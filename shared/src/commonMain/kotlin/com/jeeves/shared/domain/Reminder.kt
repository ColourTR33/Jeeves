package com.jeeves.shared.domain

import kotlinx.serialization.Serializable

/**
 * Urgency levels for reminders. Affects sort order and visual treatment.
 */
@Serializable
enum class ReminderUrgency {
    LOW,        // Nice to do, no pressure
    MEDIUM,     // Should be done soon
    HIGH,       // Needs attention today
    CRITICAL    // Drop everything — do this now
}

/**
 * Status of a reminder through its lifecycle.
 */
@Serializable
enum class ReminderStatus {
    /** Active — needs to be done. */
    OPEN,
    /** Completed — checked off. */
    DONE,
    /** Converted to a backlog item (more than 10 mins work). */
    CONVERTED_TO_BACKLOG
}

/**
 * An ad-hoc reminder/task. Lightweight — for things that should take < 10 minutes.
 * If it turns out to be bigger, convert it to a BacklogItem under the appropriate project.
 */
@Serializable
data class Reminder(
    val id: String,
    val projectId: String,             // Which project this relates to
    val task: String,                  // What needs to be done
    val assignedTo: String = "",       // Who is it for (name)
    val dueAt: Long = 0,              // Epoch ms — when it needs to be done by
    val urgency: ReminderUrgency = ReminderUrgency.MEDIUM,
    val status: ReminderStatus = ReminderStatus.OPEN,
    val createdAt: Long = 0,
    val completedAt: Long? = null,
    val convertedBacklogItemId: String? = null  // If converted, the backlog item ID
)
