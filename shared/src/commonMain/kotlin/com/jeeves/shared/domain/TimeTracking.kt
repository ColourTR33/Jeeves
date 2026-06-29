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
    val color: String = "#4A90D9",
    /** Default weekly target hours for this project (used when no specific plan is set). */
    val defaultTargetHours: Double = 0.0
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
    val targetHours: Double = 40.0
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
    val targetWeeklyHours: Double = 40.0
)

/**
 * Per-project target hours for a specific week.
 * The sum of all project targets for a week should equal [TimeReminderSettings.targetWeeklyHours] (40h).
 */
@Serializable
data class ProjectWeeklyTarget(
    val projectId: String,
    val targetHours: Double
)

/**
 * A weekly plan: the set of per-project target hours for a given week.
 * weekStartDate is always a Monday (ISO format YYYY-MM-DD).
 */
@Serializable
data class WeeklyPlan(
    val weekStartDate: String,
    val targets: List<ProjectWeeklyTarget>,
    val totalTargetHours: Double = 40.0
)

/**
 * Burndown data for a single project within a week.
 * Shows target vs actual for each day (Mon-Fri), plus remaining.
 */
data class ProjectBurndown(
    val project: Project,
    val targetHours: Double,
    val actualHours: Double,
    val remainingHours: Double,
    /** Cumulative actual hours per day: Map<date, cumulativeHours> */
    val dailyCumulative: Map<String, Double>,
    /** Ideal burndown line per day (evenly distributed target) */
    val dailyIdeal: Map<String, Double>
)

/**
 * Overall burndown for the week (all projects combined).
 */
data class WeeklyBurndown(
    val weekStartDate: String,
    val totalTarget: Double,
    val totalActual: Double,
    val totalRemaining: Double,
    val projectBurndowns: List<ProjectBurndown>,
    /** Cumulative total hours per day */
    val dailyCumulativeTotal: Map<String, Double>,
    /** Ideal total burndown per day */
    val dailyIdealTotal: Map<String, Double>
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

    // Weekly planning
    suspend fun getWeeklyPlan(weekStartDate: String): WeeklyPlan?
    suspend fun saveWeeklyPlan(plan: WeeklyPlan)
    suspend fun deleteWeeklyPlan(weekStartDate: String)
    /** Get all weekly plans (for forward planning view). */
    suspend fun getAllWeeklyPlans(): List<WeeklyPlan>

    // Backlog & Sprint planning
    suspend fun getBacklogItems(projectId: String): List<BacklogItem>
    suspend fun saveBacklogItems(projectId: String, items: List<BacklogItem>)
    suspend fun getSprintItems(weekStartDate: String): List<SprintItem>
    suspend fun saveSprintItems(weekStartDate: String, items: List<SprintItem>)
}

// ─── Backlog & Sprint Models ────────────────────────────────────────────────────

/**
 * Status of a backlog item through its lifecycle.
 */
@Serializable
enum class BacklogStatus {
    /** In the backlog, not yet planned for any week */
    BACKLOG,
    /** Accepted into a weekly sprint (allocated time) */
    PLANNED,
    /** Currently being worked on (countdown running) */
    IN_PROGRESS,
    /** Completed */
    DONE
}

/**
 * A work item in a project's backlog.
 * Items are ordered by priority (lower number = higher priority).
 */
@Serializable
data class BacklogItem(
    val id: String,
    val projectId: String,
    val title: String,
    val description: String = "",
    val priority: Int = 100,  // lower = higher priority
    val estimateMinutes: Int = 60,  // estimated time to complete
    val status: BacklogStatus = BacklogStatus.BACKLOG,
    val createdAt: Long = 0,
    val completedAt: Long? = null
)

/**
 * A backlog item that has been accepted into a weekly sprint.
 * Tracks allocated time budget and elapsed time against it.
 */
@Serializable
data class SprintItem(
    val id: String,
    val backlogItemId: String,
    val projectId: String,
    val weekStartDate: String,
    val title: String,
    val allocatedMinutes: Int,  // time budget for this week (from estimate or manual override)
    val elapsedMinutes: Int = 0,  // time spent so far
    val isActive: Boolean = false,  // currently being worked on (countdown running)
    val activeStartTime: Long? = null  // epoch ms when countdown started (null = paused/stopped)
) {
    /** Minutes remaining on the budget. Can go negative (overrun). */
    val remainingMinutes: Int get() = allocatedMinutes - elapsedMinutes

    /** Effective remaining accounting for currently-running time. */
    fun effectiveRemainingMinutes(nowMs: Long): Int {
        val runningExtra = if (isActive && activeStartTime != null) {
            ((nowMs - activeStartTime) / 60_000).toInt()
        } else 0
        return allocatedMinutes - elapsedMinutes - runningExtra
    }

    /** Whether this item is over budget. */
    fun isOverrun(nowMs: Long): Boolean = effectiveRemainingMinutes(nowMs) < 0
}
