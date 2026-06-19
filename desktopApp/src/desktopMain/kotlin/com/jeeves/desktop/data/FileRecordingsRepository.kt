package com.jeeves.desktop.data

import com.jeeves.shared.domain.*
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based recordings persistence for desktop.
 * Stores recording metadata, transcriptions, and summaries as JSON files.
 */
class FileRecordingsRepository : RecordingsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val dataDir: File
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves/data")
            dir.mkdirs()
            return dir
        }

    private val recordingsFile: File
        get() = File(dataDir, "recordings.json")

    override suspend fun saveRecording(recording: Recording) {
        val recordings = getRecordings().toMutableList()
        recordings.add(0, recording) // Add to front (most recent first)
        recordingsFile.writeText(json.encodeToString(RecordingsList.serializer(), RecordingsList(recordings)))
    }

    override suspend fun getRecordings(): List<Recording> {
        return try {
            if (recordingsFile.exists()) {
                json.decodeFromString<RecordingsList>(recordingsFile.readText()).recordings
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getRecording(id: String): Recording? {
        return getRecordings().find { it.id == id }
    }

    override suspend fun updateRecording(recording: Recording) {
        val recordings = getRecordings().map {
            if (it.id == recording.id) recording else it
        }
        recordingsFile.writeText(json.encodeToString(RecordingsList.serializer(), RecordingsList(recordings)))
    }

    override suspend fun deleteRecording(id: String) {
        val recordings = getRecordings().filter { it.id != id }
        recordingsFile.writeText(json.encodeToString(RecordingsList.serializer(), RecordingsList(recordings)))
        // Clean up associated files
        File(dataDir, "transcription_$id.json").delete()
        File(dataDir, "summary_$id.json").delete()
    }

    override suspend fun saveTranscription(result: TranscriptionResult) {
        val file = File(dataDir, "transcription_${result.recordingId}.json")
        file.writeText(json.encodeToString(TranscriptionResult.serializer(), result))
    }

    override suspend fun getTranscription(recordingId: String): TranscriptionResult? {
        return try {
            val file = File(dataDir, "transcription_$recordingId.json")
            if (file.exists()) {
                json.decodeFromString<TranscriptionResult>(file.readText())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveSummary(result: SummaryResult) {
        val file = File(dataDir, "summary_${result.recordingId}.json")
        file.writeText(json.encodeToString(SummaryResult.serializer(), result))
    }

    override suspend fun getSummary(recordingId: String): SummaryResult? {
        return try {
            val file = File(dataDir, "summary_$recordingId.json")
            if (file.exists()) {
                json.decodeFromString<SummaryResult>(file.readText())
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

@kotlinx.serialization.Serializable
data class RecordingsList(val recordings: List<Recording>)
