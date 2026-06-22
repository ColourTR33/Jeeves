package com.jeeves.desktop.audio

import com.jeeves.shared.ai.AppLogger
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.*
import kotlin.math.max

/**
 * Captures system audio (what comes out of the speakers) on Windows by finding
 * and recording from available loopback/output devices.
 *
 * On Windows, this looks for mixers that contain keywords like "Stereo Mix",
 * "CABLE Output", "What U Hear", or "Loopback" — these are virtual input devices
 * that mirror system audio output.
 *
 * If no loopback device is found, this class provides instructions for enabling one.
 *
 * When enabled, the DesktopAudioRecorder will mix both mic and system audio into
 * one recording so both sides of a call are captured.
 */
class SystemAudioCapture {

    private var loopbackLine: TargetDataLine? = null
    private var captureThread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var isCapturing = false

    private val loopbackKeywords = listOf(
        "stereo mix", "what u hear", "cable output", "loopback",
        "vb-audio", "voicemeeter", "wasapi"
    )

    /**
     * Finds available system audio loopback devices on this machine.
     */
    fun getLoopbackDevices(): List<AudioInputDevice> {
        val devices = mutableListOf<AudioInputDevice>()
        val format = AudioFormat(16000f, 16, 1, true, false)
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

        for (mixerInfo in AudioSystem.getMixerInfo()) {
            val nameLower = mixerInfo.name.lowercase()
            val descLower = mixerInfo.description.lowercase()

            val isLoopback = loopbackKeywords.any { keyword ->
                nameLower.contains(keyword) || descLower.contains(keyword)
            }

            if (isLoopback) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(lineInfo)) {
                        devices.add(AudioInputDevice(
                            name = mixerInfo.name,
                            description = mixerInfo.description,
                            vendor = mixerInfo.vendor
                        ))
                    }
                } catch (_: Exception) {}
            }
        }
        return devices
    }

    /**
     * Start capturing from the specified loopback device (or the first available one).
     * Returns true if capture started successfully.
     */
    fun startCapture(deviceName: String? = null): Boolean {
        val format = AudioFormat(16000f, 16, 1, true, false)
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

        val targetDevice = if (deviceName != null) {
            AudioSystem.getMixerInfo().find { it.name == deviceName }
        } else {
            // Auto-find first loopback device
            AudioSystem.getMixerInfo().find { info ->
                val nameLower = info.name.lowercase()
                loopbackKeywords.any { nameLower.contains(it) } &&
                    try { AudioSystem.getMixer(info).isLineSupported(lineInfo) } catch (_: Exception) { false }
            }
        }

        if (targetDevice == null) {
            AppLogger.warn("SystemAudioCapture", "No loopback device found")
            return false
        }

        return try {
            val mixer = AudioSystem.getMixer(targetDevice)
            loopbackLine = (mixer.getLine(lineInfo) as TargetDataLine).apply {
                open(format)
                start()
            }

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

            AppLogger.info("SystemAudioCapture", "Started capturing from: ${targetDevice.name}")
            true
        } catch (e: Exception) {
            AppLogger.error("SystemAudioCapture", "Failed to start capture: ${e.message}")
            false
        }
    }

    /**
     * Stop capturing and return the captured audio bytes.
     */
    fun stopCapture(): ByteArray {
        isCapturing = false
        captureThread?.join(2000)
        loopbackLine?.stop()
        loopbackLine?.close()
        loopbackLine = null
        captureThread = null

        return synchronized(buffer) { buffer.toByteArray() }
    }

    /**
     * Mix microphone PCM bytes with system audio PCM bytes.
     * Both must be 16-bit signed little-endian mono at the same sample rate.
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
