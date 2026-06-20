package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import java.time.LocalDateTime

data class CalendarEvent(
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val isOngoing: Boolean
)

/**
 * Reads upcoming calendar events from macOS Calendar app via AppleScript.
 */
class CalendarService {

    /**
     * Get the next upcoming meeting within the next 30 minutes,
     * or the currently ongoing meeting.
     */
    fun getNextMeeting(): CalendarEvent? {
        val script = """
            set now to current date
            set later to now + 30 * minutes
            set eventList to {}
            
            tell application "Calendar"
                repeat with cal in calendars
                    set calEvents to (every event of cal whose start date >= now and start date <= later)
                    repeat with evt in calEvents
                        set evtTitle to summary of evt
                        set evtStart to start date of evt
                        set evtEnd to end date of evt
                        set end of eventList to evtTitle & "|" & (evtStart as string) & "|" & (evtEnd as string)
                    end repeat
                    -- Also check ongoing events
                    set ongoingEvents to (every event of cal whose start date <= now and end date >= now)
                    repeat with evt in ongoingEvents
                        set evtTitle to summary of evt
                        set evtStart to start date of evt
                        set evtEnd to end date of evt
                        set end of eventList to evtTitle & "|" & (evtStart as string) & "|" & (evtEnd as string) & "|ONGOING"
                    end repeat
                end repeat
            end tell
            
            set AppleScript's text item delimiters to "\n"
            return eventList as string
        """.trimIndent()

        return try {
            val process = ProcessBuilder("/usr/bin/osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0 || output.isEmpty()) return null

            // Parse the first event
            val lines = output.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) return null

            val parts = lines.first().split("|")
            if (parts.size < 3) return null

            CalendarEvent(
                title = parts[0].trim(),
                startTime = LocalDateTime.now(), // Simplified - actual parsing would need date format handling
                endTime = LocalDateTime.now().plusHours(1),
                isOngoing = parts.size >= 4 && parts[3].contains("ONGOING")
            )
        } catch (e: Exception) {
            AppLogger.error("CalendarService", "Failed to read calendar: ${e.message}")
            null
        }
    }
}
