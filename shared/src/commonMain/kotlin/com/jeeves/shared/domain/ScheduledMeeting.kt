package com.jeeves.shared.domain

import kotlinx.serialization.Serializable

/**
 * A meeting imported from a calendar CSV or manually added.
 * Shown on the Recording screen with countdown alerts and pre-filled recording fields.
 */
@Serializable
data class ScheduledMeeting(
    val id: String,
    val title: String,
    val startTime: Long,              // Epoch ms
    val endTime: Long,                // Epoch ms
    val organizer: String = "",
    val date: String,                 // YYYY-MM-DD (for grouping/lookup)
    val projectId: String = "",       // Optional project to auto-assign on record
    val alertFired: Boolean = false,  // Whether the 5-min alert has already fired
    val isRecorded: Boolean = false,  // Whether this meeting has been recorded
    val linkedRecordingId: String? = null  // Recording ID if captured
)

/**
 * Persistence for scheduled meetings.
 */
interface MeetingScheduleRepository {
    suspend fun getMeetingsForDate(date: String): List<ScheduledMeeting>
    suspend fun saveMeeting(meeting: ScheduledMeeting)
    suspend fun saveMeetings(meetings: List<ScheduledMeeting>)
    suspend fun deleteMeeting(id: String)
    suspend fun updateMeeting(meeting: ScheduledMeeting)
}
