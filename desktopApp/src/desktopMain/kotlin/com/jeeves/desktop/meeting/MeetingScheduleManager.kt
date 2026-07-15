package com.jeeves.desktop.meeting

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.MeetingScheduleRepository
import com.jeeves.shared.domain.ScheduledMeeting
import com.jeeves.shared.recording.generateId
import com.jeeves.shared.time.TimeTrackingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages scheduled meetings: CSV import, CRUD, and 5-minute alerts.
 *
 * Polls every 30 seconds to check if any meeting is starting within 5 minutes.
 * When it detects one, it fires [pendingAlert] which the UI observes to show
 * a notification and play a sound.
 */
class MeetingScheduleManager(
    private val repository: MeetingScheduleRepository,
    private val scope: CoroutineScope
) {
    private val _todayMeetings = MutableStateFlow<List<ScheduledMeeting>>(emptyList())
    val todayMeetings: StateFlow<List<ScheduledMeeting>> = _todayMeetings.asStateFlow()

    private val _tomorrowMeetings = MutableStateFlow<List<ScheduledMeeting>>(emptyList())
    val tomorrowMeetings: StateFlow<List<ScheduledMeeting>> = _tomorrowMeetings.asStateFlow()

    /** Fires when a meeting is 5 minutes away. UI should show alert + play sound. */
    private val _pendingAlert = MutableStateFlow<ScheduledMeeting?>(null)
    val pendingAlert: StateFlow<ScheduledMeeting?> = _pendingAlert.asStateFlow()

    fun initialize() {
        scope.launch {
            refreshToday()
            // Alert polling loop — checks every 30 seconds
            while (isActive) {
                delay(30_000)
                checkAlerts()
            }
        }
    }

    suspend fun refreshToday() {
        val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        val tomorrow = TimeTrackingManager.epochToDateString(System.currentTimeMillis() + 86_400_000L)
        _todayMeetings.value = repository.getMeetingsForDate(today)
        _tomorrowMeetings.value = repository.getMeetingsForDate(tomorrow)
    }

    // ─── CSV Import ─────────────────────────────────────────────────────────────

    /**
     * Import meetings from a CSV string (Outlook/Google Calendar export format).
     * Expected columns: "Event Title","Start Time","End Time","Clash","Organizer"
     * Date format: "2026-07-16 08:00 AM"
     */
    fun importCsv(csvContent: String) {
        scope.launch {
            val meetings = parseCsv(csvContent)
            if (meetings.isNotEmpty()) {
                repository.saveMeetings(meetings)
                refreshToday()
                AppLogger.info("MeetingScheduleManager", "Imported ${meetings.size} meetings")
            }
        }
    }

    private fun parseCsv(csv: String): List<ScheduledMeeting> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        // Skip header line
        val dataLines = if (lines[0].contains("Event Title", ignoreCase = true)) lines.drop(1) else lines

        val dateFormat = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US)
        val meetings = mutableListOf<ScheduledMeeting>()

        for (line in dataLines) {
            try {
                val fields = parseCsvLine(line)
                if (fields.size < 5) continue

                val title = fields[0]
                val startStr = fields[1]
                val endStr = fields[2]
                // fields[3] = Clash (ignored)
                val organizer = fields[4]

                val startDate = dateFormat.parse(startStr) ?: continue
                val endDate = dateFormat.parse(endStr) ?: continue
                val dateStr = TimeTrackingManager.epochToDateString(startDate.time)

                meetings.add(ScheduledMeeting(
                    id = generateId(),
                    title = title,
                    startTime = startDate.time,
                    endTime = endDate.time,
                    organizer = organizer,
                    date = dateStr
                ))
            } catch (e: Exception) {
                AppLogger.warn("MeetingScheduleManager", "Failed to parse line: $line — ${e.message}")
            }
        }

        return meetings
    }

    /**
     * Parse a single CSV line handling quoted fields with commas inside.
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        fields.add(current.toString().trim())
        return fields
    }

    // ─── CRUD ───────────────────────────────────────────────────────────────────

    fun addMeeting(title: String, startTime: Long, endTime: Long, organizer: String = "") {
        scope.launch {
            val date = TimeTrackingManager.epochToDateString(startTime)
            val meeting = ScheduledMeeting(
                id = generateId(),
                title = title,
                startTime = startTime,
                endTime = endTime,
                organizer = organizer,
                date = date
            )
            repository.saveMeeting(meeting)
            refreshToday()
        }
    }

    fun deleteMeeting(id: String) {
        scope.launch {
            repository.deleteMeeting(id)
            refreshToday()
        }
    }

    fun updateMeeting(meeting: ScheduledMeeting) {
        scope.launch {
            repository.updateMeeting(meeting)
            refreshToday()
        }
    }

    fun markAsRecorded(meetingId: String, recordingId: String) {
        scope.launch {
            val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
            val meetings = repository.getMeetingsForDate(today)
            val meeting = meetings.find { it.id == meetingId } ?: return@launch
            repository.updateMeeting(meeting.copy(isRecorded = true, linkedRecordingId = recordingId))
            refreshToday()
        }
    }

    /** Dismiss the current alert. */
    fun dismissAlert() {
        _pendingAlert.value = null
    }

    // ─── Alert Logic ────────────────────────────────────────────────────────────

    private suspend fun checkAlerts() {
        val now = System.currentTimeMillis()
        val fiveMinutes = 5 * 60 * 1000L
        val today = TimeTrackingManager.epochToDateString(now)
        val meetings = repository.getMeetingsForDate(today)

        for (meeting in meetings) {
            if (meeting.alertFired) continue
            val timeUntilStart = meeting.startTime - now
            // Fire alert when meeting is 0-5 minutes away
            if (timeUntilStart in 0..fiveMinutes) {
                _pendingAlert.value = meeting
                // Mark alert as fired so it doesn't fire again
                repository.updateMeeting(meeting.copy(alertFired = true))
                refreshToday()
                AppLogger.info("MeetingScheduleManager", "Alert: ${meeting.title} starts in ${timeUntilStart / 60000}m")
                break // Only one alert at a time
            }
        }
    }
}
