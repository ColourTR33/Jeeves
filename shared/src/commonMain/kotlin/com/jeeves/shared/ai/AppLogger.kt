package com.jeeves.shared.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application logger that writes to stdout/stderr AND keeps an in-memory
 * ring buffer of recent log entries for the UI log viewer.
 */
object AppLogger {
    private var enabled = true
    private const val MAX_ENTRIES = 500

    data class LogEntry(val timestamp: Long, val level: String, val tag: String, val message: String)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private fun addEntry(level: String, tag: String, message: String) {
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
