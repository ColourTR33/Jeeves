package com.jeeves.desktop.audio

import com.jeeves.shared.ai.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Status of system audio capture, surfaced in the recording UI.
 */
enum class SystemCaptureStatus {
    /** Not started or not enabled */
    INACTIVE,
    /** Actively capturing system audio */
    CAPTURING,
    /** Failed to open loopback device */
    FAILED,
    /** No loopback device found on this machine */
    UNAVAILABLE
}

/**
 * Captures system audio (what comes out of the speakers) on Windows by finding
 * and recording from available loopback/output devices.
 *
 * On Windows, this looks for mixers that contain keywords like "Stereo Mix",
 * "CABLE Output", "What U Hear", or "Loopback" — these are virtual input devices
 * that mirror system audio output.
 *
 * Key design: Windows loopback devices often reject arbitrary format requests.
 * They typically only accept the system's current playback format (e.g. 48kHz stereo).
 * This class tries multiple common formats in order and resamples the captured audio
 * to 16kHz mono on stopCapture() so it can be mixed with the microphone recording.
 *
 * When enabled, the DesktopAudioRecorder will mix both mic and system audio into
 * one recording so both sides of a call are captured.
 */
class SystemAudioCapture {

    private var loopbackLine: TargetDataLine? = null
    private var captureThread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var isCapturing = false

    /** The format that was actually opened on the loopback device. */
    private var capturedFormat: AudioFormat? = null

    /** Observable status for the UI. */
    private val _status = MutableStateFlow(SystemCaptureStatus.INACTIVE)
    val status: StateFlow<SystemCaptureStatus> = _status.asStateFlow()

    /** Human-readable detail about the capture state (device name, error, etc.). */
    private val _statusDetail = MutableStateFlow<String?>(null)
    val statusDetail: StateFlow<String?> = _statusDetail.asStateFlow()

    private val loopbackKeywords = listOf(
        "stereo mix", "what u hear", "cable output", "loopback",
        "vb-audio", "voicemeeter", "wasapi"
    )

    /**
     * Formats to attempt when opening a loopback device, in priority order.
     * Windows Stereo Mix typically works best at the system's native rate (48 or 44.1 kHz).
     * We try higher fidelity first and resample down to 16kHz mono later.
     */
    private val formatsToTry = listOf(
        AudioFormat(48000f, 16, 2, true, false),  // 48kHz stereo LE — most common Windows default
        AudioFormat(44100f, 16, 2, true, false),  // 44.1kHz stereo LE
        AudioFormat(48000f, 16, 1, true, false),  // 48kHz mono LE
        AudioFormat(44100f, 16, 1, true, false),  // 44.1kHz mono LE
        AudioFormat(16000f, 16, 2, true, false),  // 16kHz stereo LE
        AudioFormat(16000f, 16, 1, true, false),  // 16kHz mono LE — ideal but rarely supported
    )

    /**
     * Finds available system audio loopback devices on this machine.
     * Checks against all candidate formats, not just 16kHz mono.
     */
    fun getLoopbackDevices(): List<AudioInputDevice> {
        val devices = mutableListOf<AudioInputDevice>()

        for (mixerInfo in AudioSystem.getMixerInfo()) {
            val nameLower = mixerInfo.name.lowercase()
            val descLower = mixerInfo.description.lowercase()

            val isLoopback = loopbackKeywords.any { keyword ->
                nameLower.contains(keyword) || descLower.contains(keyword)
            }

            if (isLoopback) {
                // Check if ANY of our candidate formats is supported
                val supported = formatsToTry.any { format ->
                    try {
                        val info = DataLine.Info(TargetDataLine::class.java, format)
                        AudioSystem.getMixer(mixerInfo).isLineSupported(info)
                    } catch (_: Exception) { false }
                }

                if (supported) {
                    devices.add(AudioInputDevice(
                        name = mixerInfo.name,
                        description = mixerInfo.description,
                        vendor = mixerInfo.vendor
                    ))
                }
            }
        }
        return devices
    }

    /**
     * Start capturing from the specified loopback device (or the first available one).
     * Tries multiple audio formats in priority order until one works.
     * Returns true if capture started successfully.
     */
    fun startCapture(deviceName: String? = null): Boolean {
        _status.value = SystemCaptureStatus.INACTIVE
        _statusDetail.value = null

        // Find a matching mixer
        val targetMixerInfo = if (deviceName != null) {
            AudioSystem.getMixerInfo().find { it.name == deviceName }
        } else {
            // Auto-find first loopback device that supports any of our formats
            AudioSystem.getMixerInfo().find { info ->
                val nameLower = info.name.lowercase()
                loopbackKeywords.any { nameLower.contains(it) } &&
                    formatsToTry.any { format ->
                        try {
                            val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
                            AudioSystem.getMixer(info).isLineSupported(lineInfo)
                        } catch (_: Exception) { false }
                    }
            }
        }

        if (targetMixerInfo == null) {
            AppLogger.warn("SystemAudioCapture", "No loopback device found")
            _status.value = SystemCaptureStatus.UNAVAILABLE
            _statusDetail.value = "No system audio device found (Stereo Mix disabled?)"
            return false
        }

        // Try each format until one opens successfully
        val mixer = AudioSystem.getMixer(targetMixerInfo)
        var openedLine: TargetDataLine? = null
        var openedFormat: AudioFormat? = null

        for (format in formatsToTry) {
            val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
            if (!mixer.isLineSupported(lineInfo)) continue

            try {
                val line = mixer.getLine(lineInfo) as TargetDataLine
                line.open(format)
                line.start()
                openedLine = line
                openedFormat = format
                AppLogger.info(
                    "SystemAudioCapture",
                    "Opened ${targetMixerInfo.name} at ${format.sampleRate}Hz ${format.channels}ch"
                )
                break
            } catch (e: Exception) {
                AppLogger.warn(
                    "SystemAudioCapture",
                    "Format ${format.sampleRate}Hz ${format.channels}ch rejected: ${e.message}"
                )
            }
        }

        if (openedLine == null || openedFormat == null) {
            AppLogger.error("SystemAudioCapture", "All formats rejected by ${targetMixerInfo.name}")
            _status.value = SystemCaptureStatus.FAILED
            _statusDetail.value = "System audio device rejected all formats"
            return false
        }

        loopbackLine = openedLine
        capturedFormat = openedFormat
        buffer.reset()
        isCapturing = true

        captureThread = Thread({
            val readBuffer = ByteArray(4096)
            while (isCapturing) {
                val bytesRead = loopbackLine?.read(readBuffer, 0, readBuffer.size) ?: 0
                if (bytesRead > 0) {
                    synchronized(buffer) {
                        buffer.write(readBuffer, 0, bytesRead)
                    }
                }
            }
        }, "SystemAudioCapture").apply {
            isDaemon = true
            start()
        }

        _status.value = SystemCaptureStatus.CAPTURING
        _statusDetail.value = "Capturing from ${targetMixerInfo.name} (${openedFormat.sampleRate.toInt()}Hz)"
        AppLogger.info("SystemAudioCapture", "Started capturing from: ${targetMixerInfo.name}")
        return true
    }

    /**
     * Stop capturing and return the captured audio resampled to 16kHz mono 16-bit PCM.
     * This matches the mic recording format so both can be mixed sample-by-sample.
     */
    fun stopCapture(): ByteArray {
        isCapturing = false
        captureThread?.join(2000)
        loopbackLine?.stop()
        loopbackLine?.close()
        loopbackLine = null
        captureThread = null

        val rawBytes = synchronized(buffer) { buffer.toByteArray() }
        val format = capturedFormat

        _status.value = SystemCaptureStatus.INACTIVE

        if (format == null || rawBytes.isEmpty()) {
            return ByteArray(0)
        }

        // If already 16kHz mono, return as-is
        if (format.sampleRate == 16000f && format.channels == 1) {
            capturedFormat = null
            return rawBytes
        }

        // Otherwise resample to 16kHz mono
        val resampled = resampleTo16kMono(rawBytes, format)
        capturedFormat = null
        return resampled
    }

    /**
     * Resample arbitrary 16-bit PCM data to 16kHz mono.
     * Handles stereo-to-mono downmix and sample rate conversion using linear interpolation.
     */
    private fun resampleTo16kMono(pcmData: ByteArray, sourceFormat: AudioFormat): ByteArray {
        val srcRate = sourceFormat.sampleRate
        val srcChannels = sourceFormat.channels
        val bytesPerSample = 2 // 16-bit
        val frameSize = srcChannels * bytesPerSample
        val totalFrames = pcmData.size / frameSize

        if (totalFrames == 0) return ByteArray(0)

        // Step 1: Read source frames and downmix to mono
        val monoSamples = FloatArray(totalFrames)
        for (i in 0 until totalFrames) {
            var sum = 0f
            for (ch in 0 until srcChannels) {
                val offset = i * frameSize + ch * bytesPerSample
                if (offset + 1 < pcmData.size) {
                    val sample = (pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF)
                    sum += sample.toFloat()
                }
            }
            monoSamples[i] = sum / srcChannels
        }

        // Step 2: Resample from srcRate to 16000Hz using linear interpolation
        val targetRate = 16000.0
        val ratio = srcRate.toDouble() / targetRate
        val outputFrames = (totalFrames / ratio).toInt()

        if (outputFrames == 0) return ByteArray(0)

        val output = ByteArray(outputFrames * bytesPerSample)
        for (i in 0 until outputFrames) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = (srcPos - srcIndex).toFloat()

            val s0 = monoSamples[srcIndex.coerceIn(0, totalFrames - 1)]
            val s1 = monoSamples[(srcIndex + 1).coerceIn(0, totalFrames - 1)]
            val interpolated = (s0 + frac * (s1 - s0)).roundToInt().coerceIn(-32768, 32767)

            val outOffset = i * bytesPerSample
            output[outOffset] = (interpolated and 0xFF).toByte()
            output[outOffset + 1] = ((interpolated shr 8) and 0xFF).toByte()
        }

        AppLogger.info(
            "SystemAudioCapture",
            "Resampled ${totalFrames} frames (${srcRate}Hz ${srcChannels}ch) → ${outputFrames} frames (16kHz mono)"
        )

        return output
    }

    /**
     * Mix microphone PCM bytes with system audio PCM bytes.
     * Both must be 16-bit signed little-endian mono at 16kHz (system audio is
     * resampled by stopCapture() before this is called).
     * Returns mixed audio (averaged samples to prevent clipping).
     */
    fun mixAudio(micBytes: ByteArray, systemBytes: ByteArray): ByteArray {
        val maxLen = max(micBytes.size, systemBytes.size)
        val result = ByteArray(maxLen)

        for (i in 0 until maxLen - 1 step 2) {
            val micSample = if (i + 1 < micBytes.size) {
                (micBytes[i + 1].toInt() shl 8) or (micBytes[i].toInt() and 0xFF)
            } else 0

            val sysSample = if (i + 1 < systemBytes.size) {
                (systemBytes[i + 1].toInt() shl 8) or (systemBytes[i].toInt() and 0xFF)
            } else 0

            // Average to prevent clipping, with slight boost
            val mixed = ((micSample + sysSample) / 2).coerceIn(-32768, 32767)

            result[i] = (mixed and 0xFF).toByte()
            if (i + 1 < maxLen) result[i + 1] = ((mixed shr 8) and 0xFF).toByte()
        }

        return result
    }

    /**
     * Check if system audio capture is available on this machine.
     */
    fun isAvailable(): Boolean = getLoopbackDevices().isNotEmpty()

    /**
     * Get setup instructions for the current platform.
     */
    fun getSetupInstructions(): String {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            "To capture system audio (other people on calls):\n" +
            "1. Right-click the speaker icon in the taskbar → Sound settings\n" +
            "2. Click 'More sound settings' → Recording tab\n" +
            "3. Right-click in the list → 'Show Disabled Devices'\n" +
            "4. Enable 'Stereo Mix' and set it as default\n\n" +
            "Alternative: Install VB-CABLE (free virtual audio cable)\n" +
            "from https://vb-audio.com/Cable/"
        } else {
            "To capture system audio on macOS:\n" +
            "1. Install BlackHole (free virtual audio driver)\n" +
            "2. Open Audio MIDI Setup → Create Multi-Output Device\n" +
            "3. Select BlackHole as the recording device in Jeeves settings"
        }
    }
}
