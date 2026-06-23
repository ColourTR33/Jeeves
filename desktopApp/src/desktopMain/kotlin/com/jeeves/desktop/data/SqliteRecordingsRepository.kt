package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-based recordings persistence for desktop.
 * Replaces FileRecordingsRepository for better performance at scale.
 * Stores recordings, transcriptions, and summaries in a single DB file.
 */
class SqliteRecordingsRepository : RecordingsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val dbPath: String
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves/data")
            dir.mkdirs()
            return File(dir, "jeeves.db").absolutePath
        }

    private val connection: Connection by lazy {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
            conn.createStatement().execute("PRAGMA journal_mode=WAL")
            conn.createStatement().execute("PRAGMA foreign_keys=ON")
            createTables(conn)
            AppLogger.info("SqliteRecordingsRepository", "Database opened: $dbPath")
        }
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS recordings (
                id TEXT PRIMARY KEY,
                file_path TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT 'Untitled Meeting',
                description TEXT NOT NULL DEFAULT '',
                template TEXT NOT NULL DEFAULT 'GENERAL',
                tags TEXT NOT NULL DEFAULT '[]',
                folder TEXT NOT NULL DEFAULT '',
                highlights TEXT NOT NULL DEFAULT '[]',
                attachments TEXT NOT NULL DEFAULT '[]'
            )
        """)

        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS transcriptions (
                recording_id TEXT PRIMARY KEY,
                text TEXT NOT NULL,
                segments TEXT NOT NULL DEFAULT '[]',
                language TEXT NOT NULL DEFAULT 'en',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                diarization_unavailable INTEGER NOT NULL DEFAULT 0
            )
        """)

        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS summaries (
                recording_id TEXT PRIMARY KEY,
                summary TEXT NOT NULL,
                key_points TEXT NOT NULL DEFAULT '[]',
                action_items TEXT NOT NULL DEFAULT '[]',
                questions TEXT NOT NULL DEFAULT '[]',
                tags TEXT NOT NULL DEFAULT '[]',
                model_used TEXT NOT NULL DEFAULT ''
            )
        """)

        // Index for fast date-based queries
        conn.createStatement().executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_recordings_created_at ON recordings(created_at DESC)
        """)
    }

    // --- Recordings ---

    override suspend fun saveRecording(recording: Recording) {
        val stmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO recordings (id, file_path, duration_ms, created_at, title, description, template, tags, folder, highlights, attachments)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, recording.id)
        stmt.setString(2, recording.filePath)
        stmt.setLong(3, recording.durationMs)
        stmt.setLong(4, recording.createdAt)
        stmt.setString(5, recording.title)
        stmt.setString(6, recording.description)
        stmt.setString(7, recording.template.name)
        stmt.setString(8, json.encodeToString(ListSerializer(String.serializer()), recording.tags))
        stmt.setString(9, recording.folder)
        stmt.setString(10, json.encodeToString(ListSerializer(Long.serializer()), recording.highlights))
        stmt.setString(11, json.encodeToString(ListSerializer(Attachment.serializer()), recording.attachments))
        stmt.executeUpdate()
        stmt.close()
    }

    override suspend fun getRecordings(): List<Recording> {
        val stmt = connection.createStatement()
        val rs = stmt.executeQuery("SELECT * FROM recordings ORDER BY created_at DESC")
        val recordings = mutableListOf<Recording>()
        while (rs.next()) {
            recordings.add(rowToRecording(rs))
        }
        rs.close()
        stmt.close()
        return recordings
    }

    override suspend fun getRecording(id: String): Recording? {
        val stmt = connection.prepareStatement("SELECT * FROM recordings WHERE id = ?")
        stmt.setString(1, id)
        val rs = stmt.executeQuery()
        val recording = if (rs.next()) rowToRecording(rs) else null
        rs.close()
        stmt.close()
        return recording
    }

    override suspend fun updateRecording(recording: Recording) {
        saveRecording(recording) // INSERT OR REPLACE handles update
    }

    override suspend fun deleteRecording(id: String) {
        connection.prepareStatement("DELETE FROM recordings WHERE id = ?").apply {
            setString(1, id)
            executeUpdate()
            close()
        }
        connection.prepareStatement("DELETE FROM transcriptions WHERE recording_id = ?").apply {
            setString(1, id)
            executeUpdate()
            close()
        }
        connection.prepareStatement("DELETE FROM summaries WHERE recording_id = ?").apply {
            setString(1, id)
            executeUpdate()
            close()
        }
    }

    // --- Transcriptions ---

    override suspend fun saveTranscription(result: TranscriptionResult) {
        val stmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO transcriptions (recording_id, text, segments, language, duration_ms, diarization_unavailable)
            VALUES (?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, result.recordingId)
        stmt.setString(2, result.text)
        stmt.setString(3, json.encodeToString(ListSerializer(TranscriptionSegment.serializer()), result.segments))
        stmt.setString(4, result.language)
        stmt.setLong(5, result.durationMs)
        stmt.setInt(6, if (result.diarizationUnavailable) 1 else 0)
        stmt.executeUpdate()
        stmt.close()
    }

    override suspend fun getTranscription(recordingId: String): TranscriptionResult? {
        val stmt = connection.prepareStatement("SELECT * FROM transcriptions WHERE recording_id = ?")
        stmt.setString(1, recordingId)
        val rs = stmt.executeQuery()
        val result = if (rs.next()) {
            TranscriptionResult(
                recordingId = rs.getString("recording_id"),
                text = rs.getString("text"),
                segments = json.decodeFromString(ListSerializer(TranscriptionSegment.serializer()), rs.getString("segments")),
                language = rs.getString("language"),
                durationMs = rs.getLong("duration_ms"),
                diarizationUnavailable = rs.getInt("diarization_unavailable") == 1
            )
        } else null
        rs.close()
        stmt.close()
        return result
    }

    // --- Summaries ---

    override suspend fun saveSummary(result: SummaryResult) {
        val stmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO summaries (recording_id, summary, key_points, action_items, questions, tags, model_used)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, result.recordingId)
        stmt.setString(2, result.summary)
        stmt.setString(3, json.encodeToString(ListSerializer(String.serializer()), result.keyPoints))
        stmt.setString(4, json.encodeToString(ListSerializer(String.serializer()), result.actionItems))
        stmt.setString(5, json.encodeToString(ListSerializer(String.serializer()), result.questions))
        stmt.setString(6, json.encodeToString(ListSerializer(String.serializer()), result.tags))
        stmt.setString(7, result.modelUsed)
        stmt.executeUpdate()
        stmt.close()
    }

    override suspend fun getSummary(recordingId: String): SummaryResult? {
        val stmt = connection.prepareStatement("SELECT * FROM summaries WHERE recording_id = ?")
        stmt.setString(1, recordingId)
        val rs = stmt.executeQuery()
        val result = if (rs.next()) {
            SummaryResult(
                recordingId = rs.getString("recording_id"),
                summary = rs.getString("summary"),
                keyPoints = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("key_points")),
                actionItems = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("action_items")),
                questions = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("questions")),
                tags = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("tags")),
                modelUsed = rs.getString("model_used")
            )
        } else null
        rs.close()
        stmt.close()
        return result
    }

    // --- Migration from JSON ---

    /**
     * Import existing data from FileRecordingsRepository JSON files.
     * Called once on first run with SQLite. Skips if recordings already exist in DB.
     */
    fun migrateFromJson() {
        val dataDir = File(System.getProperty("user.home"), "Jeeves/data")
        val recordingsFile = File(dataDir, "recordings.json")

        if (!recordingsFile.exists()) return

        // Check if DB already has data (don't re-import)
        val countRs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM recordings")
        countRs.next()
        val existingCount = countRs.getInt(1)
        countRs.close()
        if (existingCount > 0) return

        AppLogger.info("SqliteRecordingsRepository", "Migrating from JSON files...")

        try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val recordingsList = jsonParser.decodeFromString<RecordingsList>(recordingsFile.readText())

            var imported = 0
            for (recording in recordingsList.recordings) {
                // Import recording
                kotlinx.coroutines.runBlocking { saveRecording(recording) }

                // Import transcription if exists
                val transFile = File(dataDir, "transcription_${recording.id}.json")
                if (transFile.exists()) {
                    try {
                        val trans = jsonParser.decodeFromString<TranscriptionResult>(transFile.readText())
                        kotlinx.coroutines.runBlocking { saveTranscription(trans) }
                    } catch (e: Exception) {
                        AppLogger.warn("SqliteRecordingsRepository", "Failed to import transcription for ${recording.id}: ${e.message}")
                    }
                }

                // Import summary if exists
                val summaryFile = File(dataDir, "summary_${recording.id}.json")
                if (summaryFile.exists()) {
                    try {
                        val summary = jsonParser.decodeFromString<SummaryResult>(summaryFile.readText())
                        kotlinx.coroutines.runBlocking { saveSummary(summary) }
                    } catch (e: Exception) {
                        AppLogger.warn("SqliteRecordingsRepository", "Failed to import summary for ${recording.id}: ${e.message}")
                    }
                }

                imported++
            }

            AppLogger.info("SqliteRecordingsRepository", "Migration complete: $imported recordings imported")

            // Rename old JSON file to indicate migration is done
            recordingsFile.renameTo(File(dataDir, "recordings.json.migrated"))
        } catch (e: Exception) {
            AppLogger.error("SqliteRecordingsRepository", "Migration failed: ${e.message}", e)
        }
    }

    // --- Helpers ---

    private fun rowToRecording(rs: java.sql.ResultSet): Recording {
        return Recording(
            id = rs.getString("id"),
            filePath = rs.getString("file_path"),
            durationMs = rs.getLong("duration_ms"),
            createdAt = rs.getLong("created_at"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            template = try { MeetingTemplate.valueOf(rs.getString("template")) } catch (_: Exception) { MeetingTemplate.GENERAL },
            tags = json.decodeFromString(ListSerializer(String.serializer()), rs.getString("tags")),
            folder = rs.getString("folder"),
            highlights = json.decodeFromString(ListSerializer(Long.serializer()), rs.getString("highlights")),
            attachments = try {
                json.decodeFromString(ListSerializer(Attachment.serializer()), rs.getString("attachments"))
            } catch (_: Exception) { emptyList() }
        )
    }
}
