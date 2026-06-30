package com.jeeves.shared.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application logger that writes to stdout/stderr AND keeps an in-memory
 * ring buffer of recent log entries for the UI log viewer.
 * Log entries are rotated every 24 hours (cleared on new day).
 */
object AppLogger {
    private var enabled = true
    private const val MAX_ENTRIES = 500

    data class LogEntry(val timestamp: Long, val level: String, val tag: String, val message: String)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Track which day the current log entries belong to (for 24h rotation). */
    private var currentDay: Int = dayOfYear()

    private fun dayOfYear(): Int = ((System.currentTimeMillis() / 86_400_000) % 366).toInt()

    private fun rotateIfNewDay() {
        val today = dayOfYear()
        if (today != currentDay) {
            _entries.value = emptyList()
            currentDay = today
        }
    }

    private fun addEntry(level: String, tag: String, message: String) {
        rotateIfNewDay()
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun info(tag: String, message: String) {
        if (enabled) {
            println("[INFO][$tag] $message")
            addEntry("INFO", tag, message)
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            val fullMsg = if (throwable != null) "$message — ${throwable::class.simpleName}: ${throwable.message}" else message
            System.err.println("[ERROR][$tag] $fullMsg")
            addEntry("ERROR", tag, fullMsg)
        }
    }

    fun warn(tag: String, message: String) {
        if (enabled) {
            println("[WARN][$tag] $message")
            addEntry("WARN", tag, message)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
