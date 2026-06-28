package com.jeeves.shared.sync

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ConflictResolver implementing field-specific conflict resolution strategies.
 *
 * **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6**
 */
class ConflictResolverTest {

    private val resolver = ConflictResolver("local-device")

    // =========================================================================
    // resolveRecording: Last-write-wins for title/description
    // =========================================================================

    @Test
    fun resolveRecording_titleDescription_laterTimestampWins() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            title = "Local Title",
            description = "Local Desc"
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            title = "Remote Title",
            description = "Remote Desc"
        )

        val result = resolver.resolveRecording(local, remote)

        assertEquals("Remote Title", result.body["title"]?.jsonPrimitive?.content)
        assertEquals("Remote Desc", result.body["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun resolveRecording_titleDescription_sameTimestamp_greaterDeviceIdWins() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            title = "Local Title",
            description = "Local Desc"
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 1000L,
            title = "Remote Title",
            description = "Remote Desc"
        )

        val result = resolver.resolveRecording(local, remote)

        // "device-B" > "device-A" lexicographically, so remote wins
        assertEquals("Remote Title", result.body["title"]?.jsonPrimitive?.content)
        assertEquals("Remote Desc", result.body["description"]?.jsonPrimitive?.content)
    }

    // =========================================================================
    // resolveRecording: Note concatenation
    // =========================================================================

    @Test
    fun resolveRecording_notes_concatenatedByTimestampOrder() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            note = "First note"
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            note = "Second note"
        )

        val result = resolver.resolveRecording(local, remote)
        val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content

        assertEquals("[device-A]: First note\n[device-B]: Second note", resolvedNote)
    }

    @Test
    fun resolveRecording_notes_emptyLocalNote_onlyRemoteIncluded() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            note = ""
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            note = "Only note"
        )

        val result = resolver.resolveRecording(local, remote)
        val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content

        assertEquals("[device-B]: Only note", resolvedNote)
    }

    @Test
    fun resolveRecording_notes_bothEmpty_returnsEmpty() {
        val local = makeRecordingDoc(deviceId = "device-A", modifiedAt = 1000L, note = "")
        val remote = makeRecordingDoc(deviceId = "device-B", modifiedAt = 2000L, note = "")

        val result = resolver.resolveRecording(local, remote)
        val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content

        assertEquals("", resolvedNote)
    }

    // =========================================================================
    // resolveRecording: Tags union merge
    // =========================================================================

    @Test
    fun resolveRecording_tags_unionMerge() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            tags = listOf("sprint", "planning")
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            tags = listOf("planning", "api")
        )

        val result = resolver.resolveRecording(local, remote)
        val resolvedTags = result.body["tags"]?.jsonArray?.map { it.jsonPrimitive.content }

        assertNotNull(resolvedTags)
        assertEquals(3, resolvedTags.size)
        assertTrue(resolvedTags.contains("sprint"))
        assertTrue(resolvedTags.contains("planning"))
        assertTrue(resolvedTags.contains("api"))
    }

    @Test
    fun resolveRecording_tags_caseSensitive() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            tags = listOf("Sprint")
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            tags = listOf("sprint")
        )

        val result = resolver.resolveRecording(local, remote)
        val resolvedTags = result.body["tags"]?.jsonArray?.map { it.jsonPrimitive.content }

        assertNotNull(resolvedTags)
        // "Sprint" and "sprint" are different (case-sensitive)
        assertEquals(2, resolvedTags.size)
        assertTrue(resolvedTags.contains("Sprint"))
        assertTrue(resolvedTags.contains("sprint"))
    }

    // =========================================================================
    // resolveRecording: Highlights union merge
    // =========================================================================

    @Test
    fun resolveRecording_highlights_unionMerge() {
        val local = makeRecordingDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            highlights = listOf(1000L, 2000L)
        )
        val remote = makeRecordingDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            highlights = listOf(2000L, 3000L)
        )

        val result = resolver.resolveRecording(local, remote)
        val resolvedHighlights = result.body["highlights"]?.jsonArray?.map { it.jsonPrimitive.long }

        assertNotNull(resolvedHighlights)
        assertEquals(3, resolvedHighlights.size)
        assertTrue(resolvedHighlights.contains(1000L))
        assertTrue(resolvedHighlights.contains(2000L))
        assertTrue(resolvedHighlights.contains(3000L))
    }

    // =========================================================================
    // resolveTextDocument: Longest-content-wins
    // =========================================================================

    @Test
    fun resolveTextDocument_transcription_longerTextWins() {
        val local = makeTranscriptionDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            text = "Short"
        )
        val remote = makeTranscriptionDoc(
            deviceId = "device-B",
            modifiedAt = 500L,
            text = "This is a much longer transcription text"
        )

        val result = resolver.resolveTextDocument(local, remote)

        assertEquals(remote, result)
    }

    @Test
    fun resolveTextDocument_summary_longerSummaryWins() {
        val local = makeSummaryDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            summary = "This is the longer summary text content"
        )
        val remote = makeSummaryDoc(
            deviceId = "device-B",
            modifiedAt = 2000L,
            summary = "Short"
        )

        val result = resolver.resolveTextDocument(local, remote)

        assertEquals(local, result)
    }

    @Test
    fun resolveTextDocument_equalLength_moreRecentModifiedAtWins() {
        val local = makeTranscriptionDoc(
            deviceId = "device-A",
            modifiedAt = 2000L,
            text = "ABCDE"
        )
        val remote = makeTranscriptionDoc(
            deviceId = "device-B",
            modifiedAt = 1000L,
            text = "FGHIJ"
        )

        // Same length (5 chars each), local has more recent modifiedAt
        val result = resolver.resolveTextDocument(local, remote)

        assertEquals(local, result)
    }

    @Test
    fun resolveTextDocument_equalLength_sameTimestamp_greaterDeviceIdWins() {
        val local = makeTranscriptionDoc(
            deviceId = "device-A",
            modifiedAt = 1000L,
            text = "ABCDE"
        )
        val remote = makeTranscriptionDoc(
            deviceId = "device-B",
            modifiedAt = 1000L,
            text = "FGHIJ"
        )

        // Same length, same timestamp: "device-B" > "device-A" lexicographically
        val result = resolver.resolveTextDocument(local, remote)

        assertEquals(remote, result)
    }

    // =========================================================================
    // resolve(): dispatching and audit entries
    // =========================================================================

    @Test
    fun resolve_recordingType_dispatchesToResolveRecording() {
        val local = makeRecordingDoc(deviceId = "device-A", modifiedAt = 1000L, title = "A")
        val remote = makeRecordingDoc(deviceId = "device-B", modifiedAt = 2000L, title = "B")

        val result = resolver.resolve(local, remote)

        assertEquals(ResolutionStrategy.UNION_MERGE, result.strategy)
        assertNotNull(result.auditEntry)
        assertEquals(local.id, result.auditEntry.documentId)
    }

    @Test
    fun resolve_transcriptionType_dispatchesToResolveTextDocument() {
        val local = makeTranscriptionDoc(deviceId = "device-A", modifiedAt = 1000L, text = "Short")
        val remote = makeTranscriptionDoc(deviceId = "device-B", modifiedAt = 2000L, text = "Much longer text here")

        val result = resolver.resolve(local, remote)

        assertEquals(ResolutionStrategy.LONGEST_CONTENT, result.strategy)
        assertEquals(remote, result.winner)
        assertNotNull(result.auditEntry)
    }

    @Test
    fun resolve_auditEntry_containsAllRequiredFields() {
        val local = makeRecordingDoc(deviceId = "device-A", modifiedAt = 1000L, title = "A")
        val remote = makeRecordingDoc(deviceId = "device-B", modifiedAt = 2000L, title = "B")

        val result = resolver.resolve(local, remote)
        val audit = result.auditEntry

        assertTrue(audit.id.isNotEmpty())
        assertEquals(local.id, audit.documentId)
        assertEquals("device-A", audit.localDeviceId)
        assertEquals("device-B", audit.remoteDeviceId)
        assertNotNull(audit.strategy)
        assertTrue(audit.resolvedAt > 0)
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    private fun makeRecordingDoc(
        deviceId: String,
        modifiedAt: Long,
        title: String = "Title",
        description: String = "Description",
        note: String = "",
        tags: List<String> = emptyList(),
        highlights: List<Long> = emptyList()
    ): CouchDocument {
        val body = buildJsonObject {
            put("id", "rec-123")
            put("filePath", "/audio/rec.wav")
            put("durationMs", 60000L)
            put("createdAt", 1000L)
            put("title", title)
            put("description", description)
            put("template", "GENERAL")
            put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
            put("folder", "")
            put("highlights", JsonArray(highlights.map { JsonPrimitive(it) }))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", note)
        }
        return CouchDocument(
            id = "recording:rec-123",
            rev = "1-${deviceId.hashCode().toUInt().toString(16)}",
            type = "recording",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = body
        )
    }

    private fun makeTranscriptionDoc(
        deviceId: String,
        modifiedAt: Long,
        text: String
    ): CouchDocument {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
            put("text", text)
            put("segments", JsonArray(emptyList()))
            put("language", "en")
            put("durationMs", 60000L)
            put("diarizationUnavailable", false)
        }
        return CouchDocument(
            id = "transcription:rec-123",
            rev = "1-${deviceId.hashCode().toUInt().toString(16)}",
            type = "transcription",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = body
        )
    }

    private fun makeSummaryDoc(
        deviceId: String,
        modifiedAt: Long,
        summary: String
    ): CouchDocument {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
            put("summary", summary)
            put("keyPoints", JsonArray(emptyList()))
            put("actionItems", JsonArray(emptyList()))
            put("questions", JsonArray(emptyList()))
            put("tags", JsonArray(emptyList()))
            put("modelUsed", "test-model")
            put("recommendedQuestions", JsonArray(emptyList()))
            put("qualityRating", JsonNull)
        }
        return CouchDocument(
            id = "summary:rec-123",
            rev = "1-${deviceId.hashCode().toUInt().toString(16)}",
            type = "summary",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = body
        )
    }
}
