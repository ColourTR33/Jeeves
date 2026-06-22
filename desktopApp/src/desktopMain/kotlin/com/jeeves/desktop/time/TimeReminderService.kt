package com.jeeves.desktop.time

import com.jeeves.shared.time.TimeTrackingManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

data class ReminderNotification(val type: ReminderType, val title: String, val message: String)
enum class ReminderType { IDLE, LONG_RUNNING }

class TimeReminderService(
    private val timeManager: TimeTrackingManager,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private val _pendingReminder = MutableStateFlow<ReminderNotification?>(null)
    val pendingReminder: StateFlow<ReminderNotification?> = _pendingReminder.asStateFlow()

    private var lastIdleNotify = 0L
    private var lastLongRunningNotify = 0L

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(60_000)
                check()
            }
        }
    }

    fun stop() { job?.cancel() }
    fun dismiss() { _pendingReminder.value = null }

    private fun check() {
        val settings = timeManager.reminderSettings.value
        if (!settings.enabled) return

        val now = LocalTime.now()
        val inQuiet = if (settings.quietHoursStart > settings.quietHoursEnd)
            now.hour >= settings.quietHoursStart || now.hour < settings.quietHoursEnd
        else
            now.hour >= settings.quietHoursStart && now.hour < settings.quietHoursEnd
        if (inQuiet) return

        val currentMs = System.currentTimeMillis()
        val entry = timeManager.currentEntry.value

        if (entry == null || !entry.isRunning) {
            // Idle check
            val lastEntry = timeManager.todayEntries.value.filter { !it.isRunning }.maxByOrNull { it.endTime ?: 0 }
            val idleSince = lastEntry?.endTime ?: (currentMs - 3600_000)
            val idleMs = currentMs - idleSince
            val threshold = settings.idleReminderMinutes * 60_000L
            if (idleMs >= threshold && currentMs - lastIdleNotify > threshold) {
                lastIdleNotify = currentMs
                _pendingReminder.value = ReminderNotification(
                    ReminderType.IDLE, "Time not logged",
                    "You haven't logged time for ${idleMs / 60_000} minutes. What were you working on?"
                )
            }
        } else {
            // Long-running check
            val runningMs = currentMs - entry.startTime
            val threshold = settings.longRunningReminderMinutes * 60_000L
            if (runningMs >= threshold && currentMs - lastLongRunningNotify > threshold) {
                lastLongRunningNotify = currentMs
                val desc = entry.taskDescription.ifEmpty { "current task" }
                _pendingReminder.value = ReminderNotification(
                    ReminderType.LONG_RUNNING, "Timer still running",
                    "Timer for '$desc' has been running for ${runningMs / 60_000} minutes. Still working on this?"
                )
            }
        }
    }
}
