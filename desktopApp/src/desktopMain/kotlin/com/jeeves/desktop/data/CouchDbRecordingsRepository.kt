package com.jeeves.desktop.data

import com.jeeves.shared.domain.*
import com.jeeves.shared.recording.currentTimeMillis
import com.jeeves.shared.sync.*

/**
 * RecordingsRepository implementation backed by a CouchDB-compatible LocalDocumentStore.
 *
 * Converts between domain models (Recording, TranscriptionResult, SummaryResult)
 * and CouchDB documents using the converters from DocumentConverters.kt.
 *
 * Document ID conventions:
 * - Recording:           "recording:{id}"
 * - TranscriptionResult: "transcription:{recordingId}"
 * - SummaryResult:       "summary:{recordingId}"
 */
class CouchDbRecordingsRepository(
    private val store: LocalDocumentStore,
    private val deviceId: String
) : RecordingsRepository {

    override suspend fun saveRecording(recording: Recording) {
        val doc = recording.toCouchDocument(deviceId, currentTimeMillis())
        store.put(doc)
    }

    override suspend fun getRecordings(): List<Recording> {
        return store.allDocs("recording:")
            .map { it.toRecording() }
    }

    override suspend fun getRecording(id: String): Recording? {
        val doc = store.get("recording:$id") ?: return null
        return doc.toRecording()
    }

    override suspend fun updateRecording(recording: Recording) {
        val existingDoc = store.get("recording:${recording.id}")
        val doc = recording.toCouchDocument(deviceId, currentTimeMillis())
        // Carry forward the existing rev for optimistic concurrency
        val docWithRev = if (existingDoc != null) {
            doc.copy(rev = existingDoc.rev)
        } else {
            doc
        }
        store.put(docWithRev)
    }

    override suspend fun updateRecordingNote(recordingId: String, note: String) {
        val existingDoc = store.get("recording:$recordingId") ?: return
        val recording = existingDoc.toRecording()
        val updated = recording.copy(postRecordingNote = note)
        val doc = updated.toCouchDocument(deviceId, currentTimeMillis())
        val docWithRev = doc.copy(rev = existingDoc.rev)
        store.put(docWithRev)
    }

    override suspend fun deleteRecording(id: String) {
        val existingDoc = store.get("recording:$id") ?: return
        val rev = existingDoc.rev ?: return
        store.delete("recording:$id", rev)
    }

    override suspend fun saveTranscription(result: TranscriptionResult) {
        val existingDoc = store.get("transcription:${result.recordingId}")
        val doc = result.toCouchDocument(deviceId, currentTimeMillis())
        val docWithRev = if (existingDoc != null) {
            doc.copy(rev = existingDoc.rev)
        } else {
            doc
        }
        store.put(docWithRev)
    }

    override suspend fun getTranscription(recordingId: String): TranscriptionResult? {
        val doc = store.get("transcription:$recordingId") ?: return null
        return doc.toTranscription()
    }

    override suspend fun saveSummary(result: SummaryResult) {
        val existingDoc = store.get("summary:${result.recordingId}")
        val doc = result.toCouchDocument(deviceId, currentTimeMillis())
        val docWithRev = if (existingDoc != null) {
            doc.copy(rev = existingDoc.rev)
        } else {
            doc
        }
        store.put(docWithRev)
    }

    override suspend fun getSummary(recordingId: String): SummaryResult? {
        val doc = store.get("summary:$recordingId") ?: return null
        return doc.toSummary()
    }
}
