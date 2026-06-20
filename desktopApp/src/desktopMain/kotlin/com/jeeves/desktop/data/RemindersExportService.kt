package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import java.io.IOException

/**
 * Exports action items to Apple Reminders via osascript (AppleScript).
 * Creates reminders in a "Jeeves" list, creating the list if it doesn't exist.
 */
class RemindersExportService {

    /**
     * Export action items as individual reminders in Apple Reminders.
     * Creates a "Jeeves" reminder list if it doesn't exist.
     * Returns the number of reminders successfully created.
     */
    fun exportToReminders(actionItems: List<String>, meetingTitle: String): Int {
        if (actionItems.isEmpty()) return 0

        // Ensure the "Jeeves" list exists
        ensureListExists()

        var created = 0
        for (item in actionItems) {
            if (createReminder(item, meetingTitle)) {
                created++
            }
        }

        AppLogger.info("RemindersExport", "Created $created/${actionItems.size} reminders for '$meetingTitle'")
        return created
    }

    private fun ensureListExists() {
        val script = """
            tell application "Reminders"
                if not (exists list "Jeeves") then
                    make new list with properties {name:"Jeeves"}
                end if
            end tell
        """.trimIndent()
        runAppleScript(script)
    }

    private fun createReminder(text: String, meetingTitle: String): Boolean {
        // Escape for AppleScript string literals (double backslashes and quotes)
        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedTitle = meetingTitle.replace("\\", "\\\\").replace("\"", "\\\"")

        val script = """
            tell application "Reminders"
                tell list "Jeeves"
                    make new reminder with properties {name:"$escapedText", body:"From meeting: $escapedTitle"}
                end tell
            end tell
        """.trimIndent()

        return runAppleScript(script)
    }

    private fun runAppleScript(script: String): Boolean {
        return try {
            // Write script to temp file to avoid shell escaping issues
            val tmpFile = java.io.File.createTempFile("jeeves_script", ".scpt")
            tmpFile.writeText(script)
            tmpFile.deleteOnExit()

            val process = ProcessBuilder("/usr/bin/osascript", tmpFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.inputStream.bufferedReader().readText()
                AppLogger.error("RemindersExport", "AppleScript failed (exit $exitCode): $error")
            }
            tmpFile.delete()
            exitCode == 0
        } catch (e: IOException) {
            AppLogger.error("RemindersExport", "Failed to run osascript: ${e.message}")
            false
        }
    }
}
