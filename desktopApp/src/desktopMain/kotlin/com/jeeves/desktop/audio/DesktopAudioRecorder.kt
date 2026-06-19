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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Desktop audio recorder using javax.sound.sampled.
 * Records microphone input to WAV files at 16kHz mono (optimal for Whisper).
 * Exposes real-time audio level (0.0 to 1.0) for visual feedback.
 */
class DesktopAudioRecorder : AudioRecorder {

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Current audio level, 0.0 (silence) to 1.0 (max). Updated ~30 times/sec. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var targetLine: TargetDataLine? = null
    private var recordingThread: Thread? = null
    private var audioBuffer = ByteArrayOutputStream()
    private var outputPath: String = ""
    private var isPaused = false

    private val audioFormat = AudioFormat(
        16000f,  // Sample rate - 16kHz optimal for Whisper
        16,      // Sample size in bits
        1,       // Channels (mono)
        true,    // Signed
        false    // Big endian
    )

    override suspend fun startRecording(outputPath: String) {
        this.outputPath = outputPath
        audioBuffer = ByteArrayOutputStream()
        isPaused = false

        withContext(Dispatchers.IO) {
            val info = DataLine.Info(TargetDataLine::class.java, audioFormat)

            if (!AudioSystem.isLineSupported(info)) {
                throw IllegalStateException("Audio line not supported. Check microphone access.")
            }

            targetLine = (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(audioFormat)
                start()
            }

            _isRecording.value = true

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

        withContext(Dispatchers.IO) {
            recordingThread?.join(2000)
            targetLine?.stop()
            targetLine?.close()
            targetLine = null

            // Write WAV file
            val audioData = synchronized(audioBuffer) { audioBuffer.toByteArray() }
            val audioInputStream = AudioInputStream(
                audioData.inputStream(),
                audioFormat,
                audioData.size.toLong() / audioFormat.frameSize
            )

            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, outputFile)
        }

        return outputPath
    }

    override suspend fun pauseRecording() {
        isPaused = true
    }

    override suspend fun resumeRecording() {
        isPaused = false
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
}
