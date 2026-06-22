package com.jeeves.desktop.audio

import com.jeeves.shared.domain.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.*
import kotlin.math.log10
import kotlin.math.max

/**
 * Desktop audio recorder using javax.sound.sampled.
 * Records microphone input to WAV files at 16kHz mono or stereo (optimal for Whisper).
 * Supports recording from a specific audio device (e.g., BlackHole for system audio capture).
 * Exposes real-time audio level (0.0 to 1.0) for visual feedback.
 */
class DesktopAudioRecorder : AudioRecorder {

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Current audio level, 0.0 (silence) to 1.0 (max). Updated ~30 times/sec. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /**
     * Exposes an error message when a stereo recording was requested but the device
     * does not support 2-channel capture, causing a fallback to mono.
     * Null when no fallback has occurred.
     */
    private val _stereoFallbackError = MutableStateFlow<String?>(null)
    val stereoFallbackError: StateFlow<String?> = _stereoFallbackError.asStateFlow()

    private var targetLine: TargetDataLine? = null
    private var recordingThread: Thread? = null
    private var audioBuffer = ByteArrayOutputStream()
    private var outputPath: String = ""
    private var isPaused = false

    /**
     * The audio format resolved at session start. Once recording begins, this does not change
     * until the session ends (requirement 6.6).
     * Exposed as read-only for StreamingTranscriber to determine channel count.
     */
    var sessionFormat: AudioFormat? = null
        private set

    /**
     * Optional device name to record from. When set, recording will use the specified
     * audio input device instead of the system default. Set this before calling startRecording.
     * Used for system audio capture via virtual audio devices like BlackHole.
     */
    var deviceName: String = ""

    /**
     * When true, also captures system audio (loopback) alongside the microphone
     * and mixes both into the final recording. This allows capturing both sides
     * of a call (your voice via mic + other participants via system audio).
     */
    var captureSystemAudio: Boolean = false

    private val systemCapture = SystemAudioCapture()

    /**
     * Returns a list of available audio input devices that support 16kHz 16-bit capture.
     * Useful for letting the user select a specific input (e.g., BlackHole for system audio).
     */
    fun getAvailableInputDevices(): List<AudioInputDevice> {
        val devices = mutableListOf<AudioInputDevice>()
        val format = buildAudioFormat(channels = 1)
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

        for (mixerInfo in AudioSystem.getMixerInfo()) {
            try {
                val mixer = AudioSystem.getMixer(mixerInfo)
                if (mixer.isLineSupported(lineInfo)) {
                    devices.add(
                        AudioInputDevice(
                            name = mixerInfo.name,
                            description = mixerInfo.description,
                            vendor = mixerInfo.vendor
                        )
                    )
                }
            } catch (_: Exception) {
                // Skip devices that throw when queried
            }
        }
        return devices
    }

    override suspend fun startRecording(outputPath: String, stereo: Boolean) {
        this.outputPath = outputPath
        audioBuffer = ByteArrayOutputStream()
        isPaused = false
        _stereoFallbackError.value = null

        withContext(Dispatchers.IO) {
            val resolvedFormat = resolveAudioFormat(stereo)
            sessionFormat = resolvedFormat

            val info = DataLine.Info(TargetDataLine::class.java, resolvedFormat)

            // If a specific device is configured, find and use that mixer
            val line: TargetDataLine = if (deviceName.isNotEmpty()) {
                findDeviceLine(deviceName, info)
                    ?: throw IllegalStateException(
                        "Audio device '$deviceName' not found or does not support the required format. " +
                            "Check that the device is connected and available."
                    )
            } else {
                if (!AudioSystem.isLineSupported(info)) {
                    throw IllegalStateException("Audio line not supported. Check microphone access.")
                }
                AudioSystem.getLine(info) as TargetDataLine
            }

            targetLine = line.apply {
                open(resolvedFormat)
                start()
            }

            _isRecording.value = true

            // Start system audio capture if enabled
            if (captureSystemAudio) {
                systemCapture.startCapture()
            }

            recordingThread = Thread({
                val buffer = ByteArray(4096)
                while (_isRecording.value) {
                    if (!isPaused) {
                        val bytesRead = targetLine?.read(buffer, 0, buffer.size) ?: 0
                        if (bytesRead > 0) {
                            synchronized(audioBuffer) {
                                audioBuffer.write(buffer, 0, bytesRead)
                            }
                            // Calculate RMS level from the buffer
                            _audioLevel.value = calculateLevel(buffer, bytesRead)
                        }
                    } else {
                        _audioLevel.value = 0f
                        Thread.sleep(50)
                    }
                }
                _audioLevel.value = 0f
            }, "AudioRecorder").apply {
                isDaemon = true
                start()
            }
        }
    }

    override suspend fun stopRecording(): String {
        _isRecording.value = false
        _audioLevel.value = 0f

        val format = sessionFormat ?: throw IllegalStateException("No active recording session")

        withContext(Dispatchers.IO) {
            recordingThread?.join(2000)
            targetLine?.stop()
            targetLine?.close()
            targetLine = null

            // Get microphone audio
            var audioData = synchronized(audioBuffer) { audioBuffer.toByteArray() }

            // Mix in system audio if it was being captured
            if (captureSystemAudio) {
                val systemAudioData = systemCapture.stopCapture()
                if (systemAudioData.isNotEmpty()) {
                    audioData = systemCapture.mixAudio(audioData, systemAudioData)
                }
            }

            // Write WAV file using the session format
            val audioInputStream = AudioInputStream(
                audioData.inputStream(),
                format,
                audioData.size.toLong() / format.frameSize
            )

            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, outputFile)
        }

        sessionFormat = null
        return outputPath
    }

    override suspend fun pauseRecording() {
        isPaused = true
    }

    override suspend fun resumeRecording() {
        isPaused = false
    }

    /**
     * Resolve the audio format for this recording session.
     * Attempts stereo (2 channels) if requested; falls back to mono if the system
     * does not support 2-channel capture on any available line.
     */
    private fun resolveAudioFormat(stereo: Boolean): AudioFormat {
        if (!stereo) {
            return buildAudioFormat(channels = 1)
        }

        val stereoFormat = buildAudioFormat(channels = 2)
        val stereoInfo = DataLine.Info(TargetDataLine::class.java, stereoFormat)

        return if (AudioSystem.isLineSupported(stereoInfo)) {
            stereoFormat
        } else {
            _stereoFallbackError.value =
                "Stereo recording requested but the audio device does not support 2-channel capture. Falling back to mono."
            buildAudioFormat(channels = 1)
        }
    }

    /**
     * Build an AudioFormat with the given channel count.
     * Uses 16kHz sample rate, 16-bit signed PCM, little-endian — optimal for Whisper.
     */
    private fun buildAudioFormat(channels: Int): AudioFormat {
        return AudioFormat(
            16000f,   // Sample rate - 16kHz optimal for Whisper
            16,       // Sample size in bits
            channels, // 1 = mono, 2 = stereo
            true,     // Signed
            false     // Little-endian
        )
    }

    /**
     * Returns a snapshot of audio bytes from the given offset to the current buffer end.
     * Thread-safe: synchronizes on audioBuffer, copies bytes under lock, then returns.
     */
    fun getBufferSnapshot(fromOffset: Int): ByteArray {
        synchronized(audioBuffer) {
            val allBytes = audioBuffer.toByteArray()
            return if (fromOffset >= allBytes.size) {
                ByteArray(0)
            } else {
                allBytes.copyOfRange(fromOffset, allBytes.size)
            }
        }
    }

    /**
     * Returns the current size of the audio buffer in bytes.
     * Thread-safe: synchronizes on audioBuffer.
     */
    fun getBufferSize(): Int {
        synchronized(audioBuffer) {
            return audioBuffer.size()
        }
    }

    /**
     * Calculate normalised audio level (0.0-1.0) from 16-bit PCM samples.
     * Uses RMS with a dB scale mapped to a visual range.
     */
    private fun calculateLevel(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0.0
        val sampleCount = bytesRead / 2 // 16-bit = 2 bytes per sample

        for (i in 0 until bytesRead - 1 step 2) {
            // Little-endian 16-bit signed
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += (sample * sample).toDouble()
        }

        if (sampleCount == 0) return 0f

        val rms = Math.sqrt(sum / sampleCount)
        // Convert to dB, normalise to 0.0-1.0 range
        // -60dB = silence, 0dB = max
        val db = 20 * log10(max(rms, 1.0) / 32768.0)
        // Map -60..0 dB to 0.0..1.0
        val normalised = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        return normalised
    }

    /**
     * Find a TargetDataLine from a specific mixer/device by name.
     * Returns null if no matching device is found or the device doesn't support the format.
     */
    private fun findDeviceLine(name: String, lineInfo: DataLine.Info): TargetDataLine? {
        for (mixerInfo in AudioSystem.getMixerInfo()) {
            if (mixerInfo.name == name) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(lineInfo)) {
                        return mixer.getLine(lineInfo) as TargetDataLine
                    }
                } catch (_: Exception) {
                    // Device exists but can't provide the line
                }
            }
        }
        return null
    }
}

/**
 * Represents an available audio input device.
 */
data class AudioInputDevice(
    val name: String,
    val description: String,
    val vendor: String
)
