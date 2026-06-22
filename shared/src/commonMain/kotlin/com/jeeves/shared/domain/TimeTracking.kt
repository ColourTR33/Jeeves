package com.jeeves.shared.domain

import kotlinx.serialization.Serializable

@Serializable
data class Client(
    val id: String,
    val name: String
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val clientId: String? = null,
    val isBillable: Boolean = true,
    val isDistributed: Boolean = false,
    val color: String = "#4A90D9"
)

@Serializable
data class TimeEntry(
    val id: String,
    val projectId: String,
    val taskDescription: String = "",
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long? = null,
    val date: String,
    val isRunning: Boolean = false,
    val linkedRecordingId: String? = null
) {
    fun effectiveDurationMs(nowMs: Long): Long {
        return if (isRunning) nowMs - startTime
        else durationMs ?: ((endTime ?: startTime) - startTime)
    }

    fun effectiveHours(nowMs: Long): Double = effectiveDurationMs(nowMs) / 3_600_000.0
}

data class TimesheetRow(
    val project: Project,
    val hoursPerDay: Map<String, Double>,
    val totalHours: Double
)

data class WeeklyTimesheet(
    val weekStartDate: String,
    val weekEndDate: String,
    val rows: List<TimesheetRow>,
    val dailyTotals: Map<String, Double>,
    val grandTotal: Double,
    val targetHours: Double = 37.5
)

data class DistributionPreview(
    val distributedProjectName: String,
    val totalDistributedHours: Double,
    val allocations: List<DistributionAllocation>
)

data class DistributionAllocation(
    val project: Project,
    val originalHours: Double,
    val additionalHours: Double,
    val newTotalHours: Double
)

@Serializable
data class TimeReminderSettings(
    val idleReminderMinutes: Int = 60,
    val longRunningReminderMinutes: Int = 60,
    val quietHoursStart: Int = 18,
    val quietHoursEnd: Int = 8,
    val enabled: Boolean = true,
    val targetWeeklyHours: Double = 37.5
)

interface TimeTrackingRepository {
    suspend fun getProjects(): List<Project>
    suspend fun saveProject(project: Project)
    suspend fun deleteProject(id: String)
    suspend fun getClients(): List<Client>
    suspend fun saveClient(client: Client)
    suspend fun getTimeEntries(startDate: String, endDate: String): List<TimeEntry>
    suspend fun getTimeEntriesForDate(date: String): List<TimeEntry>
    suspend fun getRunningEntry(): TimeEntry?
    suspend fun saveTimeEntry(entry: TimeEntry)
    suspend fun updateTimeEntry(entry: TimeEntry)
    suspend fun deleteTimeEntry(id: String)
    suspend fun getReminderSettings(): TimeReminderSettings
    suspend fun saveReminderSettings(settings: TimeReminderSettings)
}
