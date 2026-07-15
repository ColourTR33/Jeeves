package com.jeeves.shared.domain

import kotlinx.serialization.Serializable

/**
 * A Mantra is a recurring timeboxed operating procedure — a sequence of
 * short, focused steps executed in order with a countdown timer.
 *
 * Designed for ADHD-friendly structure: each step has a fixed duration,
 * a clear action, and must be checked off before moving to the next.
 */
@Serializable
data class Mantra(
    val id: String,
    val name: String,                         // e.g. "Midday Comms Check"
    val description: String = "",             // Optional context
    val steps: List<MantraStep>,              // Ordered steps with durations
    val schedule: MantraSchedule,             // When this mantra recurs
    val projectId: String = "",               // Project to log time against (empty = don't log)
    val isActive: Boolean = true,             // Can be paused without deleting
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    /** Total duration of all steps in seconds. */
    val totalDurationSeconds: Int get() = steps.sumOf { it.durationSeconds }

    /** Total duration formatted as mm:ss or hh:mm:ss. */
    val totalDurationFormatted: String get() {
        val total = totalDurationSeconds
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}

/**
 * A single step within a Mantra. Each step has a fixed time box.
 */
@Serializable
data class MantraStep(
    val id: String,
    val phase: String,                        // e.g. "Recon", "Firefighting", "Quick Hits"
    val action: String,                       // What to do during this step
    val durationSeconds: Int                  // How long this step lasts (countdown)
)

/**
 * Schedule defining when a Mantra should run.
 */
@Serializable
data class MantraSchedule(
    val daysOfWeek: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    ),
    val startTimeHour: Int = 9,               // 24h format
    val startTimeMinute: Int = 0
) {
    val startTimeFormatted: String get() = String.format("%02d:%02d", startTimeHour, startTimeMinute)
}

@Serializable
enum class DayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY
}

/**
 * Represents a single execution (run) of a Mantra.
 * Tracks progress through the steps and timestamps for time logging.
 */
@Serializable
data class MantraExecution(
    val id: String,
    val mantraId: String,
    val date: String,                          // YYYY-MM-DD
    val startedAt: Long,                       // Epoch ms when execution began
    val completedAt: Long? = null,             // Epoch ms when all steps done (null = in progress)
    val currentStepIndex: Int = 0,             // Which step we're on (0-based)
    val completedSteps: List<String> = emptyList(),  // Step IDs that have been checked off
    val status: MantraExecutionStatus = MantraExecutionStatus.IN_PROGRESS,
    val elapsedSeconds: Int = 0                // Total seconds spent (for time logging)
)

@Serializable
enum class MantraExecutionStatus {
    /** Currently running — timer is active. */
    IN_PROGRESS,
    /** All steps completed successfully. */
    COMPLETED,
    /** User chose to skip/abandon this run. */
    SKIPPED
}
