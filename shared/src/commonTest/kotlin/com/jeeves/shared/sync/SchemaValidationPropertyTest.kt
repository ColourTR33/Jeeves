package com.jeeves.shared.sync

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Feature: multi-device-sync, Property 8: Schema validation rejects malformed documents

/**
 * Property-based tests for schema validation rejection.
 *
 * For any JSON object that is missing a required field or contains a field of incorrect type
 * relative to the Recording, TranscriptionResult, or SummaryResult schemas, the
 * LocalDocumentStore SHALL reject it during replication write and not persist it locally.
 *
 * **Validates: Requirements 7.5**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class SchemaValidationPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbDocId: Arb<String> = Arb.string(1..20)
        .filter { it.isNotBlank() && !it.contains(':') && !it.contains('/') }

    private val arbDeviceId: Arb<String> = Arb.string(5..30)
        .filter { it.isNotBlank() }

    private val arbTimestamp: Arb<Long> = Arb.long(1000000000000L..1800000000000L)

    /** Generator for a wrong-type value (not a string) to use where a string is expected */
    private val arbNonStringValue: Arb<JsonElement> = Arb.choice(
        Arb.int().map { JsonPrimitive(it) },
        Arb.boolean().map { JsonPrimitive(it) },
        Arb.constant(JsonNull),
        Arb.constant(JsonArray(listOf(JsonPrimitive("item")))),
        Arb.constant(JsonObject(mapOf("nested" to JsonPrimitive("obj"))))
    )

    /** Generator for a wrong-type value (not a number) to use where a number is expected */
    private val arbNonNumberValue: Arb<JsonElement> = Arb.choice(
        Arb.string(1..20).map { JsonPrimitive(it) },
        Arb.boolean().map { JsonPrimitive(it) },
        Arb.constant(JsonNull),
        Arb.constant(JsonArray(listOf(JsonPrimitive(42)))),
        Arb.constant(JsonObject(mapOf("nested" to JsonPrimitive(1))))
    )

    // --- Recording schema: required fields are id (String), filePath (String), durationMs (Number), createdAt (Number) ---

    /** Required fields for a valid recording body */
    private val recordingRequiredFields = listOf("id", "filePath", "durationMs", "createdAt")
    private val recordingStringFields = listOf("id", "filePath")
    private val recordingNumberFields = listOf("durationMs", "createdAt")

    /** Generate a malformed recording body by omitting one required field */
    private val arbMalformedRecordingMissingField: Arb<JsonObject> = Arb.bind(
        arbDocId,
        Arb.string(1..30),
        Arb.long(0L..86400000L),
        arbTimestamp,
        Arb.element(recordingRequiredFields)
    ) { id, filePath, durationMs, createdAt, fieldToOmit ->
        val allFields = mapOf(
            "id" to JsonPrimitive(id),
            "filePath" to JsonPrimitive(filePath),
            "durationMs" to JsonPrimitive(durationMs),
            "createdAt" to JsonPrimitive(createdAt)
        )
        JsonObject(allFields.filterKeys { it != fieldToOmit })
    }

    /** Generate a malformed recording body by replacing a string field with a non-string value */
    private val arbMalformedRecordingWrongTypeString: Arb<Pair<JsonObject, String>> = Arb.bind(
        arbDocId,
        Arb.string(1..30),
        Arb.long(0L..86400000L),
        arbTimestamp,
        Arb.element(recordingStringFields),
        arbNonStringValue
    ) { id, filePath, durationMs, createdAt, fieldToBreak, wrongValue ->
        val allFields = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "filePath" to JsonPrimitive(filePath),
            "durationMs" to JsonPrimitive(durationMs),
            "createdAt" to JsonPrimitive(createdAt)
        )
        allFields[fieldToBreak] = wrongValue
        JsonObject(allFields) to fieldToBreak
    }

    /** Generate a malformed recording body by replacing a number field with a non-number value */
    private val arbMalformedRecordingWrongTypeNumber: Arb<Pair<JsonObject, String>> = Arb.bind(
        arbDocId,
        Arb.string(1..30),
        Arb.long(0L..86400000L),
        arbTimestamp,
        Arb.element(recordingNumberFields),
        arbNonNumberValue
    ) { id, filePath, durationMs, createdAt, fieldToBreak, wrongValue ->
        val allFields = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "filePath" to JsonPrimitive(filePath),
            "durationMs" to JsonPrimitive(durationMs),
            "createdAt" to JsonPrimitive(createdAt)
        )
        allFields[fieldToBreak] = wrongValue
        JsonObject(allFields) to fieldToBreak
    }

    // --- Transcription schema: required fields are recordingId (String), text (String) ---

    private val transcriptionRequiredFields = listOf("recordingId", "text")

    /** Generate a malformed transcription body by omitting one required field */
    private val arbMalformedTranscriptionMissingField: Arb<JsonObject> = Arb.bind(
        arbDocId,
        Arb.string(0..200),
        Arb.element(transcriptionRequiredFields)
    ) { recordingId, text, fieldToOmit ->
        val allFields = mapOf(
            "recordingId" to JsonPrimitive(recordingId),
            "text" to JsonPrimitive(text)
        )
        JsonObject(allFields.filterKeys { it != fieldToOmit })
    }

    /** Generate a malformed transcription body with wrong type on a string field */
    private val arbMalformedTranscriptionWrongType: Arb<Pair<JsonObject, String>> = Arb.bind(
        arbDocId,
        Arb.string(0..200),
        Arb.element(transcriptionRequiredFields),
        arbNonStringValue
    ) { recordingId, text, fieldToBreak, wrongValue ->
        val allFields = mutableMapOf<String, JsonElement>(
            "recordingId" to JsonPrimitive(recordingId),
            "text" to JsonPrimitive(text)
        )
        allFields[fieldToBreak] = wrongValue
        JsonObject(allFields) to fieldToBreak
    }

    // --- Summary schema: required fields are recordingId (String), summary (String) ---

    private val summaryRequiredFields = listOf("recordingId", "summary")

    /** Generate a malformed summary body by omitting one required field */
    private val arbMalformedSummaryMissingField: Arb<JsonObject> = Arb.bind(
        arbDocId,
        Arb.string(0..200),
        Arb.element(summaryRequiredFields)
    ) { recordingId, summary, fieldToOmit ->
        val allFields = mapOf(
            "recordingId" to JsonPrimitive(recordingId),
            "summary" to JsonPrimitive(summary)
        )
        JsonObject(allFields.filterKeys { it != fieldToOmit })
    }

    /** Generate a malformed summary body with wrong type on a string field */
    private val arbMalformedSummaryWrongType: Arb<Pair<JsonObject, String>> = Arb.bind(
        arbDocId,
        Arb.string(0..200),
        Arb.element(summaryRequiredFields),
        arbNonStringValue
    ) { recordingId, summary, fieldToBreak, wrongValue ->
        val allFields = mutableMapOf<String, JsonElement>(
            "recordingId" to JsonPrimitive(recordingId),
            "summary" to JsonPrimitive(summary)
        )
        allFields[fieldToBreak] = wrongValue
        JsonObject(allFields) to fieldToBreak
    }

    // =========================================================================
    // Property Tests
    // =========================================================================

    /**
     * Property 8: Recording documents with missing required fields are rejected
     *
     * For any recording document body missing one of the required fields (id, filePath,
     * durationMs, createdAt), bulkDocs(newEdits=false) SHALL reject it (ok=false)
     * and the document SHALL NOT be persisted.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_recording_missingRequiredField_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedRecordingMissingField, arbDocId, arbDeviceId, arbTimestamp) {
            malformedBody, docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "recording:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-abc123",
                    type = "recording",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size, "Expected exactly 1 result from bulkDocs")
                assertEquals(
                    false, results[0].ok,
                    "Malformed recording (missing field) should be rejected. Body: $malformedBody"
                )

                // Verify document was not persisted
                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected document should not be persisted in store. DocId: $docId"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 8: Recording documents with wrong string field type are rejected
     *
     * For any recording document body where a string field (id, filePath) has an incorrect
     * type (number, boolean, null, array, object), bulkDocs(newEdits=false) SHALL reject it.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_recording_wrongStringFieldType_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedRecordingWrongTypeString, arbDocId, arbDeviceId, arbTimestamp) {
            (malformedBody, brokenField), docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "recording:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-def456",
                    type = "recording",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size)
                assertEquals(
                    false, results[0].ok,
                    "Recording with wrong type for '$brokenField' should be rejected. Body: $malformedBody"
                )

                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected document should not be persisted. DocId: $docId, broken field: $brokenField"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 8: Recording documents with wrong number field type are rejected
     *
     * For any recording document body where a number field (durationMs, createdAt) has an
     * incorrect type (string, boolean, null, array, object), bulkDocs(newEdits=false) SHALL reject it.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_recording_wrongNumberFieldType_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedRecordingWrongTypeNumber, arbDocId, arbDeviceId, arbTimestamp) {
            (malformedBody, brokenField), docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "recording:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-ghi789",
                    type = "recording",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size)
                assertEquals(
                    false, results[0].ok,
                    "Recording with wrong type for '$brokenField' should be rejected. Body: $malformedBody"
                )

                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected document should not be persisted. DocId: $docId, broken field: $brokenField"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 8: Transcription documents with missing required fields are rejected
     *
     * For any transcription document body missing one of the required fields
     * (recordingId, text), bulkDocs(newEdits=false) SHALL reject it.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_transcription_missingRequiredField_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedTranscriptionMissingField, arbDocId, arbDeviceId, arbTimestamp) {
            malformedBody, docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "transcription:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-trans123",
                    type = "transcription",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size)
                assertEquals(
                    false, results[0].ok,
                    "Malformed transcription (missing field) should be rejected. Body: $malformedBody"
                )

                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected transcription should not be persisted. DocId: $docId"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 8: Transcription documents with wrong field type are rejected
     *
     * For any transcription document body where a string field (recordingId, text) has
     * an incorrect type, bulkDocs(newEdits=false) SHALL reject it.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_transcription_wrongFieldType_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedTranscriptionWrongType, arbDocId, arbDeviceId, arbTimestamp) {
            (malformedBody, brokenField), docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "transcription:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-trans456",
                    type = "transcription",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size)
                assertEquals(
                    false, results[0].ok,
                    "Transcription with wrong type for '$brokenField' should be rejected. Body: $malformedBody"
                )

                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected transcription should not be persisted. DocId: $docId, broken field: $brokenField"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 8: Summary documents with missing required fields are rejected
     *
     * For any summary document body missing one of the required fields
     * (recordingId, summary), bulkDocs(newEdits=false) SHALL reject it.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_summary_missingRequiredField_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedSummaryMissingField, arbDocId, arbDeviceId, arbTimestamp) {
            malformedBody, docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "summary:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-sum123",
                    type = "summary",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size)
                assertEquals(
                    false, results[0].ok,
                    "Malformed summary (missing field) should be rejected. Body: $malformedBody"
                )

                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected summary should not be persisted. DocId: $docId"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 8: Summary documents with wrong field type are rejected
     *
     * For any summary document body where a string field (recordingId, summary) has
     * an incorrect type, bulkDocs(newEdits=false) SHALL reject it.
     *
     * **Validates: Requirements 7.5**
     */
    @Test
    fun property8_summary_wrongFieldType_isRejectedAndNotPersisted() = runTest {
        checkAll(config, arbMalformedSummaryWrongType, arbDocId, arbDeviceId, arbTimestamp) {
            (malformedBody, brokenField), docIdSuffix, deviceId, modifiedAt ->

            val tempDir = createTempDirectory("schema-validation-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val docId = "summary:$docIdSuffix"

                val doc = CouchDocument(
                    id = docId,
                    rev = "1-sum456",
                    type = "summary",
                    deviceId = deviceId,
                    modifiedAt = modifiedAt,
                    body = malformedBody
                )

                val results = store.bulkDocs(listOf(doc), newEdits = false)

                assertEquals(1, results.size)
                assertEquals(
                    false, results[0].ok,
                    "Summary with wrong type for '$brokenField' should be rejected. Body: $malformedBody"
                )

                val persisted = store.get(docId)
                assertNull(
                    persisted,
                    "Rejected summary should not be persisted. DocId: $docId, broken field: $brokenField"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
