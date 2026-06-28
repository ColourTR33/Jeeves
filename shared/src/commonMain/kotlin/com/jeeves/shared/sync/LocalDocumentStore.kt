package com.jeeves.shared.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A persistent log entry recording a document change with its sequence number.
 */
@Serializable
internal data class ChangeLogEntry(
    val seq: Long,
    val id: String,
    val rev: String,
    val deleted: Boolean = false
)

/**
 * A CouchDB-compatible local document store backed by JSON files in a directory structure.
 *
 * Documents are organized by type prefix (e.g., baseDir/recording/, baseDir/transcription/).
 * Each document is stored as an individual JSON file named by its _id (with `:` replaced by `_`).
 *
 * Atomic writes are guaranteed by writing to a `.tmp` file first, then renaming on success.
 * A SharedFlow emits DocumentChange events on every write (put/delete).
 *
 * The store maintains an in-memory sequence counter (also persisted to disk) that increments
 * on each write, used by the replication engine to track changes.
 *
 * A persistent change log records every write with its sequence number, enabling the
 * `changesSince` method to replay changes for replication.
 */
class LocalDocumentStore(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val _changes = MutableSharedFlow<DocumentChange>(replay = 0, extraBufferCapacity = 64)

    /**
     * Emits document change events on every write (put/delete).
     * Used by SyncEngine to detect local writes needing push.
     */
    val changes: SharedFlow<DocumentChange> = _changes.asSharedFlow()

    private val mutex = Mutex()

    /** In-memory sequence counter, persisted to baseDir/_sequence */
    private var sequenceCounter: Long = 0L

    /** Tracks the current generation per document ID for _rev generation */
    private val generationMap = mutableMapOf<String, Int>()

    /** In-memory change log loaded from disk, appended on each write */
    private val changeLog = mutableListOf<ChangeLogEntry>()

    init {
        baseDir.mkdirs()
        sequenceCounter = loadSequenceCounter()
        loadGenerationMap()
        loadChangeLog()
    }

    // --- Core Operations ---

    /**
     * Read a document by _id. Returns null if not found or if the document is marked deleted.
     */
    suspend fun get(id: String): CouchDocument? = mutex.withLock {
        val file = fileForId(id)
        if (!file.exists()) return@withLock null
        val doc = json.decodeFromString<CouchDocument>(file.readText())
        if (doc.deleted) return@withLock null
        doc
    }

    /**
     * Write a document. Generates a new _rev with an incrementing generation counter.
     * Uses atomic file writes (write to .tmp, rename on success).
     * Emits a DocumentChange event on success.
     *
     * If the document already exists and the provided _rev does not match the current _rev,
     * throws an IllegalStateException (optimistic concurrency check).
     */
    suspend fun put(doc: CouchDocument): CouchDocument = mutex.withLock {
        val file = fileForId(doc.id)
        val dirForType = file.parentFile
        dirForType.mkdirs()

        // Optimistic concurrency: check _rev if document already exists
        if (file.exists()) {
            val existing = json.decodeFromString<CouchDocument>(file.readText())
            if (doc.rev != null && doc.rev != existing.rev) {
                throw IllegalStateException(
                    "Document update conflict: expected rev '${existing.rev}' but got '${doc.rev}' for document '${doc.id}'"
                )
            }
        }

        // Increment generation for this document
        val currentGen = generationMap.getOrDefault(doc.id, 0)
        val newGen = currentGen + 1
        generationMap[doc.id] = newGen

        // Generate new _rev from generation + document content
        val contentForHash = json.encodeToString(doc.body)
        val newRev = generateRev(newGen, contentForHash)

        val updatedDoc = doc.copy(rev = newRev)

        // Atomic write: write to .tmp then rename
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        val serialized = json.encodeToString(updatedDoc)
        tmpFile.writeText(serialized)
        tmpFile.renameTo(file)

        // Increment and persist sequence counter
        sequenceCounter++
        persistSequenceCounter()

        // Append to change log
        appendChangeLogEntry(ChangeLogEntry(seq = sequenceCounter, id = updatedDoc.id, rev = newRev, deleted = false))

        // Emit change event
        _changes.emit(DocumentChange(id = updatedDoc.id, rev = newRev, deleted = false))

        updatedDoc
    }

    /**
     * Delete a document (tombstone). Writes a deleted marker document.
     * The provided rev must match the current rev (optimistic concurrency).
     * Emits a DocumentChange event on success.
     *
     * @throws IllegalStateException if the rev doesn't match or the document doesn't exist
     */
    suspend fun delete(id: String, rev: String): CouchDocument = mutex.withLock {
        val file = fileForId(id)
        if (!file.exists()) {
            throw IllegalStateException("Document not found: '$id'")
        }

        val existing = json.decodeFromString<CouchDocument>(file.readText())
        if (existing.rev != rev) {
            throw IllegalStateException(
                "Document update conflict: expected rev '${existing.rev}' but got '$rev' for document '$id'"
            )
        }

        // Increment generation
        val currentGen = generationMap.getOrDefault(id, 0)
        val newGen = currentGen + 1
        generationMap[id] = newGen

        // Generate new rev for the tombstone
        val newRev = generateRev(newGen, "")

        val tombstone = existing.copy(rev = newRev, deleted = true)

        // Atomic write
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        val serialized = json.encodeToString(tombstone)
        tmpFile.writeText(serialized)
        tmpFile.renameTo(file)

        // Increment and persist sequence counter
        sequenceCounter++
        persistSequenceCounter()

        // Append to change log
        appendChangeLogEntry(ChangeLogEntry(seq = sequenceCounter, id = id, rev = newRev, deleted = true))

        // Emit change event
        _changes.emit(DocumentChange(id = id, rev = newRev, deleted = true))

        tombstone
    }

    // --- Bulk and Query Operations ---

    /**
     * Bulk write for replication. Returns results per document.
     *
     * When `newEdits = true` (normal mode): treats each doc like a regular `put()`,
     * generating new revisions. No schema validation is performed since local writes
     * originate from trusted code.
     *
     * When `newEdits = false` (replication mode): accepts each document as-is with its
     * existing `_rev`, used when receiving replicated documents from a remote CouchDB.
     * Does not generate new revisions or check for conflicts.
     * Schema validation is applied: malformed documents are discarded with a logged
     * rejection and a failed BulkDocResult, but processing continues for remaining
     * documents without interrupting the replication session.
     *
     * Individual document failures do not abort the batch.
     *
     * **Validates: Requirements 7.5, 7.6**
     */
    suspend fun bulkDocs(docs: List<CouchDocument>, newEdits: Boolean = true): List<BulkDocResult> {
        return if (newEdits) {
            // Normal mode: each doc is treated like a put()
            docs.map { doc ->
                try {
                    val saved = put(doc)
                    BulkDocResult(id = saved.id, rev = saved.rev, ok = true)
                } catch (e: Exception) {
                    BulkDocResult(id = doc.id, ok = false, error = "conflict", reason = e.message)
                }
            }
        } else {
            // Replication mode: accept documents as-is with existing _rev
            mutex.withLock {
                docs.map { doc ->
                    // Schema validation for replicated documents (Requirement 7.5, 7.6)
                    val validationResult = DocumentValidator.validate(doc)
                    if (validationResult is DocumentValidator.ValidationResult.Invalid) {
                        // Discard malformed document, log rejection, continue processing
                        println("[VALIDATION REJECTED] Document '${doc.id}': ${validationResult.reason}")
                        return@map BulkDocResult(
                            id = doc.id,
                            ok = false,
                            error = "validation_failed",
                            reason = validationResult.reason
                        )
                    }

                    try {
                        val file = fileForId(doc.id)
                        val dirForType = file.parentFile
                        dirForType.mkdirs()

                        // Write the document as-is (preserving its _rev from the remote)
                        val tmpFile = File(file.parentFile, "${file.name}.tmp")
                        val serialized = json.encodeToString(doc)
                        tmpFile.writeText(serialized)
                        tmpFile.renameTo(file)

                        // Update generation map from the incoming rev
                        if (doc.rev != null) {
                            val generation = doc.rev.substringBefore("-").toIntOrNull() ?: 0
                            val currentGen = generationMap.getOrDefault(doc.id, 0)
                            if (generation > currentGen) {
                                generationMap[doc.id] = generation
                            }
                        }

                        // Increment and persist sequence counter
                        sequenceCounter++
                        persistSequenceCounter()

                        // Append to change log
                        val rev = doc.rev ?: ""
                        appendChangeLogEntry(ChangeLogEntry(seq = sequenceCounter, id = doc.id, rev = rev, deleted = doc.deleted))

                        // Emit change event
                        _changes.emit(DocumentChange(id = doc.id, rev = rev, deleted = doc.deleted))

                        BulkDocResult(id = doc.id, rev = doc.rev, ok = true)
                    } catch (e: Exception) {
                        BulkDocResult(id = doc.id, ok = false, error = "write_error", reason = e.message)
                    }
                }
            }
        }
    }

    /**
     * Get changes since a sequence number.
     *
     * Returns all document changes that occurred after the given sequence number.
     * The `since` parameter is a string representing a sequence number (parseable to Long).
     * A `since` of "0" returns all changes from the beginning.
     *
     * Results are limited to `limit` entries and include the `lastSeq` indicating
     * the sequence number of the last returned change.
     */
    suspend fun changesSince(since: String, limit: Int = 100): ChangesResponse = mutex.withLock {
        val sinceSeq = since.toLongOrNull() ?: 0L

        // Filter change log entries after the given sequence
        val filtered = changeLog
            .filter { it.seq > sinceSeq }
            .take(limit)

        val results = filtered.map { entry ->
            DocumentChange(id = entry.id, rev = entry.rev, deleted = entry.deleted)
        }

        val lastSeq = if (filtered.isNotEmpty()) {
            filtered.last().seq.toString()
        } else {
            since
        }

        ChangesResponse(results = results, lastSeq = lastSeq)
    }

    /**
     * Get all non-deleted documents whose `_id` starts with the given prefix.
     * Used for queries like `allDocs("recording:")` to get all recordings.
     */
    suspend fun allDocs(prefix: String): List<CouchDocument> = mutex.withLock {
        val typePrefix = prefix.trimEnd(':')
        val typeDir = File(baseDir, typePrefix)

        if (!typeDir.exists() || !typeDir.isDirectory) {
            return@withLock emptyList()
        }

        typeDir.listFiles()
            ?.filter { it.extension == "json" && !it.name.endsWith(".tmp") }
            ?.mapNotNull { file ->
                try {
                    val doc = json.decodeFromString<CouchDocument>(file.readText())
                    if (!doc.deleted && doc.id.startsWith(prefix)) doc else null
                } catch (_: Exception) {
                    null // Skip malformed files
                }
            }
            ?: emptyList()
    }

    /**
     * Check if data migration from SQLite has been completed.
     */
    suspend fun hasMigrationCompleted(): Boolean {
        val marker = File(baseDir, "_migration_complete")
        return marker.exists()
    }

    /**
     * Record that data migration has been completed.
     */
    suspend fun markMigrationComplete() {
        val marker = File(baseDir, "_migration_complete")
        marker.writeText("done")
    }

    // --- Internal helpers ---

    /**
     * Returns the current sequence counter value.
     * Used by the sync engine to track replication progress.
     */
    fun getSequenceCounter(): Long = sequenceCounter

    /**
     * Resolves the file path for a document ID.
     * Documents are organized by type prefix: baseDir/{type}/{sanitized_id}.json
     * The type prefix is extracted from the ID (e.g., "recording:abc" → type "recording").
     */
    private fun fileForId(id: String): File {
        val typePrefix = id.substringBefore(":")
        val sanitizedId = id.replace(":", "_")
        val typeDir = File(baseDir, typePrefix)
        return File(typeDir, "$sanitizedId.json")
    }

    /**
     * Loads the persisted sequence counter, defaulting to 0 if not found.
     */
    private fun loadSequenceCounter(): Long {
        val seqFile = File(baseDir, "_sequence")
        return if (seqFile.exists()) {
            seqFile.readText().trim().toLongOrNull() ?: 0L
        } else {
            0L
        }
    }

    /**
     * Persists the current sequence counter to disk.
     */
    private fun persistSequenceCounter() {
        val seqFile = File(baseDir, "_sequence")
        seqFile.writeText(sequenceCounter.toString())
    }

    /**
     * Loads the change log from disk into memory.
     */
    private fun loadChangeLog() {
        val logFile = File(baseDir, "_changelog.json")
        if (logFile.exists()) {
            try {
                val entries = json.decodeFromString<List<ChangeLogEntry>>(logFile.readText())
                changeLog.addAll(entries)
            } catch (_: Exception) {
                // If the file is corrupted, start fresh
            }
        }
    }

    /**
     * Appends a change log entry to the in-memory list and persists to disk.
     */
    private fun appendChangeLogEntry(entry: ChangeLogEntry) {
        changeLog.add(entry)
        val logFile = File(baseDir, "_changelog.json")
        logFile.writeText(json.encodeToString(changeLog.toList()))
    }

    /**
     * Loads the generation map from existing documents on disk.
     * Parses the generation number from each document's _rev field.
     */
    private fun loadGenerationMap() {
        if (!baseDir.exists()) return
        baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith("_") }?.forEach { typeDir ->
            typeDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                try {
                    val doc = json.decodeFromString<CouchDocument>(file.readText())
                    val rev = doc.rev
                    if (rev != null) {
                        val generation = rev.substringBefore("-").toIntOrNull() ?: 0
                        generationMap[doc.id] = generation
                    }
                } catch (_: Exception) {
                    // Skip malformed files
                }
            }
        }
    }
}
