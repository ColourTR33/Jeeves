package com.jeeves.shared.sync

import com.jeeves.shared.domain.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit tests for domain-to-CouchDocument converters and reverse converters.
 *
 * **Validates: Requirements 2.1, 2.4, 2.5**
 */
class DocumentConvertersTest {

    // =============================================================================
    // Recording → CouchDocument
    // =============================================================================

    @Test
    fun recording_toCouchDocument_producesCorrectDocumentId() {
        val recording = createTestRecording(id = "abc123")
        val doc = recording.toCouchDocument("device-1", modifiedAt = 1700000000000L)
        assertEquals("recording:abc123", doc.id)
    }

    @Test
    fun recording_toCouchDocument_setsTypeCorrectly() {
        val recording = createTestRecording()
        val doc = recording.toCouchDocument("device-1", modifiedAt = 1700000000000L)
        assertEquals("recording", doc.type)
    }

    @Test
    fun recording_toCouchDocument_setsDeviceId() {
        val recording = createTestRecording()
        val doc = recording.toCouchDocument("my-device-uuid", modifiedAt = 1700000000000L)
        assertEquals("my-device-uuid", doc.deviceId)
    }

    @Test
    fun recording_toCouchDocument_setsModifiedAt() {
        val recording = createTestRecording()
        val doc = recording.toCouchDocument("device-1", modifiedAt = 1700000000000L)
        assertEquals(1700000000000L, doc.modifiedAt)
    }

    @Test
    fun recording_toCouchDocument_preservesAllFieldsInBody() {
        val recording = Recording(
            id = "rec-001",
            filePath = "/recordings/test.wav",
            durationMs = 60000,
            createdAt = 1700000000000L,
            title = "Sprint Planning",
            description = "Weekly sprint planning",
            template = MeetingTemplate.STANDUP,
            tags = listOf("sprint", "planning"),
            folder = "work",
            highlights = listOf(120000L, 1800000L),
            attachments = listOf(
                Attachment("att-1", "/screenshots/screen1.png", 5000, "diagram")
            ),
            postRecordingNote = "Follow up on API redesign"
        )

        val doc = recording.toCouchDocument("device-1", modifiedAt = 1700000000000L)
        val body = doc.body

        assertEquals("rec-001", body["id"]!!.jsonPrimitive.content)
        assertEquals("/recordings/test.wav", body["filePath"]!!.jsonPrimitive.content)
        assertEquals(60000L, body["durationMs"]!!.jsonPrimitive.long)
        assertEquals(1700000000000L, body["createdAt"]!!.jsonPrimitive.long)
        assertEquals("Sprint Planning", body["title"]!!.jsonPrimitive.content)
        assertEquals("Weekly sprint planning", body["description"]!!.jsonPrimitive.content)
        assertEquals("STANDUP", body["template"]!!.jsonPrimitive.content)
        assertEquals(2, body["tags"]!!.jsonArray.size)
        assertEquals("sprint", body["tags"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("work", body["folder"]!!.jsonPrimitive.content)
        assertEquals(2, body["highlights"]!!.jsonArray.size)
        assertEquals(120000L, body["highlights"]!!.jsonArray[0].jsonPrimitive.long)
        assertEquals(1, body["attachments"]!!.jsonArray.size)
        assertEquals("Follow up on API redesign", body["postRecordingNote"]!!.jsonPrimitive.content)
    }

    @Test
    fun recording_toCouchDocument_handlesEmptyDefaults() {
        val recording = Recording(
            id = "minimal",
            filePath = "/test.wav",
            durationMs = 0,
            createdAt = 0
        )

        val doc = recording.toCouchDocument("device-1", modifiedAt = 100L)
        val body = doc.body

        assertEquals("Untitled Meeting", body["title"]!!.jsonPrimitive.content)
        assertEquals("", body["description"]!!.jsonPrimitive.content)
        assertEquals("GENERAL", body["template"]!!.jsonPrimitive.content)
        assertEquals(0, body["tags"]!!.jsonArray.size)
        assertEquals("", body["folder"]!!.jsonPrimitive.content)
        assertEquals(0, body["highlights"]!!.jsonArray.size)
        assertEquals(0, body["attachments"]!!.jsonArray.size)
        assertEquals("", body["postRecordingNote"]!!.jsonPrimitive.content)
    }

    // =============================================================================
    // CouchDocument → Recording
    // =============================================================================

    @Test
    fun couchDocument_toRecording_roundTripPreservesAllFields() {
        val original = Recording(
            id = "rec-002",
            filePath = "/recordings/meeting.wav",
            durationMs = 120000,
            createdAt = 1700000000000L,
            title = "Design Review",
            description = "Architecture discussion",
            template = MeetingTemplate.ONE_ON_ONE,
            tags = listOf("design", "architecture"),
            folder = "projects",
            highlights = listOf(30000L, 60000L, 90000L),
            attachments = listOf(
                Attachment("att-1", "/img/1.png", 1000, "screenshot"),
                Attachment("att-2", "/img/2.png", 2000, "")
            ),
            postRecordingNote = "Need to revisit module boundaries"
        )

        val doc = original.toCouchDocument("device-1", modifiedAt = 1700000000000L)
        val restored = doc.toRecording()

        assertEquals(original, restored)
    }

    @Test
    fun couchDocument_toRecording_failsForWrongType() {
        val doc = CouchDocument(
            id = "transcription:abc",
            type = "transcription",
            deviceId = "dev-1",
            modifiedAt = 100L,
            body = buildJsonObject {}
        )

        assertFailsWith<IllegalArgumentException> {
            doc.toRecording()
        }
    }

    // =============================================================================
    // TranscriptionResult → CouchDocument
    // =============================================================================

    @Test
    fun transcription_toCouchDocument_producesCorrectDocumentId() {
        val transcription = TranscriptionResult(recordingId = "xyz789", text = "Hello")
        val doc = transcription.toCouchDocument("device-1", modifiedAt = 100L)
        assertEquals("transcription:xyz789", doc.id)
    }

    @Test
    fun transcription_toCouchDocument_setsTypeCorrectly() {
        val transcription = TranscriptionResult(recordingId = "abc", text = "Hello")
        val doc = transcription.toCouchDocument("device-1", modifiedAt = 100L)
        assertEquals("transcription", doc.type)
    }

    @Test
    fun transcription_toCouchDocument_preservesSegments() {
        val transcription = TranscriptionResult(
            recordingId = "abc",
            text = "Hello world",
            segments = listOf(
                TranscriptionSegment(0, 5000, "Hello", "Speaker 1"),
                TranscriptionSegment(5000, 10000, "world", null)
            ),
            language = "en",
            durationMs = 10000,
            diarizationUnavailable = false
        )

        val doc = transcription.toCouchDocument("device-1", modifiedAt = 100L)
        val body = doc.body

        assertEquals("abc", body["recordingId"]!!.jsonPrimitive.content)
        assertEquals("Hello world", body["text"]!!.jsonPrimitive.content)
        assertEquals(2, body["segments"]!!.jsonArray.size)
        assertEquals("en", body["language"]!!.jsonPrimitive.content)
        assertEquals(10000L, body["durationMs"]!!.jsonPrimitive.long)
        assertEquals(false, body["diarizationUnavailable"]!!.jsonPrimitive.boolean)
    }

    // =============================================================================
    // CouchDocument → TranscriptionResult
    // =============================================================================

    @Test
    fun couchDocument_toTranscription_roundTripPreservesAllFields() {
        val original = TranscriptionResult(
            recordingId = "rec-123",
            text = "Welcome to the meeting. Let's discuss the agenda.",
            segments = listOf(
                TranscriptionSegment(0, 3000, "Welcome to the meeting.", "Speaker 1"),
                TranscriptionSegment(3000, 7000, "Let's discuss the agenda.", "Speaker 2")
            ),
            language = "en",
            durationMs = 7000,
            diarizationUnavailable = true
        )

        val doc = original.toCouchDocument("device-1", modifiedAt = 100L)
        val restored = doc.toTranscription()

        assertEquals(original, restored)
    }

    @Test
    fun couchDocument_toTranscription_failsForWrongType() {
        val doc = CouchDocument(
            id = "recording:abc",
            type = "recording",
            deviceId = "dev-1",
            modifiedAt = 100L,
            body = buildJsonObject {}
        )

        assertFailsWith<IllegalArgumentException> {
            doc.toTranscription()
        }
    }

    // =============================================================================
    // SummaryResult → CouchDocument
    // =============================================================================

    @Test
    fun summary_toCouchDocument_producesCorrectDocumentId() {
        val summary = SummaryResult(recordingId = "rec-456", summary = "Test")
        val doc = summary.toCouchDocument("device-1", modifiedAt = 100L)
        assertEquals("summary:rec-456", doc.id)
    }

    @Test
    fun summary_toCouchDocument_setsTypeCorrectly() {
        val summary = SummaryResult(recordingId = "abc", summary = "Test")
        val doc = summary.toCouchDocument("device-1", modifiedAt = 100L)
        assertEquals("summary", doc.type)
    }

    @Test
    fun summary_toCouchDocument_preservesQualityRating() {
        val summary = SummaryResult(
            recordingId = "abc",
            summary = "Good meeting",
            qualityRating = QualityRating(4, 3, 5, 4, 4.0)
        )

        val doc = summary.toCouchDocument("device-1", modifiedAt = 100L)
        val body = doc.body

        val ratingElement = body["qualityRating"]!!.jsonObject
        assertEquals(4, ratingElement["pacing"]!!.jsonPrimitive.int)
        assertEquals(3, ratingElement["questions"]!!.jsonPrimitive.int)
        assertEquals(5, ratingElement["goalSetting"]!!.jsonPrimitive.int)
        assertEquals(4, ratingElement["nextSteps"]!!.jsonPrimitive.int)
        assertEquals(4.0, ratingElement["overall"]!!.jsonPrimitive.double)
    }

    @Test
    fun summary_toCouchDocument_handlesNullQualityRating() {
        val summary = SummaryResult(
            recordingId = "abc",
            summary = "Short",
            qualityRating = null
        )

        val doc = summary.toCouchDocument("device-1", modifiedAt = 100L)
        val body = doc.body

        assertEquals(JsonNull, body["qualityRating"])
    }

    // =============================================================================
    // CouchDocument → SummaryResult
    // =============================================================================

    @Test
    fun couchDocument_toSummary_roundTripPreservesAllFields() {
        val original = SummaryResult(
            recordingId = "rec-789",
            summary = "The team discussed sprint goals and agreed on timeline.",
            keyPoints = listOf("API redesign timeline agreed", "New hire onboarding plan"),
            actionItems = listOf("Mark: Draft API spec by Friday"),
            questions = listOf("Should we use GraphQL or REST?"),
            tags = listOf("sprint", "planning", "api"),
            modelUsed = "qwen3:8b",
            recommendedQuestions = listOf("What are the risk mitigation strategies?"),
            qualityRating = QualityRating(4, 3, 5, 4, 4.0)
        )

        val doc = original.toCouchDocument("device-1", modifiedAt = 100L)
        val restored = doc.toSummary()

        assertEquals(original, restored)
    }

    @Test
    fun couchDocument_toSummary_roundTripWithNullQualityRating() {
        val original = SummaryResult(
            recordingId = "rec-null",
            summary = "Brief meeting",
            qualityRating = null
        )

        val doc = original.toCouchDocument("device-1", modifiedAt = 100L)
        val restored = doc.toSummary()

        assertEquals(original, restored)
        assertNull(restored.qualityRating)
    }

    @Test
    fun couchDocument_toSummary_failsForWrongType() {
        val doc = CouchDocument(
            id = "recording:abc",
            type = "recording",
            deviceId = "dev-1",
            modifiedAt = 100L,
            body = buildJsonObject {}
        )

        assertFailsWith<IllegalArgumentException> {
            doc.toSummary()
        }
    }

    // =============================================================================
    // All templates round-trip correctly
    // =============================================================================

    @Test
    fun recording_allMeetingTemplates_roundTripCorrectly() {
        MeetingTemplate.entries.forEach { template ->
            val recording = createTestRecording(template = template)
            val doc = recording.toCouchDocument("device-1", modifiedAt = 100L)
            val restored = doc.toRecording()
            assertEquals(template, restored.template)
        }
    }

    // =============================================================================
    // Helpers
    // =============================================================================

    private fun createTestRecording(
        id: String = "test-id",
        template: MeetingTemplate = MeetingTemplate.GENERAL
    ): Recording = Recording(
        id = id,
        filePath = "/test/$id.wav",
        durationMs = 60000,
        createdAt = 1700000000000L,
        title = "Test Recording",
        description = "A test",
        template = template,
        tags = listOf("test"),
        folder = "",
        highlights = emptyList(),
        attachments = emptyList(),
        postRecordingNote = ""
    )
}
