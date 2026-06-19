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

/**
 * Desktop audio recorder using javax.sound.sampled.
 * Records microphone input to WAV files at 16kHz mono (optimal for Whisper).
 */
class DesktopAudioRecorder : AudioRecorder {

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

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
                        }
                    } else {
                        Thread.sleep(50)
                    }
                }
            }, "AudioRecorder").apply {
                isDaemon = true
                start()
            }
        }
    }

    override suspend fun stopRecording(): String {
        _isRecording.value = false

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
}
