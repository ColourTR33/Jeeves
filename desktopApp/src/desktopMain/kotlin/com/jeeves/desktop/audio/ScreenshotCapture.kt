package com.jeeves.desktop.audio

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.Attachment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Cross-platform screenshot capture utility.
 * Uses Java's Robot API (works on Windows, macOS, Linux).
 * Hides the Jeeves window during capture so it doesn't appear in the screenshot.
 */
object ScreenshotCapture {

    private val screenshotsDir: File by lazy {
        val dir = File(System.getProperty("user.home"), "Jeeves/recordings/screenshots")
        dir.mkdirs()
        dir
    }

    /**
     * Captures the full screen and saves as PNG.
     * Briefly hides all Jeeves windows, captures via Robot, then restores.
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

            // Small delay to ensure windows are hidden
            Thread.sleep(150)

            try {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                val robot = Robot()
                val capture = robot.createScreenCapture(Rectangle(0, 0, screenSize.width, screenSize.height))
                ImageIO.write(capture, "png", file)
            } finally {
                // Always restore windows
                windows.forEach { it.isVisible = true }
            }

            if (!file.exists() || file.length() == 0L) {
                AppLogger.error("ScreenshotCapture", "Screenshot file not created")
                return null
            }

            AppLogger.info("ScreenshotCapture", "Screenshot saved: ${file.name} (${file.length() / 1024}KB)")

            Attachment(
                id = id,
                filePath = file.absolutePath,
                timestampMs = timestampMs,
                caption = ""
            )
        } catch (e: Exception) {
            AppLogger.error("ScreenshotCapture", "Failed to capture screen: ${e.message}")
            Window.getWindows().forEach { if (!it.isVisible) it.isVisible = true }
            null
        }
    }
}
