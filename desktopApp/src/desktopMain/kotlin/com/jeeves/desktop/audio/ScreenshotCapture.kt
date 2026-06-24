package com.jeeves.desktop.audio

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.Attachment
import java.awt.Window
import java.io.File
import java.util.UUID

/**
 * Utility for capturing screenshots during a recording session.
 * Uses macOS `screencapture` command for native screenshot behaviour.
 * Hides the Jeeves window during capture so it doesn't appear in the screenshot.
 */
object ScreenshotCapture {

    private val screenshotsDir: File by lazy {
        val dir = File(System.getProperty("user.home"), "Jeeves/recordings/screenshots")
        dir.mkdirs()
        dir
    }

    /**
     * Captures the screen excluding the Jeeves window and saves it as a PNG file.
     * Briefly hides all Jeeves windows, takes the screenshot using macOS screencapture,
     * then restores the windows.
     *
     * @param timestampMs milliseconds into the recording when the screenshot was taken
     */
    fun captureScreen(timestampMs: Long): Attachment? {
        return try {
            val id = UUID.randomUUID().toString()
            val fileName = "screenshot_${System.currentTimeMillis()}.png"
            val file = File(screenshotsDir, fileName)

            // Hide all Jeeves windows before capture
            val windows = Window.getWindows().filter { it.isVisible }
            windows.forEach { it.isVisible = false }

            // Small delay to ensure windows are hidden before capture
            Thread.sleep(100)

            try {
                // Use macOS screencapture: -x = no sound, -C = capture cursor, -m = main display only
                val process = ProcessBuilder("/usr/sbin/screencapture", "-x", "-m", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    AppLogger.error("ScreenshotCapture", "screencapture timed out")
                    return null
                }

                if (process.exitValue() != 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    AppLogger.error("ScreenshotCapture", "screencapture failed: $output")
                    return null
                }
            } finally {
                // Always restore windows
                windows.forEach { it.isVisible = true }
            }

            if (!file.exists() || file.length() == 0L) {
                AppLogger.error("ScreenshotCapture", "Screenshot file not created")
                return null
            }

            Attachment(
                id = id,
                filePath = file.absolutePath,
                timestampMs = timestampMs,
                caption = ""
            )
        } catch (e: Exception) {
            AppLogger.error("ScreenshotCapture", "Failed to capture screen: ${e.message}")
            // Ensure windows are restored on exception
            Window.getWindows().forEach { if (!it.isVisible) it.isVisible = true }
            null
        }
    }
}
