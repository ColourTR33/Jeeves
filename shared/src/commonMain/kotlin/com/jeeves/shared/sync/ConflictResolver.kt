package com.jeeves.shared.sync

import com.jeeves.shared.recording.currentTimeMillis
import com.jeeves.shared.recording.generateId
import kotlinx.serialization.json.*

/**
 * Resolves conflicts between local and remote document revisions using
 * field-specific strategies defined in the multi-device-sync specification.
 *
 * Strategies:
 * - Recording title/description: Last-write-wins by `modifiedAt`, tiebreak by lexicographic `deviceId`
 * - Recording postRecordingNote: Concatenation ordered by timestamp, prefixed by deviceId
 * - Recording tags/highlights: Union merge (case-sensitive for tags)
 * - TranscriptionResult/SummaryResult: Longest-content-wins by character count, tiebreak by `modifiedAt`
 *
 * Every resolved conflict produces a [ConflictAuditEntry] for the audit log.
 *
 * **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6**
 */
class ConflictResolver(private val deviceId: String) {

    /**
     * Resolve conflicts between local and remote versions of a document.
     * Dispatches to type-specific resolution based on the document's `type` field.
     */
    fun resolve(local: CouchDocument, remote: CouchDocument): ResolvedDocument {
        return when (local.type) {
            "recording" -> resolveRecordingConflict(local, remote)
            "transcription", "summary" -> resolveTextDocumentConflict(local, remote)
            else -> {
                // Unknown type: fall back to last-write-wins on the whole document
                val winner = lastWriteWins(local, remote)
                val loser = if (winner === local) remote else local
                val auditEntry = createAuditEntry(
                    documentId = local.id,
                    local = local,
                    remote = remote,
                    strategy = ResolutionStrategy.LAST_WRITE_WINS,
                    winnerRev = winner.rev ?: ""
                )
                ResolvedDocument(
                    winner = winner,
                    loser = loser,
                    strategy = ResolutionStrategy.LAST_WRITE_WINS,
                    auditEntry = auditEntry
                )
            }
        }
    }

    /**
     * Resolve a Recording document conflict using field-specific strategies:
     * - title/description: last-write-wins (by modifiedAt, tiebreak by deviceId)
     * - postRecordingNote: concatenation (ordered by timestamp, prefixed by deviceId)
     * - tags: union merge (case-sensitive)
     * - highlights: union merge
     *
     * Other fields (id, filePath, durationMs, createdAt, template, folder, attachments)
     * are taken from the last-write-wins winner.
     */
    fun resolveRecording(local: CouchDocument, remote: CouchDocument): CouchDocument {
        val localBody = local.body
        val remoteBody = remote.body

        // Determine last-write-wins winner for title/description and base fields
        val lwwWinner = lastWriteWins(local, remote)
        val winnerBody = lwwWinner.body

        // --- title/description: from LWW winner ---
        val title = winnerBody["title"] ?: JsonPrimitive("")
        val description = winnerBody["description"] ?: JsonPrimitive("")

        // --- postRecordingNote: concatenation ordered by modifiedAt ascending ---
        val resolvedNote = concatenateNotes(local, remote)

        // --- tags: union merge (case-sensitive) ---
        val localTags = localBody.getStringListSafe("tags")
        val remoteTags = remoteBody.getStringListSafe("tags")
        val mergedTags = (localTags + remoteTags).distinct()

        // --- highlights: union merge ---
        val localHighlights = localBody.getLongListSafe("highlights")
        val remoteHighlights = remoteBody.getLongListSafe("highlights")
        val mergedHighlights = (localHighlights + remoteHighlights).distinct()

        // Build the merged body, using LWW winner for base fields
        val mergedBody = buildJsonObject {
            // Copy base fields from the LWW winner
            winnerBody["id"]?.let { put("id", it) }
            winnerBody["filePath"]?.let { put("filePath", it) }
            winnerBody["durationMs"]?.let { put("durationMs", it) }
            winnerBody["createdAt"]?.let { put("createdAt", it) }
            put("title", title)
            put("description", description)
            winnerBody["template"]?.let { put("template", it) }
            put("tags", JsonArray(mergedTags.map { JsonPrimitive(it) }))
            winnerBody["folder"]?.let { put("folder", it) }
            put("highlights", JsonArray(mergedHighlights.map { JsonPrimitive(it) }))
            winnerBody["attachments"]?.let { put("attachments", it) }
            put("postRecordingNote", JsonPrimitive(resolvedNote))
        }

        // The resolved document uses the winner's metadata but merged body
        val resolvedModifiedAt = maxOf(local.modifiedAt, remote.modifiedAt)
        return lwwWinner.copy(
            body = mergedBody,
            modifiedAt = resolvedModifiedAt
        )
    }

    /**
     * Resolve a TranscriptionResult or SummaryResult conflict.
     * Uses longest-content-wins strategy (by character count of the text/summary field).
     * If lengths are equal, uses modifiedAt as tiebreaker (more recent wins).
     */
    fun resolveTextDocument(local: CouchDocument, remote: CouchDocument): CouchDocument {
        val textField = if (local.type == "transcription") "text" else "summary"

        val localText = local.body[textField]?.jsonPrimitive?.content ?: ""
        val remoteText = remote.body[textField]?.jsonPrimitive?.content ?: ""

        return when {
            localText.length > remoteText.length -> local
            remoteText.length > localText.length -> remote
            // Equal length: tiebreak by modifiedAt (more recent wins)
            else -> lastWriteWins(local, remote)
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun resolveRecordingConflict(local: CouchDocument, remote: CouchDocument): ResolvedDocument {
        val resolved = resolveRecording(local, remote)
        val lwwWinner = lastWriteWins(local, remote)
        val loser = if (lwwWinner === local) remote else local

        val auditEntry = createAuditEntry(
            documentId = local.id,
            local = local,
            remote = remote,
            strategy = ResolutionStrategy.UNION_MERGE,
            winnerRev = resolved.rev ?: lwwWinner.rev ?: ""
        )

        return ResolvedDocument(
            winner = resolved,
            loser = loser,
            strategy = ResolutionStrategy.UNION_MERGE,
            auditEntry = auditEntry
        )
    }

    private fun resolveTextDocumentConflict(local: CouchDocument, remote: CouchDocument): ResolvedDocument {
        val winner = resolveTextDocument(local, remote)
        val loser = if (winner === local) remote else local

        val auditEntry = createAuditEntry(
            documentId = local.id,
            local = local,
            remote = remote,
            strategy = ResolutionStrategy.LONGEST_CONTENT,
            winnerRev = winner.rev ?: ""
        )

        return ResolvedDocument(
            winner = winner,
            loser = loser,
            strategy = ResolutionStrategy.LONGEST_CONTENT,
            auditEntry = auditEntry
        )
    }

    /**
     * Determines the last-write-wins winner between two documents.
     * Winner is the document with the greater modifiedAt timestamp.
     * If timestamps are identical, the document with the lexicographically greater deviceId wins.
     */
    private fun lastWriteWins(local: CouchDocument, remote: CouchDocument): CouchDocument {
        return when {
            local.modifiedAt > remote.modifiedAt -> local
            remote.modifiedAt > local.modifiedAt -> remote
            // Identical timestamps: lexicographically greater deviceId wins
            local.deviceId > remote.deviceId -> local
            remote.deviceId > local.deviceId -> remote
            // Completely identical (same device, same timestamp): local wins
            else -> local
        }
    }

    /**
     * Concatenates postRecordingNote fields from both documents.
     * Ordered by modifiedAt ascending, each prefixed by the originating deviceId.
     * Format: "[deviceId-A]: note-A\n[deviceId-B]: note-B"
     *
     * If one note is empty, returns just the non-empty note (still prefixed).
     * If both are empty, returns empty string.
     */
    private fun concatenateNotes(local: CouchDocument, remote: CouchDocument): String {
        val localNote = local.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""
        val remoteNote = remote.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""

        if (localNote.isEmpty() && remoteNote.isEmpty()) return ""

        // Determine order by modifiedAt ascending
        val first: Pair<String, String>
        val second: Pair<String, String>

        if (local.modifiedAt <= remote.modifiedAt) {
            first = local.deviceId to localNote
            second = remote.deviceId to remoteNote
        } else {
            first = remote.deviceId to remoteNote
            second = local.deviceId to localNote
        }

        val parts = mutableListOf<String>()
        if (first.second.isNotEmpty()) {
            parts.add("[${first.first}]: ${first.second}")
        }
        if (second.second.isNotEmpty()) {
            parts.add("[${second.first}]: ${second.second}")
        }

        return parts.joinToString("\n")
    }

    /**
     * Creates an audit log entry for a resolved conflict.
     */
    private fun createAuditEntry(
        documentId: String,
        local: CouchDocument,
        remote: CouchDocument,
        strategy: ResolutionStrategy,
        winnerRev: String
    ): ConflictAuditEntry {
        return ConflictAuditEntry(
            id = generateId(),
            documentId = documentId,
            localDeviceId = local.deviceId,
            remoteDeviceId = remote.deviceId,
            strategy = strategy,
            resolvedAt = currentTimeMillis(),
            localRev = local.rev ?: "",
            remoteRev = remote.rev ?: "",
            winnerRev = winnerRev
        )
    }
}

/**
 * Result of resolving a conflict between two document revisions.
 */
data class ResolvedDocument(
    val winner: CouchDocument,
    val loser: CouchDocument?,
    val strategy: ResolutionStrategy,
    val auditEntry: ConflictAuditEntry
)

// =============================================================================
// Private JsonObject helper extensions
// =============================================================================

private fun JsonObject.getStringListSafe(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { el ->
        (el as? JsonPrimitive)?.content
    }
}

private fun JsonObject.getLongListSafe(key: String): List<Long> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { el ->
        (el as? JsonPrimitive)?.longOrNull
    }
}