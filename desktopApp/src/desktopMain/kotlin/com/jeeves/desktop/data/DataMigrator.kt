package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.RecordingsRepository
import com.jeeves.shared.recording.currentTimeMillis
import com.jeeves.shared.sync.CouchDocument
import com.jeeves.shared.sync.LocalDocumentStore
import com.jeeves.shared.sync.toCouchDocument

/**
 * Migrates existing data from the SQLite-backed RecordingsRepository into the
 * CouchDB-compatible LocalDocumentStore.
 *
 * Migration runs only once: a marker in the LocalDocumentStore prevents re-execution
 * on subsequent application starts. Individual document failures are logged and skipped
 * without aborting the overall migration.
 *
 * **Validates: Requirements 2.3, 2.5, 2.7**
 */
class DataMigrator(
    private val sqliteRepository: RecordingsRepository,
    private val localStore: LocalDocumentStore,
    private val deviceId: String
) {

    companion object {
        private const val TAG = "DataMigrator"
    }

    /**
     * Result of a migration attempt.
     *
     * @param migrated Number of documents successfully written to the local store.
     * @param failed Number of documents that could not be migrated.
     * @param skipped Number of documents skipped (migration already complete).
     */
    data class MigrationResult(
        val migrated: Int,
        val failed: Int,
        val skipped: Int
    )

    /**
     * Run migration if it has not already been completed.
     *
     * Checks the migration-complete marker first. If already done, returns immediately
     * with all counts at zero (skipped = total documents that would have been migrated).
     *
     * Otherwise reads all recordings, transcriptions, and summaries from SQLite,
     * converts them to CouchDB documents, writes them via bulkDocs, then marks
     * migration as complete.
     */
    suspend fun migrateIfNeeded(): MigrationResult {
        if (localStore.hasMigrationCompleted()) {
            AppLogger.info(TAG, "Migration already completed, skipping")
            return MigrationResult(migrated = 0, failed = 0, skipped = 0)
        }

        AppLogger.info(TAG, "Starting data migration from SQLite to LocalDocumentStore")

        val documents = mutableListOf<CouchDocument>()
        var failed = 0
        val modifiedAt = currentTimeMillis()

        // Collect all recordings and their associated transcriptions/summaries
        val recordings = try {
            sqliteRepository.getRecordings()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to read recordings from SQLite: ${e.message}", e)
            return MigrationResult(migrated = 0, failed = 0, skipped = 0)
        }

        for (recording in recordings) {
            // Convert recording
            try {
                documents.add(recording.toCouchDocument(deviceId, modifiedAt))
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to convert recording '${recording.id}': ${e.message}", e)
                failed++
            }

            // Convert transcription if available
            try {
                val transcription = sqliteRepository.getTranscription(recording.id)
                if (transcription != null) {
                    documents.add(transcription.toCouchDocument(deviceId, modifiedAt))
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to convert transcription for recording '${recording.id}': ${e.message}", e)
                failed++
            }

            // Convert summary if available
            try {
                val summary = sqliteRepository.getSummary(recording.id)
                if (summary != null) {
                    documents.add(summary.toCouchDocument(deviceId, modifiedAt))
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to convert summary for recording '${recording.id}': ${e.message}", e)
                failed++
            }
        }

        // Write all documents via bulkDocs
        var migrated = 0
        if (documents.isNotEmpty()) {
            val results = localStore.bulkDocs(documents, newEdits = true)
            for (result in results) {
                if (result.ok) {
                    migrated++
                } else {
                    failed++
                    AppLogger.error(TAG, "Failed to write document '${result.id}': ${result.error} - ${result.reason}")
                }
            }
        }

        // Mark migration as complete regardless of individual failures
        localStore.markMigrationComplete()

        AppLogger.info(TAG, "Migration complete: $migrated migrated, $failed failed, ${recordings.size} recordings processed")

        return MigrationResult(migrated = migrated, failed = failed, skipped = 0)
    }
}
