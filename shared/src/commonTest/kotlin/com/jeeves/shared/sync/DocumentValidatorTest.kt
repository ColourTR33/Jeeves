package com.jeeves.shared.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * Unit tests for DocumentValidator and schema validation integration with LocalDocumentStore.
 *
 * **Validates: Requirements 7.5, 7.6**
 */
class DocumentValidatorTest {

    private lateinit var tempDir: File
    private lateinit var store: LocalDocumentStore

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("validatorTest").toFile()
        store = LocalDocumentStore(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =============================================================================
    // DocumentValidator unit tests - Recording schema
    // =============================================================================

    @Test
    fun recording_validDocument_passes() {
        val doc = createValidRecordingDoc()
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    @Test
    fun recording_missingId_rejected() {
        val body = buildJsonObject {
            put("filePath", "/test.wav")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("id"))
    }

    @Test
    fun recording_missingFilePath_rejected() {
        val body = buildJsonObject {
            put("id", "test")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("filePath"))
    }

    @Test
    fun recording_missingDurationMs_rejected() {
        val body = buildJsonObject {
            put("id", "test")
            put("filePath", "/test.wav")
            put("createdAt", 1700000000000L)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("durationMs"))
    }

    @Test
    fun recording_missingCreatedAt_rejected() {
        val body = buildJsonObject {
            put("id", "test")
            put("filePath", "/test.wav")
            put("durationMs", 60000)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("createdAt"))
    }

    @Test
    fun recording_idWrongType_number_rejected() {
        val body = buildJsonObject {
            put("id", 12345)
            put("filePath", "/test.wav")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("id"))
        assertTrue(result.reason.contains("string"))
    }

    @Test
    fun recording_durationMsWrongType_string_rejected() {
        val body = buildJsonObject {
            put("id", "test")
            put("filePath", "/test.wav")
            put("durationMs", "sixty-thousand")
            put("createdAt", 1700000000000L)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("durationMs"))
        assertTrue(result.reason.contains("number"))
    }

    @Test
    fun recording_createdAtWrongType_boolean_rejected() {
        val body = buildJsonObject {
            put("id", "test")
            put("filePath", "/test.wav")
            put("durationMs", 60000)
            put("createdAt", true)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("createdAt"))
        assertTrue(result.reason.contains("number"))
    }

    @Test
    fun recording_nullRequiredField_rejected() {
        val body = buildJsonObject {
            put("id", JsonNull)
            put("filePath", "/test.wav")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("id"))
    }

    @Test
    fun recording_withOptionalFieldsOnly_passes() {
        // Valid doc with all required fields + optional fields
        val body = buildJsonObject {
            put("id", "test")
            put("filePath", "/test.wav")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
            put("title", "My Meeting")
            put("tags", JsonArray(listOf(JsonPrimitive("tag1"))))
            put("folder", "work")
        }
        val doc = createDocWithBody("recording:test", "recording", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    // =============================================================================
    // DocumentValidator unit tests - Transcription schema
    // =============================================================================

    @Test
    fun transcription_validDocument_passes() {
        val doc = createValidTranscriptionDoc()
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    @Test
    fun transcription_missingRecordingId_rejected() {
        val body = buildJsonObject {
            put("text", "Hello world")
        }
        val doc = createDocWithBody("transcription:test", "transcription", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("recordingId"))
    }

    @Test
    fun transcription_missingText_rejected() {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
        }
        val doc = createDocWithBody("transcription:test", "transcription", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("text"))
    }

    @Test
    fun transcription_recordingIdWrongType_rejected() {
        val body = buildJsonObject {
            put("recordingId", 123)
            put("text", "Hello world")
        }
        val doc = createDocWithBody("transcription:test", "transcription", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("recordingId"))
    }

    @Test
    fun transcription_textWrongType_rejected() {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
            put("text", JsonArray(listOf(JsonPrimitive("chunk"))))
        }
        val doc = createDocWithBody("transcription:test", "transcription", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("text"))
    }

    @Test
    fun transcription_withOptionalFields_passes() {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
            put("text", "Hello world")
            put("language", "en")
            put("durationMs", 5000)
            put("diarizationUnavailable", false)
        }
        val doc = createDocWithBody("transcription:test", "transcription", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    // =============================================================================
    // DocumentValidator unit tests - Summary schema
    // =============================================================================

    @Test
    fun summary_validDocument_passes() {
        val doc = createValidSummaryDoc()
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    @Test
    fun summary_missingRecordingId_rejected() {
        val body = buildJsonObject {
            put("summary", "The team discussed...")
        }
        val doc = createDocWithBody("summary:test", "summary", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("recordingId"))
    }

    @Test
    fun summary_missingSummary_rejected() {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
        }
        val doc = createDocWithBody("summary:test", "summary", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("summary"))
    }

    @Test
    fun summary_recordingIdWrongType_rejected() {
        val body = buildJsonObject {
            put("recordingId", 456)
            put("summary", "Team discussed goals")
        }
        val doc = createDocWithBody("summary:test", "summary", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("recordingId"))
    }

    @Test
    fun summary_summaryWrongType_rejected() {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
            put("summary", 42)
        }
        val doc = createDocWithBody("summary:test", "summary", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("summary"))
    }

    @Test
    fun summary_withOptionalFields_passes() {
        val body = buildJsonObject {
            put("recordingId", "rec-123")
            put("summary", "Team discussed goals")
            put("keyPoints", JsonArray(listOf(JsonPrimitive("point 1"))))
            put("actionItems", JsonArray(listOf(JsonPrimitive("do thing"))))
            put("modelUsed", "qwen3:8b")
        }
        val doc = createDocWithBody("summary:test", "summary", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    // =============================================================================
    // Unknown type documents pass through
    // =============================================================================

    @Test
    fun unknownType_passes() {
        val body = buildJsonObject { put("foo", "bar") }
        val doc = createDocWithBody("custom:test", "custom", body)
        val result = DocumentValidator.validate(doc)
        assertIs<DocumentValidator.ValidationResult.Valid>(result)
    }

    // =============================================================================
    // Integration tests - bulkDocs with newEdits=false validation
    // =============================================================================

    @Test
    fun bulkDocs_replicationMode_validDocuments_persisted() = runTest {
        val docs = listOf(
            createValidRecordingDoc("recording:r1", "3-abc123"),
            createValidTranscriptionDoc("transcription:t1", "1-def456"),
            createValidSummaryDoc("summary:s1", "2-ghi789")
        )

        val results = store.bulkDocs(docs, newEdits = false)

        assertEquals(3, results.size)
        assertTrue(results.all { it.ok })

        // Verify documents are persisted
        assertNotNull(store.get("recording:r1"))
        assertNotNull(store.get("transcription:t1"))
        assertNotNull(store.get("summary:s1"))
    }

    @Test
    fun bulkDocs_replicationMode_malformedDocumentRejected_notPersisted() = runTest {
        val malformedBody = buildJsonObject {
            // Missing required "id" and "filePath" for recording
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
        }
        val malformedDoc = CouchDocument(
            id = "recording:bad1",
            rev = "1-abc",
            type = "recording",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = malformedBody
        )

        val results = store.bulkDocs(listOf(malformedDoc), newEdits = false)

        assertEquals(1, results.size)
        assertFalse(results[0].ok)
        assertEquals("validation_failed", results[0].error)
        assertNotNull(results[0].reason)
        assertTrue(results[0].reason!!.contains("id"))

        // Document should NOT be persisted
        assertNull(store.get("recording:bad1"))
    }

    @Test
    fun bulkDocs_replicationMode_malformedSkipped_othersProcessed() = runTest {
        val validDoc = createValidRecordingDoc("recording:good1", "1-valid")
        val malformedDoc = CouchDocument(
            id = "recording:bad2",
            rev = "1-bad",
            type = "recording",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = buildJsonObject {
                // Missing required fields
                put("title", "No required fields")
            }
        )
        val anotherValidDoc = createValidTranscriptionDoc("transcription:good2", "1-also-valid")

        val results = store.bulkDocs(
            listOf(validDoc, malformedDoc, anotherValidDoc),
            newEdits = false
        )

        assertEquals(3, results.size)
        assertTrue(results[0].ok, "First valid doc should succeed")
        assertFalse(results[1].ok, "Malformed doc should fail")
        assertEquals("validation_failed", results[1].error)
        assertTrue(results[2].ok, "Third valid doc should succeed")

        // Verify persistence
        assertNotNull(store.get("recording:good1"))
        assertNull(store.get("recording:bad2"))
        assertNotNull(store.get("transcription:good2"))
    }

    @Test
    fun bulkDocs_normalMode_noValidation_malformedDocsPersisted() = runTest {
        // In newEdits=true mode, no schema validation is applied (trusted local code)
        val malformedBody = buildJsonObject {
            put("title", "Incomplete recording body")
            // Missing required "id", "filePath", "durationMs", "createdAt"
        }
        val doc = CouchDocument(
            id = "recording:local1",
            type = "recording",
            deviceId = "local-device",
            modifiedAt = 1700000000000L,
            body = malformedBody
        )

        val results = store.bulkDocs(listOf(doc), newEdits = true)

        assertEquals(1, results.size)
        assertTrue(results[0].ok, "Local writes bypass validation")

        // Document should be persisted even though body is malformed
        assertNotNull(store.get("recording:local1"))
    }

    @Test
    fun bulkDocs_replicationMode_wrongFieldType_rejected() = runTest {
        val body = buildJsonObject {
            put("id", 999) // Should be String
            put("filePath", "/test.wav")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
        }
        val doc = CouchDocument(
            id = "recording:typefail",
            rev = "1-abc",
            type = "recording",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = body
        )

        val results = store.bulkDocs(listOf(doc), newEdits = false)

        assertFalse(results[0].ok)
        assertEquals("validation_failed", results[0].error)
        assertTrue(results[0].reason!!.contains("string"))

        assertNull(store.get("recording:typefail"))
    }

    @Test
    fun bulkDocs_replicationMode_unknownType_passesThrough() = runTest {
        val body = buildJsonObject {
            put("someField", "anything")
        }
        val doc = CouchDocument(
            id = "custom:unknown1",
            rev = "1-xyz",
            type = "custom",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = body
        )

        val results = store.bulkDocs(listOf(doc), newEdits = false)

        assertTrue(results[0].ok, "Unknown types should pass through validation")
        assertNotNull(store.get("custom:unknown1"))
    }

    @Test
    fun bulkDocs_replicationMode_rejectedDocResult_includesDocId() = runTest {
        val doc = CouchDocument(
            id = "summary:idcheck",
            rev = "1-abc",
            type = "summary",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = buildJsonObject { put("recordingId", 123) } // wrong type + missing summary
        )

        val results = store.bulkDocs(listOf(doc), newEdits = false)

        assertEquals("summary:idcheck", results[0].id)
        assertFalse(results[0].ok)
    }

    // =============================================================================
    // Helpers
    // =============================================================================

    private fun createValidRecordingDoc(
        id: String = "recording:valid1",
        rev: String = "1-abc123"
    ): CouchDocument {
        val body = buildJsonObject {
            put("id", id.substringAfter(":"))
            put("filePath", "/recordings/test.wav")
            put("durationMs", 60000)
            put("createdAt", 1700000000000L)
            put("title", "Test Recording")
            put("description", "")
            put("template", "GENERAL")
            put("tags", JsonArray(emptyList()))
            put("folder", "")
            put("highlights", JsonArray(emptyList()))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", "")
        }
        return CouchDocument(
            id = id,
            rev = rev,
            type = "recording",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = body
        )
    }

    private fun createValidTranscriptionDoc(
        id: String = "transcription:valid1",
        rev: String = "1-def456"
    ): CouchDocument {
        val body = buildJsonObject {
            put("recordingId", id.substringAfter(":"))
            put("text", "Hello world, this is a transcription.")
            put("segments", JsonArray(emptyList()))
            put("language", "en")
            put("durationMs", 5000)
            put("diarizationUnavailable", false)
        }
        return CouchDocument(
            id = id,
            rev = rev,
            type = "transcription",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = body
        )
    }

    private fun createValidSummaryDoc(
        id: String = "summary:valid1",
        rev: String = "1-ghi789"
    ): CouchDocument {
        val body = buildJsonObject {
            put("recordingId", id.substringAfter(":"))
            put("summary", "The team discussed sprint planning goals.")
            put("keyPoints", JsonArray(emptyList()))
            put("actionItems", JsonArray(emptyList()))
            put("questions", JsonArray(emptyList()))
            put("tags", JsonArray(emptyList()))
            put("modelUsed", "qwen3:8b")
            put("recommendedQuestions", JsonArray(emptyList()))
            put("qualityRating", JsonNull)
        }
        return CouchDocument(
            id = id,
            rev = rev,
            type = "summary",
            deviceId = "remote-device",
            modifiedAt = 1700000000000L,
            body = body
        )
    }

    private fun createDocWithBody(id: String, type: String, body: JsonObject): CouchDocument {
        return CouchDocument(
            id = id,
            rev = "1-test",
            type = type,
            deviceId = "test-device",
            modifiedAt = 1700000000000L,
            body = body
        )
    }
}
