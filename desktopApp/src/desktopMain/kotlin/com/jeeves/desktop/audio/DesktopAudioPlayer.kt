package com.jeeves.desktop.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.*

/**
 * Desktop audio player using javax.sound.sampled.
 * Plays back WAV recordings with position tracking.
 */
class DesktopAudioPlayer {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var clip: Clip? = null
    private var positionThread: Thread? = null

    suspend fun play(filePath: String) {
        withContext(Dispatchers.IO) {
            stop()

            val file = File(filePath)
            if (!file.exists()) return@withContext

            val audioInputStream = AudioSystem.getAudioInputStream(file)
            val format = audioInputStream.format
            val frames = audioInputStream.frameLength
            val durationSeconds = frames / format.frameRate
            _durationMs.value = (durationSeconds * 1000).toLong()

            clip = AudioSystem.getClip().apply {
                open(audioInputStream)
                start()
            }

            _isPlaying.value = true
            startPositionTracking()
        }
    }

    fun pause() {
        clip?.let {
            if (it.isRunning) {
                it.stop()
                _isPlaying.value = false
                stopPositionTracking()
            }
        }
    }

    fun resume() {
        clip?.let {
            if (!it.isRunning) {
                it.start()
                _isPlaying.value = true
                startPositionTracking()
            }
        }
    }

    fun stop() {
        stopPositionTracking()
        clip?.let {
            it.stop()
            it.close()
        }
        clip = null
        _isPlaying.value = false
        _positionMs.value = 0
    }

    fun seekTo(positionMs: Long) {
        clip?.let {
            val frameRate = it.format.frameRate
            val frame = ((positionMs / 1000.0) * frameRate).toLong()
            it.framePosition = frame.toInt().coerceIn(0, it.frameLength - 1)
            _positionMs.value = positionMs
        }
    }

    private fun startPositionTracking() {
        stopPositionTracking()
        positionThread = Thread({
            while (_isPlaying.value) {
                clip?.let {
                    val currentMs = (it.framePosition / it.format.frameRate * 1000).toLong()
                    _positionMs.value = currentMs

                    // Auto-stop at end
                    if (it.framePosition >= it.frameLength) {
                        _isPlaying.value = false
                        _positionMs.value = _durationMs.value
                    }
                }
                Thread.sleep(100)
            }
        }, "AudioPositionTracker").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPositionTracking() {
        positionThread?.interrupt()
        positionThread = null
    }
}
