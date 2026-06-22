package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Exports action items to Apple Reminders via .ics file (iCalendar VTODO).
 * 
 * This approach opens a .ics file containing VTODO entries which macOS Reminders
 * natively imports — no AppleScript or Automation permissions required.
 */
class RemindersExportService {

    private val icsDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Export action items as reminders by generating a .ics file and opening it.
     * macOS will prompt the user to import into Reminders.
     * Returns the number of items written to the file.
     */
    fun exportToReminders(actionItems: List<String>, meetingTitle: String): Int {
        if (actionItems.isEmpty()) return 0

        return try {
            val icsContent = buildIcsContent(actionItems, meetingTitle)
            val icsFile = writeIcsFile(icsContent, meetingTitle)
            openWithReminders(icsFile)
            AppLogger.info("RemindersExport", "Created .ics with ${actionItems.size} reminders for '$meetingTitle'")
            actionItems.size
        } catch (e: Exception) {
            AppLogger.error("RemindersExport", "Failed to export reminders: ${e::class.simpleName}: ${e.message}")
            0
        }
    }

    private fun buildIcsContent(actionItems: List<String>, meetingTitle: String): String {
        val now = icsDateFormat.format(Instant.now())
        val sb = StringBuilder()

        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//Jeeves//Meeting Recorder//EN")

        for (item in actionItems) {
            val uid = UUID.randomUUID().toString()
            val escapedItem = escapeIcsText(item)
            val escapedTitle = escapeIcsText(meetingTitle)

            sb.appendLine("BEGIN:VTODO")
            sb.appendLine("UID:$uid")
            sb.appendLine("DTSTAMP:$now")
            sb.appendLine("CREATED:$now")
            sb.appendLine("SUMMARY:$escapedItem")
            sb.appendLine("DESCRIPTION:From meeting: $escapedTitle")
            sb.appendLine("STATUS:NEEDS-ACTION")
            sb.appendLine("END:VTODO")
        }

        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }

    private fun escapeIcsText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
    }

    private fun writeIcsFile(content: String, meetingTitle: String): File {
        val dir = File(System.getProperty("user.home"), "Jeeves")
        dir.mkdirs()

        // Sanitize title for filename
        val safeName = meetingTitle
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "_")
            .take(50)

        val file = File(dir, "reminders_${safeName}.ics")
        file.writeText(content)
        return file
    }

    private fun openWithReminders(file: File) {
        // Use java.awt.Desktop to open .ics with the default handler (cross-platform)
        // On macOS: opens with Reminders (for VTODO) or Calendar
        // On Windows: opens with the default .ics handler (Outlook, Windows Calendar, etc.)
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
            } else {
                // Fallback: platform-specific open command
                val os = System.getProperty("os.name").lowercase()
                val command = when {
                    os.contains("mac") -> listOf("/usr/bin/open", file.absolutePath)
                    os.contains("win") -> listOf("cmd", "/c", "start", "", file.absolutePath)
                    else -> listOf("xdg-open", file.absolutePath)
                }
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            AppLogger.error("RemindersExport", "Failed to open .ics file: ${e.message}")
        }
    }
}
