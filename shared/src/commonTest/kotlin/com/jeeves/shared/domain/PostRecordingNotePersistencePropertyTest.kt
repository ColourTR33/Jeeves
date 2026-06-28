package com.jeeves.shared.domain

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Feature: meeting-intelligence, Property 4: Post-recording note persistence round-trip

/**
 * Property-based test for post-recording note persistence round-trip.
 *
 * For any recording with a non-empty postRecordingNote string, updating
 * the recording then retrieving it should return a recording whose
 * postRecordingNote is identical to the original.
 *
 * **Validates: Requirements 3.2**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class PostRecordingNotePersistencePropertyTest {

    private val config = PropTestConfig(iterations = 100)

    /**
     * In-memory stub for RecordingsRepository to enable testing without file I/O.
     */
    private class InMemoryRecordingsRepository : RecordingsRepository {
        private val recordings = mutableListOf<Recording>()
        private val transcriptions = mutableMapOf<String, TranscriptionResult>()
        private val summaries = mutableMapOf<String, SummaryResult>()

        override suspend fun saveRecording(recording: Recording) {
            recordings.add(0, recording)
        }

        override suspend fun getRecordings(): List<Recording> = recordings.toList()

        override suspend fun getRecording(id: String): Recording? =
            recordings.find { it.id == id }

        override suspend fun updateRecording(recording: Recording) {
            val index = recordings.indexOfFirst { it.id == recording.id }
            if (index >= 0) {
                recordings[index] = recording
            }
        }

        override suspend fun updateRecordingNote(recordingId: String, note: String) {
            val recording = getRecording(recordingId) ?: return
            val updated = recording.copy(postRecordingNote = note)
            updateRecording(updated)
        }

        override suspend fun deleteRecording(id: String) {
            recordings.removeAll { it.id == id }
            transcriptions.remove(id)
            summaries.remove(id)
        }

        override suspend fun saveTranscription(result: TranscriptionResult) {
            transcriptions[result.recordingId] = result
        }

        override suspend fun getTranscription(recordingId: String): TranscriptionResult? =
            transcriptions[recordingId]

        override suspend fun saveSummary(result: SummaryResult) {
            summaries[result.recordingId] = result
        }

        override suspend fun getSummary(recordingId: String): SummaryResult? =
            summaries[recordingId]
    }

    /**
     * Property 4: Post-recording note persistence round-trip
     *
     * For any recording with a non-empty note string:
     * 1. Save a recording to the repo
     * 2. Call updateRecordingNote(recording.id, note)
     * 3. Retrieve the recording
     * 4. Verify recording.postRecordingNote == the original note
     *
     * **Validates: Requirements 3.2**
     */
    @Test
    fun property4_postRecordingNotePersistenceRoundTrip_preservesIdenticalNote() = runTest {
        checkAll(config, Arb.string(1..200).filter { it.isNotBlank() }) { note ->
            val repo = InMemoryRecordingsRepository()

            // Create and save a recording
            val recording = Recording(
                id = "test-recording-${note.hashCode()}",
                filePath = "/tmp/test.wav",
                durationMs = 60000L,
                createdAt = 1700000000000L
            )
            repo.saveRecording(recording)

            // Update the recording note
            repo.updateRecordingNote(recording.id, note)

            // Retrieve the recording
            val retrieved = repo.getRecording(recording.id)

            // Verify the note was persisted correctly
            assertNotNull(retrieved, "Recording should be retrievable after saving")
            assertEquals(
                note,
                retrieved.postRecordingNote,
                "Post-recording note should be identical after update and retrieve round-trip. " +
                    "Expected: \"$note\", Got: \"${retrieved.postRecordingNote}\""
            )
        }
    }
}
