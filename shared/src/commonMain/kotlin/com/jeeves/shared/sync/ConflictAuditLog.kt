package com.jeeves.shared.sync

import com.jeeves.shared.recording.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages a persistent audit log of conflict resolution events.
 *
 * Stores [ConflictAuditEntry] records in a JSON file (`_audit_log.json`) within the
 * local document store directory. Entries older than 90 days are pruned on initialization.
 *
 * Thread-safe: all operations are protected by a [Mutex].
 *
 * **Validates: Requirements 5.6, 5.7**
 */
class ConflictAuditLog(private val baseDir: File) {

    companion object {
        /** 90 days in milliseconds */
        internal const val RETENTION_PERIOD_MS: Long = 90L * 24 * 60 * 60 * 1000

        private const val AUDIT_LOG_FILENAME = "_audit_log.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val entries = mutableListOf<ConflictAuditEntry>()

    init {
        baseDir.mkdirs()
        loadEntries()
        pruneExpiredSync()
    }

    /**
     * Append a new audit entry to the log and persist to disk.
     */
    suspend fun append(entry: ConflictAuditEntry) = mutex.withLock {
        entries.add(entry)
        persistEntries()
    }

    /**
     * Retrieve all current audit log entries.
     */
    suspend fun getAll(): List<ConflictAuditEntry> = mutex.withLock {
        entries.toList()
    }

    /**
     * Remove entries where `resolvedAt` is older than 90 days from the current time.
     * Called on initialization (startup) and can be called manually.
     */
    suspend fun pruneExpired() = mutex.withLock {
        pruneExpiredInternal()
    }

    // --- Internal helpers ---

    /**
     * Synchronous version of pruneExpired for use during initialization.
     */
    private fun pruneExpiredSync() {
        pruneExpiredInternal()
    }

    private fun pruneExpiredInternal() {
        val cutoff = currentTimeMillis() - RETENTION_PERIOD_MS
        val sizeBefore = entries.size
        entries.removeAll { it.resolvedAt < cutoff }
        if (entries.size != sizeBefore) {
            persistEntriesSync()
        }
    }

    private fun loadEntries() {
        val file = File(baseDir, AUDIT_LOG_FILENAME)
        if (file.exists()) {
            try {
                val loaded = json.decodeFromString<List<ConflictAuditEntry>>(file.readText())
                entries.addAll(loaded)
            } catch (_: Exception) {
                // If the file is corrupted, start fresh
            }
        }
    }

    private fun persistEntries() {
        persistEntriesSync()
    }

    private fun persistEntriesSync() {
        val file = File(baseDir, AUDIT_LOG_FILENAME)
        val tmpFile = File(baseDir, "${AUDIT_LOG_FILENAME}.tmp")
        tmpFile.writeText(json.encodeToString(entries.toList()))
        tmpFile.renameTo(file)
    }
}
