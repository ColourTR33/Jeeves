package com.jeeves.desktop.data

import com.jeeves.shared.domain.MeetingScheduleRepository
import com.jeeves.shared.domain.ScheduledMeeting
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based persistence for scheduled meetings.
 * Stored in ~/Jeeves/meetings/YYYY-MM-DD.json (one file per day).
 */
class FileMeetingScheduleRepository : MeetingScheduleRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val meetingsDir: File
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves/meetings")
            dir.mkdirs()
            return dir
        }

    private fun fileForDate(date: String) = File(meetingsDir, "$date.json")

    override suspend fun getMeetingsForDate(date: String): List<ScheduledMeeting> {
        val file = fileForDate(date)
        return try {
            if (file.exists()) {
                json.decodeFromString(ListSerializer(ScheduledMeeting.serializer()), file.readText())
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun saveMeeting(meeting: ScheduledMeeting) {
        val all = getMeetingsForDate(meeting.date).toMutableList()
        val i = all.indexOfFirst { it.id == meeting.id }
        if (i >= 0) all[i] = meeting else all.add(meeting)
        writeDate(meeting.date, all)
    }

    override suspend fun saveMeetings(meetings: List<ScheduledMeeting>) {
        // Group by date and write each day
        meetings.groupBy { it.date }.forEach { (date, dayMeetings) ->
            val existing = getMeetingsForDate(date).toMutableList()
            dayMeetings.forEach { meeting ->
                val i = existing.indexOfFirst { it.id == meeting.id }
                if (i >= 0) existing[i] = meeting else existing.add(meeting)
            }
            writeDate(date, existing)
        }
    }

    override suspend fun deleteMeeting(id: String) {
        // Search today and tomorrow
        val today = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        val tomorrow = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis() + 86_400_000)
        for (date in listOf(today, tomorrow)) {
            val all = getMeetingsForDate(date)
            if (all.any { it.id == id }) {
                writeDate(date, all.filter { it.id != id })
                return
            }
        }
    }

    override suspend fun updateMeeting(meeting: ScheduledMeeting) {
        saveMeeting(meeting)
    }

    private fun writeDate(date: String, meetings: List<ScheduledMeeting>) {
        fileForDate(date).writeText(
            json.encodeToString(ListSerializer(ScheduledMeeting.serializer()), meetings.sortedBy { it.startTime })
        )
    }
}
