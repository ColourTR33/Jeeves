package com.jeeves.shared.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application logger that writes to stdout/stderr AND keeps an in-memory
 * ring buffer of recent log entries for the UI log viewer.
 *
 * Log levels:
 *   - DEBUG: Only logged when verbose mode is enabled. Detailed internal state.
 *   - INFO:  Normal operational messages. Always logged.
 *   - WARN:  Potential issues that don't stop operation. Always logged.
 *   - ERROR: Failures. Always logged.
 *
 * When verbose mode is enabled:
 *   - DEBUG messages are included
 *   - All messages are also written to a rotating log file
 *   - Ring buffer is increased to 2000 entries
 *
 * Log entries are rotated on new day (cleared on day change).
 */
object AppLogger {
    private var enabled = true
    private var verbose = false
    private var fileWriter: LogFileWriter? = null

    private const val MAX_ENTRIES_NORMAL = 500
    private const val MAX_ENTRIES_VERBOSE = 2000

    private val maxEntries: Int get() = if (verbose) MAX_ENTRIES_VERBOSE else MAX_ENTRIES_NORMAL

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _isVerbose = MutableStateFlow(false)
    val isVerbose: StateFlow<Boolean> = _isVerbose.asStateFlow()

    /** Track which day the current log entries belong to (for daily rotation). */
    private var currentDay: Int = dayOfYear()

    private fun dayOfYear(): Int = ((System.currentTimeMillis() / 86_400_000) % 366).toInt()

    private fun rotateIfNewDay() {
        val today = dayOfYear()
        if (today != currentDay) {
            _entries.value = emptyList()
            currentDay = today
            fileWriter?.rotate()
        }
    }

    /**
     * Enable or disable verbose logging. Call this on startup after loading settings,
     * and whenever the user toggles the setting.
     */
    fun setVerbose(enabled: Boolean, logDir: String? = null) {
        verbose = enabled
        _isVerbose.value = enabled
        if (enabled && logDir != null) {
            fileWriter = LogFileWriter(logDir)
            info("AppLogger", "Verbose logging ENABLED — writing to $logDir")
        } else if (!enabled) {
            fileWriter?.close()
            fileWriter = null
        }
    }

    private fun addEntry(level: String, tag: String, message: String) {
        rotateIfNewDay()
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        _entries.value = (_entries.value + entry).takeLast(maxEntries)

        // Write to file if verbose
        fileWriter?.write(entry)
    }

    fun debug(tag: String, message: String) {
        if (enabled && verbose) {
            println("[DEBUG][$tag] $message")
            addEntry("DEBUG", tag, message)
        }
    }

    fun info(tag: String, message: String) {
        if (enabled) {
            println("[INFO][$tag] $message")
            addEntry("INFO", tag, message)
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            val fullMsg = if (throwable != null) {
                "$message — ${throwable::class.simpleName}: ${throwable.message}"
            } else message
            System.err.println("[ERROR][$tag] $fullMsg")
            addEntry("ERROR", tag, fullMsg)

            // In verbose mode, also log the stack trace
            if (verbose && throwable != null) {
                val stackTrace = throwable.stackTraceToString().take(2000)
                addEntry("DEBUG", tag, "Stack trace:\n$stackTrace")
            }
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

    /**
     * Export all current log entries as a single string (for clipboard/file export).
     */
    fun exportLogs(): String {
        return _entries.value.joinToString("\n") { entry ->
            val time = formatTimestamp(entry.timestamp)
            "[$time][${entry.level}][${entry.tag}] ${entry.message}"
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        val totalSeconds = epochMs / 1000
        val hours = ((totalSeconds % 86400) / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}

/**
 * Writes log entries to a file on disk. Rotates daily.
 * File name: jeeves-YYYY-MM-DD.log
 */
class LogFileWriter(private val logDir: String) {
    private var writer: java.io.BufferedWriter? = null
    private var currentFileName: String = ""

    init {
        ensureWriter()
    }

    private fun ensureWriter() {
        val today = todayString()
        val fileName = "jeeves-$today.log"
        if (fileName != currentFileName) {
            writer?.close()
            val dir = java.io.File(logDir)
            dir.mkdirs()
            val file = java.io.File(dir, fileName)
            writer = java.io.BufferedWriter(java.io.FileWriter(file, true))
            currentFileName = fileName

            // Clean up old log files (keep last 7 days)
            cleanOldLogs(dir)
        }
    }

    private fun todayString(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    fun write(entry: AppLogger.LogEntry) {
        try {
            ensureWriter()
            val time = formatTimestamp(entry.timestamp)
            writer?.write("[$time][${entry.level}][${entry.tag}] ${entry.message}")
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            // Don't recurse into logging — just print to stderr
            System.err.println("[LogFileWriter] Failed to write log: ${e.message}")
        }
    }

    fun rotate() {
        currentFileName = "" // Force new file on next write
    }

    fun close() {
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }

    private fun cleanOldLogs(dir: java.io.File) {
        try {
            val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            dir.listFiles()
                ?.filter { it.name.startsWith("jeeves-") && it.name.endsWith(".log") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun formatTimestamp(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return String.format(
            "%02d:%02d:%02d.%03d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND)
        )
    }
}
