package com.jeeves.desktop.audio

import com.jeeves.shared.domain.Attachment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Utility for capturing screenshots during a recording session.
 * Screenshots are saved to the Jeeves recordings/screenshots directory.
 */
object ScreenshotCapture {

    private val screenshotsDir: File by lazy {
        val dir = File(System.getProperty("user.home"), "Jeeves/recordings/screenshots")
        dir.mkdirs()
        dir
    }

    /**
     * Captures the full screen and saves it as a PNG file.
     * Returns an Attachment with the file path and timestamp, or null on failure.
     *
     * @param timestampMs milliseconds into the recording when the screenshot was taken
     */
    fun captureScreen(timestampMs: Long): Attachment? {
        return try {
            val robot = Robot()
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val screenRect = Rectangle(screenSize)
            val image = robot.createScreenCapture(screenRect)

            val id = UUID.randomUUID().toString()
            val fileName = "screenshot_${System.currentTimeMillis()}.png"
            val file = File(screenshotsDir, fileName)

            ImageIO.write(image, "png", file)

            Attachment(
                id = id,
                filePath = file.absolutePath,
                timestampMs = timestampMs,
                caption = ""
            )
        } catch (e: Exception) {
            println("[ScreenshotCapture] Failed to capture screen: ${e.message}")
            null
        }
    }
}
