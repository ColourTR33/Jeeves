package com.jeeves.shared.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for DocumentSchema data classes and serialization.
 *
 * **Validates: Requirements 2.1, 2.4**
 */
class DocumentSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- CouchDocument serialization ---

    @Test
    fun couchDocument_serialization_mapsFieldsToCorrectJsonKeys() {
        val doc = CouchDocument(
            id = "recording:abc123",
            rev = "1-abcdef",
            deleted = false,
            attachments = null,
            type = "recording",
            deviceId = "device-1",
            modifiedAt = 1700000000000L,
            body = buildJsonObject { put("title", "Test") }
        )

        val serialized = json.encodeToString(CouchDocument.serializer(), doc)
        assertTrue(serialized.contains("\"_id\":\"recording:abc123\""))
        assertTrue(serialized.contains("\"_rev\":\"1-abcdef\""))
        assertTrue(serialized.contains("\"_deleted\":false"))
        assertTrue(serialized.contains("\"type\":\"recording\""))
    }

    @Test
    fun couchDocument_roundTrip_preservesAllFields() {
        val doc = CouchDocument(
            id = "transcription:xyz789",
            rev = "3-fedcba",
            deleted = false,
            attachments = mapOf(
                "audio.wav" to AttachmentStub(
                    contentType = "audio/wav",
                    length = 104857600,
                    digest = "md5-abc123",
                    stub = true
                )
            ),
            type = "transcription",
            deviceId = "device-uuid-here",
            modifiedAt = 1700000060000L,
            body = buildJsonObject {
                put("recordingId", "xyz789")
                put("text", "Hello world")
            }
        )

        val serialized = json.encodeToString(CouchDocument.serializer(), doc)
        val deserialized = json.decodeFromString(CouchDocument.serializer(), serialized)

        assertEquals(doc, deserialized)
    }

    @Test
    fun couchDocument_defaultValues_workCorrectly() {
        val doc = CouchDocument(
            id = "summary:test",
            type = "summary",
            deviceId = "dev-1",
            modifiedAt = 123L,
            body = buildJsonObject {}
        )

        assertNull(doc.rev)
        assertFalse(doc.deleted)
        assertNull(doc.attachments)
    }

    @Test
    fun couchDocument_deletedFlag_serializes() {
        val doc = CouchDocument(
            id = "recording:del1",
            rev = "2-abc",
            deleted = true,
            type = "recording",
            deviceId = "dev-1",
            modifiedAt = 100L,
            body = buildJsonObject {}
        )

        val serialized = json.encodeToString(CouchDocument.serializer(), doc)
        assertTrue(serialized.contains("\"_deleted\":true"))
    }

    // --- AttachmentStub serialization ---

    @Test
    fun attachmentStub_serialization_usesCorrectSerialNames() {
        val stub = AttachmentStub(
            contentType = "audio/wav",
            length = 50000000,
            digest = "md5-xyz",
            stub = true
        )

        val serialized = json.encodeToString(AttachmentStub.serializer(), stub)
        assertTrue(serialized.contains("\"content_type\":\"audio/wav\""))
        assertTrue(serialized.contains("\"length\":50000000"))
    }

    @Test
    fun attachmentStub_roundTrip_preservesFields() {
        val stub = AttachmentStub(
            contentType = "image/png",
            length = 1024,
            digest = "md5-test",
            stub = false
        )

        val serialized = json.encodeToString(AttachmentStub.serializer(), stub)
        val deserialized = json.decodeFromString(AttachmentStub.serializer(), serialized)
        assertEquals(stub, deserialized)
    }

    // --- DocumentChange serialization ---

    @Test
    fun documentChange_roundTrip_preservesFields() {
        val change = DocumentChange(
            id = "recording:test1",
            rev = "5-abcdef",
            deleted = false
        )

        val serialized = json.encodeToString(DocumentChange.serializer(), change)
        val deserialized = json.decodeFromString(DocumentChange.serializer(), serialized)
        assertEquals(change, deserialized)
    }

    @Test
    fun documentChange_deletedTrue_serializes() {
        val change = DocumentChange(
            id = "recording:deleted1",
            rev = "2-xyz",
            deleted = true
        )

        val serialized = json.encodeToString(DocumentChange.serializer(), change)
        assertTrue(serialized.contains("\"deleted\":true"))
    }

    // --- ChangesResponse serialization ---

    @Test
    fun changesResponse_roundTrip_preservesFields() {
        val response = ChangesResponse(
            results = listOf(
                DocumentChange("doc1", "1-abc"),
                DocumentChange("doc2", "2-def", deleted = true)
            ),
            lastSeq = "42"
        )

        val serialized = json.encodeToString(ChangesResponse.serializer(), response)
        val deserialized = json.decodeFromString(ChangesResponse.serializer(), serialized)
        assertEquals(response, deserialized)
    }

    @Test
    fun changesResponse_serialization_usesLastSeqSerialName() {
        val response = ChangesResponse(
            results = emptyList(),
            lastSeq = "100"
        )

        val serialized = json.encodeToString(ChangesResponse.serializer(), response)
        assertTrue(serialized.contains("\"last_seq\":\"100\""))
    }

    // --- BulkDocResult serialization ---

    @Test
    fun bulkDocResult_success_roundTrip() {
        val result = BulkDocResult(
            id = "recording:abc",
            rev = "1-new",
            ok = true
        )

        val serialized = json.encodeToString(BulkDocResult.serializer(), result)
        val deserialized = json.decodeFromString(BulkDocResult.serializer(), serialized)
        assertEquals(result, deserialized)
    }

    @Test
    fun bulkDocResult_failure_containsErrorAndReason() {
        val result = BulkDocResult(
            id = "recording:bad",
            ok = false,
            error = "conflict",
            reason = "Document update conflict"
        )

        val serialized = json.encodeToString(BulkDocResult.serializer(), result)
        assertTrue(serialized.contains("\"error\":\"conflict\""))
        assertTrue(serialized.contains("\"reason\":\"Document update conflict\""))
    }

    // --- ConflictAuditEntry serialization ---

    @Test
    fun conflictAuditEntry_roundTrip_preservesAllFields() {
        val entry = ConflictAuditEntry(
            id = "audit-uuid-1",
            documentId = "recording:abc123",
            localDeviceId = "device-a",
            remoteDeviceId = "device-b",
            strategy = ResolutionStrategy.LAST_WRITE_WINS,
            resolvedAt = 1700000000000L,
            localRev = "2-local",
            remoteRev = "3-remote",
            winnerRev = "3-remote"
        )

        val serialized = json.encodeToString(ConflictAuditEntry.serializer(), entry)
        val deserialized = json.decodeFromString(ConflictAuditEntry.serializer(), serialized)
        assertEquals(entry, deserialized)
    }

    @Test
    fun conflictAuditEntry_allStrategies_serialize() {
        ResolutionStrategy.entries.forEach { strategy ->
            val entry = ConflictAuditEntry(
                id = "audit-$strategy",
                documentId = "doc:1",
                localDeviceId = "dev-local",
                remoteDeviceId = "dev-remote",
                strategy = strategy,
                resolvedAt = 0L,
                localRev = "1-a",
                remoteRev = "1-b",
                winnerRev = "1-a"
            )
            val serialized = json.encodeToString(ConflictAuditEntry.serializer(), entry)
            val deserialized = json.decodeFromString(ConflictAuditEntry.serializer(), serialized)
            assertEquals(entry, deserialized)
        }
    }

    // --- generateRev function ---

    @Test
    fun generateRev_producesExpectedFormat() {
        val rev = generateRev(1, "hello world")
        assertTrue(rev.startsWith("1-"))
        // MD5 hash is 32 hex chars
        assertEquals(34, rev.length) // "1-" + 32 hex chars
    }

    @Test
    fun generateRev_knownMd5_producesCorrectHash() {
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        val rev = generateRev(1, "")
        assertEquals("1-d41d8cd98f00b204e9800998ecf8427e", rev)
    }

    @Test
    fun generateRev_sameContentSameGeneration_producesSameRev() {
        val rev1 = generateRev(3, "test content")
        val rev2 = generateRev(3, "test content")
        assertEquals(rev1, rev2)
    }

    @Test
    fun generateRev_differentGeneration_producesDifferentPrefix() {
        val rev1 = generateRev(1, "content")
        val rev2 = generateRev(2, "content")
        assertTrue(rev1.startsWith("1-"))
        assertTrue(rev2.startsWith("2-"))
        // Same content but different generation means different rev
        assertTrue(rev1 != rev2)
    }

    @Test
    fun generateRev_differentContent_producesDifferentHash() {
        val rev1 = generateRev(1, "content A")
        val rev2 = generateRev(1, "content B")
        assertTrue(rev1 != rev2)
    }

    // --- md5Hex verification ---

    @Test
    fun md5Hex_emptyString_producesCorrectHash() {
        val hash = md5Hex("".encodeToByteArray())
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hash)
    }

    @Test
    fun md5Hex_helloWorld_producesCorrectHash() {
        // MD5("Hello, World!") = 65a8e27d8879283831b664bd8b7f0ad4
        val hash = md5Hex("Hello, World!".encodeToByteArray())
        assertEquals("65a8e27d8879283831b664bd8b7f0ad4", hash)
    }

    @Test
    fun md5Hex_knownVector_producesCorrectHash() {
        // MD5("The quick brown fox jumps over the lazy dog") = 9e107d9d372bb6826bd81d3542a419d6
        val hash = md5Hex("The quick brown fox jumps over the lazy dog".encodeToByteArray())
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", hash)
    }

    // --- ResolutionStrategy enum ---

    @Test
    fun resolutionStrategy_allValues_present() {
        val strategies = ResolutionStrategy.entries
        assertEquals(4, strategies.size)
        assertTrue(strategies.contains(ResolutionStrategy.LAST_WRITE_WINS))
        assertTrue(strategies.contains(ResolutionStrategy.CONCATENATE))
        assertTrue(strategies.contains(ResolutionStrategy.UNION_MERGE))
        assertTrue(strategies.contains(ResolutionStrategy.LONGEST_CONTENT))
    }
}
