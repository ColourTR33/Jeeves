package com.jeeves.desktop.data

import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.RecordingsRepository

data class SearchResult(
    val recording: Recording,
    val matchSource: String,  // "transcription" or "summary"
    val snippet: String       // ~60 chars of context around the match
)

class SearchService(
    private val recordingsRepository: RecordingsRepository
) {
    private var transcriptionCache: Map<String, String> = emptyMap()
    private var summaryCache: Map<String, String> = emptyMap()
    private var cacheLoaded = false

    suspend fun search(query: String, recordings: List<Recording>): List<SearchResult> {
        if (!cacheLoaded) {
            loadCache(recordings)
        }
        if (query.isBlank()) return emptyList()

        val lowerQuery = query.lowercase()
        val results = mutableListOf<SearchResult>()

        for (recording in recordings) {
            // Search transcription
            val transcriptionText = transcriptionCache[recording.id]
            if (transcriptionText != null) {
                val index = transcriptionText.lowercase().indexOf(lowerQuery)
                if (index >= 0) {
                    results.add(
                        SearchResult(
                            recording = recording,
                            matchSource = "transcription",
                            snippet = extractSnippet(transcriptionText, index, query.length)
                        )
                    )
                    continue // One result per recording
                }
            }

            // Search summary
            val summaryText = summaryCache[recording.id]
            if (summaryText != null) {
                val index = summaryText.lowercase().indexOf(lowerQuery)
                if (index >= 0) {
                    results.add(
                        SearchResult(
                            recording = recording,
                            matchSource = "summary",
                            snippet = extractSnippet(summaryText, index, query.length)
                        )
                    )
                }
            }
        }

        return results
    }

    private suspend fun loadCache(recordings: List<Recording>) {
        val transcriptions = mutableMapOf<String, String>()
        val summaries = mutableMapOf<String, String>()

        for (recording in recordings) {
            recordingsRepository.getTranscription(recording.id)?.let {
                transcriptions[recording.id] = it.text
            }
            recordingsRepository.getSummary(recording.id)?.let {
                summaries[recording.id] = "${it.summary} ${it.keyPoints.joinToString(" ")} ${it.actionItems.joinToString(" ")}"
            }
        }

        transcriptionCache = transcriptions
        summaryCache = summaries
        cacheLoaded = true
    }

    fun invalidateCache() {
        cacheLoaded = false
    }

    private fun extractSnippet(text: String, matchIndex: Int, matchLength: Int): String {
        val contextChars = 30
        val start = maxOf(0, matchIndex - contextChars)
        val end = minOf(text.length, matchIndex + matchLength + contextChars)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return "$prefix${text.substring(start, end)}$suffix"
    }
}
