package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CalendarEvent(
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val isOngoing: Boolean,
    val attendees: List<String> = emptyList()
)

/**
 * Reads upcoming calendar events. Uses platform-specific approaches:
 * - macOS: AppleScript to query Calendar.app
 * - Windows: PowerShell to query Outlook COM object
 * - Cross-platform: ICS file reading from configured path
 *
 * Returns null gracefully if the platform doesn't support calendar access
 * or the required app (Calendar / Outlook) is not available.
 */
class CalendarService {

    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Optional path to a synced ICS calendar file (cross-platform fallback) */
    var icsFilePath: String? = null

    /**
     * Get the next upcoming meeting within the next 30 minutes,
     * or the currently ongoing meeting.
     */
    fun getNextMeeting(): CalendarEvent? {
        return try {
            // Try ICS file first if configured (cross-platform)
            icsFilePath?.let { path ->
                val icsEvent = getNextMeetingFromIcs(path)
                if (icsEvent != null) return icsEvent
            }

            // Fall back to platform-specific methods
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

    // --- ICS file parsing (cross-platform) ---

    private fun getNextMeetingFromIcs(path: String): CalendarEvent? {
        val file = File(path)
        if (!file.exists()) return null

        val now = LocalDateTime.now()
        val soon = now.plusMinutes(30)
        val content = file.readText()

        // Simple ICS parser — looks for VEVENT blocks
        val events = mutableListOf<CalendarEvent>()
        val eventBlocks = content.split("BEGIN:VEVENT")
            .drop(1) // First element is before any VEVENT
            .map { "BEGIN:VEVENT$it".substringBefore("END:VEVENT") }

        for (block in eventBlocks) {
            try {
                val title = extractIcsField(block, "SUMMARY") ?: continue
                val dtStart = extractIcsField(block, "DTSTART") ?: continue
                val dtEnd = extractIcsField(block, "DTEND")

                val startTime = parseIcsDateTime(dtStart) ?: continue
                val endTime = dtEnd?.let { parseIcsDateTime(it) } ?: startTime.plusHours(1)

                // Check if event is ongoing or upcoming
                val isOngoing = now.isAfter(startTime) && now.isBefore(endTime)
                val isUpcoming = startTime.isAfter(now) && startTime.isBefore(soon)

                if (isOngoing || isUpcoming) {
                    val attendees = extractIcsAttendees(block)
                    events.add(CalendarEvent(
                        title = title,
                        startTime = startTime,
                        endTime = endTime,
                        isOngoing = isOngoing,
                        attendees = attendees
                    ))
                }
            } catch (e: Exception) {
                // Skip malformed events
            }
        }

        // Return ongoing events first, then upcoming sorted by start time
        return events
            .sortedWith(compareBy({ !it.isOngoing }, { it.startTime }))
            .firstOrNull()
    }

    private fun extractIcsField(block: String, field: String): String? {
        // Handle both simple (SUMMARY:text) and parameterized (SUMMARY;LANGUAGE=en:text) forms
        val regex = Regex("$field[;:](.*)\\r?\\n", RegexOption.IGNORE_CASE)
        val match = regex.find(block) ?: return null
        val value = match.groupValues[1]
        // Strip parameters if present (e.g., ";LANGUAGE=en:" -> value after last colon)
        return if (value.contains(":")) value.substringAfter(":") else value
    }

    private fun extractIcsAttendees(block: String): List<String> {
        val regex = Regex("ATTENDEE[^:]*:mailto:([^\\r\\n]+)", RegexOption.IGNORE_CASE)
        return regex.findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun parseIcsDateTime(dtValue: String): LocalDateTime? {
        return try {
            // Common formats: 20240115T100000Z, 20240115T100000
            val cleanValue = dtValue.trim()
            if (cleanValue.endsWith("Z")) {
                // UTC time
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                LocalDateTime.parse(cleanValue, formatter)
                    .atZone(ZoneId.of("UTC"))
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime()
            } else if (cleanValue.contains("T")) {
                // Local time
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                LocalDateTime.parse(cleanValue, formatter)
            } else {
                // Date only
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd")
                java.time.LocalDate.parse(cleanValue, formatter).atStartOfDay()
            }
        } catch (e: Exception) {
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
