package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import java.io.File
import java.time.LocalDateTime

data class CalendarEvent(
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val isOngoing: Boolean
)

/**
 * Reads upcoming calendar events. Uses platform-specific approaches:
 * - macOS: AppleScript to query Calendar.app
 * - Windows: PowerShell to query Outlook COM object
 *
 * Returns null gracefully if the platform doesn't support calendar access
 * or the required app (Calendar / Outlook) is not available.
 */
class CalendarService {

    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Get the next upcoming meeting within the next 30 minutes,
     * or the currently ongoing meeting.
     */
    fun getNextMeeting(): CalendarEvent? {
        return try {
            when {
                isMacOS -> getNextMeetingMacOS()
                isWindows -> getNextMeetingWindows()
                else -> null
            }
        } catch (e: Exception) {
            AppLogger.error("CalendarService", "Failed to read calendar: ${e.message}")
            null
        }
    }

    // --- macOS: AppleScript ---

    private fun getNextMeetingMacOS(): CalendarEvent? {
        val script = """
            set now to current date
            set later to now + 30 * minutes
            set eventList to {}
            
            tell application "Calendar"
                repeat with cal in calendars
                    set calEvents to (every event of cal whose start date >= now and start date <= later)
                    repeat with evt in calEvents
                        set evtTitle to summary of evt
                        set end of eventList to evtTitle & "|UPCOMING"
                    end repeat
                    set ongoingEvents to (every event of cal whose start date <= now and end date >= now)
                    repeat with evt in ongoingEvents
                        set evtTitle to summary of evt
                        set end of eventList to evtTitle & "|ONGOING"
                    end repeat
                end repeat
            end tell
            
            set AppleScript's text item delimiters to "\n"
            return eventList as string
        """.trimIndent()

        val process = ProcessBuilder("/usr/bin/osascript", "-e", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0 || output.isEmpty()) return null

        val lines = output.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val parts = lines.first().split("|")
        if (parts.isEmpty()) return null

        return CalendarEvent(
            title = parts[0].trim(),
            startTime = LocalDateTime.now(),
            endTime = LocalDateTime.now().plusHours(1),
            isOngoing = parts.getOrNull(1)?.contains("ONGOING") == true
        )
    }

    // --- Windows: PowerShell + Outlook COM ---

    private fun getNextMeetingWindows(): CalendarEvent? {
        // Write PowerShell script to a temp file to avoid quoting issues
        val scriptFile = File(System.getProperty("java.io.tmpdir"), "jeeves_calendar.ps1")
        scriptFile.writeText("""
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            try {
                ${'$'}outlook = New-Object -ComObject Outlook.Application
                if (-not ${'$'}outlook) { exit 0 }
                ${'$'}namespace = ${'$'}outlook.GetNamespace("MAPI")
                ${'$'}calendar = ${'$'}namespace.GetDefaultFolder(9)
                ${'$'}now = Get-Date
                ${'$'}later = ${'$'}now.AddMinutes(30)
                ${'$'}items = ${'$'}calendar.Items
                ${'$'}items.Sort("[Start]")
                ${'$'}items.IncludeRecurrences = ${'$'}true
                ${'$'}filter = "[Start] >= '" + ${'$'}now.ToString("g") + "' AND [Start] <= '" + ${'$'}later.ToString("g") + "'"
                ${'$'}upcoming = ${'$'}items.Restrict(${'$'}filter)
                if (${'$'}upcoming.Count -gt 0) {
                    ${'$'}evt = ${'$'}upcoming.Item(1)
                    Write-Output (${'$'}evt.Subject + "|UPCOMING")
                } else {
                    ${'$'}ongoingFilter = "[Start] <= '" + ${'$'}now.ToString("g") + "' AND [End] >= '" + ${'$'}now.ToString("g") + "'"
                    ${'$'}ongoing = ${'$'}items.Restrict(${'$'}ongoingFilter)
                    if (${'$'}ongoing.Count -gt 0) {
                        ${'$'}evt = ${'$'}ongoing.Item(1)
                        Write-Output (${'$'}evt.Subject + "|ONGOING")
                    }
                }
            } catch { }
        """.trimIndent())

        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return null
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        if (output.isEmpty()) return null

        val parts = output.split("|")
        if (parts.isEmpty()) return null

        return CalendarEvent(
            title = parts[0].trim(),
            startTime = LocalDateTime.now(),
            endTime = LocalDateTime.now().plusHours(1),
            isOngoing = parts.getOrNull(1)?.contains("ONGOING") == true
        )
    }
}
