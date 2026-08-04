package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.RecordingsRepository
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Search result with match context.
 */
data class SearchResult(
    val recording: Recording,
    val matchSource: String,  // "title", "transcription", "summary", "tags"
    val snippet: String,      // Text with match highlighted
    val rank: Double = 0.0    // FTS5 rank score (lower is better match)
)

/**
 * Full-text search service using SQLite FTS5.
 * Maintains an FTS5 virtual table indexed on recording titles, transcriptions, and summaries.
 * Provides fast, ranked search results with snippet highlighting.
 */
class SearchService(
    private val recordingsRepository: RecordingsRepository
) {
    private val dbPath: String
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves/data")
            dir.mkdirs()
            return File(dir, "jeeves-search.db").absolutePath
        }

    private val connection: Connection by lazy {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
            conn.createStatement().execute("PRAGMA journal_mode=WAL")
            conn.createStatement().execute("PRAGMA busy_timeout=5000")
            conn.autoCommit = true
            createFtsTables(conn)
            AppLogger.info("SearchService", "FTS database opened: $dbPath")
        }
    }

    private fun <T> withDb(block: (Connection) -> T): T {
        return synchronized(connection) {
            block(connection)
        }
    }

    private fun createFtsTables(conn: Connection) {
        // FTS5 virtual table for full-text search
        conn.createStatement().executeUpdate("""
            CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(
                recording_id,
                title,
                description,
                transcription,
                summary,
                key_points,
                action_items,
                tags,
                content='',
                tokenize='porter unicode61'
            )
        """)

        // Track which recordings have been indexed
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS indexed_recordings (
                recording_id TEXT PRIMARY KEY,
                indexed_at INTEGER NOT NULL
            )
        """)
    }

    /**
     * Search across all recordings using FTS5.
     * Returns ranked results with highlighted snippets.
     */
    suspend fun search(query: String, recordings: List<Recording>): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // Ensure index is up to date
        rebuildIndexIfNeeded(recordings)

        return withDb { conn ->
            // FTS5 search with ranking
            val ftsQuery = sanitizeFtsQuery(query)
            val stmt = conn.prepareStatement("""
                SELECT 
                    recording_id,
                    snippet(search_index, 1, '<b>', '</b>', '...', 20) as title_snippet,
                    snippet(search_index, 3, '<b>', '</b>', '...', 30) as trans_snippet,
                    snippet(search_index, 4, '<b>', '</b>', '...', 30) as summary_snippet,
                    bm25(search_index) as rank
                FROM search_index
                WHERE search_index MATCH ?
                ORDER BY rank
                LIMIT 50
            """)
            stmt.setString(1, ftsQuery)

            val rs = stmt.executeQuery()
            val results = mutableListOf<SearchResult>()
            val recordingMap = recordings.associateBy { it.id }

            while (rs.next()) {
                val recordingId = rs.getString("recording_id")
                val recording = recordingMap[recordingId] ?: continue

                val titleSnippet = rs.getString("title_snippet") ?: ""
                val transSnippet = rs.getString("trans_snippet") ?: ""
                val summarySnippet = rs.getString("summary_snippet") ?: ""
                val rank = rs.getDouble("rank")

                // Determine best match source
                val (source, snippet) = when {
                    titleSnippet.contains("<b>") -> "title" to titleSnippet
                    transSnippet.contains("<b>") -> "transcription" to transSnippet
                    summarySnippet.contains("<b>") -> "summary" to summarySnippet
                    else -> "transcription" to transSnippet.ifEmpty { titleSnippet }
                }

                results.add(SearchResult(
                    recording = recording,
                    matchSource = source,
                    snippet = snippet.replace("<b>", "**").replace("</b>", "**"),
                    rank = rank
                ))
            }

            rs.close()
            stmt.close()
            results
        }
    }

    /**
     * Index a recording and its transcription/summary.
     */
    suspend fun indexRecording(recording: Recording) {
        val transcription = recordingsRepository.getTranscription(recording.id)
        val summary = recordingsRepository.getSummary(recording.id)

        withDb { conn ->
            // Delete existing entry
            conn.prepareStatement("DELETE FROM search_index WHERE recording_id = ?").apply {
                setString(1, recording.id)
                executeUpdate()
                close()
            }

            // Insert new entry
            val stmt = conn.prepareStatement("""
                INSERT INTO search_index (recording_id, title, description, transcription, summary, key_points, action_items, tags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)
            stmt.setString(1, recording.id)
            stmt.setString(2, recording.title)
            stmt.setString(3, recording.description)
            stmt.setString(4, transcription?.text ?: "")
            stmt.setString(5, summary?.summary ?: "")
            stmt.setString(6, summary?.keyPoints?.joinToString(" ") ?: "")
            stmt.setString(7, summary?.actionItems?.joinToString(" ") ?: "")
            stmt.setString(8, recording.tags.joinToString(" "))
            stmt.executeUpdate()
            stmt.close()

            // Update indexed_recordings tracker
            conn.prepareStatement("INSERT OR REPLACE INTO indexed_recordings (recording_id, indexed_at) VALUES (?, ?)").apply {
                setString(1, recording.id)
                setLong(2, System.currentTimeMillis())
                executeUpdate()
                close()
            }
        }
    }

    /**
     * Remove a recording from the search index.
     */
    fun removeFromIndex(recordingId: String) {
        withDb { conn ->
            conn.prepareStatement("DELETE FROM search_index WHERE recording_id = ?").apply {
                setString(1, recordingId)
                executeUpdate()
                close()
            }
            conn.prepareStatement("DELETE FROM indexed_recordings WHERE recording_id = ?").apply {
                setString(1, recordingId)
                executeUpdate()
                close()
            }
        }
    }

    /**
     * Rebuild the entire search index.
     */
    suspend fun rebuildIndex(recordings: List<Recording>) {
        AppLogger.info("SearchService", "Rebuilding search index for ${recordings.size} recordings...")

        withDb { conn ->
            conn.createStatement().executeUpdate("DELETE FROM search_index")
            conn.createStatement().executeUpdate("DELETE FROM indexed_recordings")
        }

        for (recording in recordings) {
            indexRecording(recording)
        }

        AppLogger.info("SearchService", "Search index rebuilt")
    }

    /**
     * Check if index needs rebuilding and rebuild incrementally.
     */
    private suspend fun rebuildIndexIfNeeded(recordings: List<Recording>) {
        val indexedIds = withDb { conn ->
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT recording_id FROM indexed_recordings")
            val ids = mutableSetOf<String>()
            while (rs.next()) {
                ids.add(rs.getString("recording_id"))
            }
            rs.close()
            stmt.close()
            ids
        }

        val recordingIds = recordings.map { it.id }.toSet()

        // Index any new recordings
        for (recording in recordings) {
            if (recording.id !in indexedIds) {
                indexRecording(recording)
            }
        }

        // Remove deleted recordings from index
        for (indexedId in indexedIds) {
            if (indexedId !in recordingIds) {
                removeFromIndex(indexedId)
            }
        }
    }

    /**
     * Sanitize user query for FTS5.
     * Converts simple queries to FTS5 syntax.
     */
    private fun sanitizeFtsQuery(query: String): String {
        // Escape special FTS5 characters and convert to prefix search
        val escaped = query
            .replace("\"", "\"\"")
            .replace("*", "")
            .replace(":", " ")
            .trim()

        // If multiple words, search for all of them (implicit AND)
        val words = escaped.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return if (words.size > 1) {
            words.joinToString(" ") { "\"$it\"*" }
        } else {
            "\"$escaped\"*"
        }
    }

    /**
     * Legacy method for compatibility - delegates to FTS search.
     */
    fun invalidateCache() {
        // No-op for FTS - index is always up to date
    }
}
