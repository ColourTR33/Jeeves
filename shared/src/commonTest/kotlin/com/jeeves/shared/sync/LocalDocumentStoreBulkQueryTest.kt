package com.jeeves.shared.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * Unit tests for LocalDocumentStore bulk and query operations:
 * bulkDocs, changesSince, allDocs.
 *
 * **Validates: Requirements 2.1, 3.4**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDocumentStoreBulkQueryTest {

    private lateinit var tempDir: File
    private lateinit var store: LocalDocumentStore

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("localDocStoreBulkTest").toFile()
        store = LocalDocumentStore(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- bulkDocs(newEdits = true) tests ---

    @Test
    fun bulkDocs_newEditsTrue_writesMultipleDocuments() = runTest {
        val docs = listOf(
            createTestDoc("recording:bulk1"),
            createTestDoc("recording:bulk2"),
            createTestDoc("recording:bulk3")
        )

        val results = store.bulkDocs(docs, newEdits = true)

        assertEquals(3, results.size)
        results.forEach { result ->
            assertTrue(result.ok)
            assertNotNull(result.rev)
            assertNull(result.error)
        }

        // Verify documents are retrievable
        assertNotNull(store.get("recording:bulk1"))
        assertNotNull(store.get("recording:bulk2"))
        assertNotNull(store.get("recording:bulk3"))
    }

    @Test
    fun bulkDocs_newEditsTrue_generatesNewRevisions() = runTest {
        val docs = listOf(createTestDoc("recording:rev1"))
        val results = store.bulkDocs(docs, newEdits = true)

        assertEquals(1, results.size)
        assertTrue(results[0].rev!!.startsWith("1-"))
    }

    @Test
    fun bulkDocs_newEditsTrue_individualFailureDoesNotAbortBatch() = runTest {
        // Write a document first
        store.put(createTestDoc("recording:existing1"))

        // Try bulk write with one conflicting doc and one new doc
        val docs = listOf(
            createTestDoc("recording:existing1").copy(rev = "99-fakehash"), // Will conflict
            createTestDoc("recording:new1") // Should succeed
        )

        val results = store.bulkDocs(docs, newEdits = true)

        assertEquals(2, results.size)
        // First should fail
        assertFalse(results[0].ok)
        assertEquals("conflict", results[0].error)
        // Second should succeed
        assertTrue(results[1].ok)
        assertNotNull(store.get("recording:new1"))
    }

    @Test
    fun bulkDocs_newEditsTrue_incrementsSequenceCounter() = runTest {
        val initialSeq = store.getSequenceCounter()
        val docs = listOf(
            createTestDoc("recording:seq1"),
            createTestDoc("recording:seq2")
        )

        store.bulkDocs(docs, newEdits = true)

        assertEquals(initialSeq + 2, store.getSequenceCounter())
    }

    @Test
    fun bulkDocs_newEditsTrue_emitsChangeEvents() = runTest {
        val changes = mutableListOf<DocumentChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.changes.collect { changes.add(it) }
        }

        val docs = listOf(
            createTestDoc("recording:emit1"),
            createTestDoc("recording:emit2")
        )

        store.bulkDocs(docs, newEdits = true)

        assertEquals(2, changes.size)
        assertEquals("recording:emit1", changes[0].id)
        assertEquals("recording:emit2", changes[1].id)
        job.cancel()
    }

    // --- bulkDocs(newEdits = false) tests ---

    @Test
    fun bulkDocs_newEditsFalse_preservesExistingRev() = runTest {
        val docs = listOf(
            createTestDoc("recording:rep1").copy(rev = "5-abc123def456")
        )

        val results = store.bulkDocs(docs, newEdits = false)

        assertEquals(1, results.size)
        assertTrue(results[0].ok)
        assertEquals("5-abc123def456", results[0].rev)

        // Verify the stored document has the original rev
        val retrieved = store.get("recording:rep1")
        assertNotNull(retrieved)
        assertEquals("5-abc123def456", retrieved.rev)
    }

    @Test
    fun bulkDocs_newEditsFalse_writesMultipleDocuments() = runTest {
        val docs = listOf(
            createTestDoc("recording:rep1").copy(rev = "2-aaa"),
            createTestDoc("transcription:rep2").copy(rev = "3-bbb"),
            createTestDoc("summary:rep3").copy(rev = "1-ccc")
        )

        val results = store.bulkDocs(docs, newEdits = false)

        assertEquals(3, results.size)
        results.forEach { assertTrue(it.ok) }

        assertNotNull(store.get("recording:rep1"))
        assertNotNull(store.get("transcription:rep2"))
        assertNotNull(store.get("summary:rep3"))
    }

    @Test
    fun bulkDocs_newEditsFalse_updatesGenerationMap() = runTest {
        val docs = listOf(
            createTestDoc("recording:gen1").copy(rev = "5-abc123")
        )
        store.bulkDocs(docs, newEdits = false)

        // Now if we do a normal put on the same doc, it should increment from 5
        val retrieved = store.get("recording:gen1")!!
        val updated = store.put(retrieved.copy(body = buildJsonObject { put("title", "Updated") }))
        assertTrue(updated.rev!!.startsWith("6-"), "Expected gen 6, got ${updated.rev}")
    }

    @Test
    fun bulkDocs_newEditsFalse_incrementsSequenceCounter() = runTest {
        val initialSeq = store.getSequenceCounter()
        val docs = listOf(
            createTestDoc("recording:seq1").copy(rev = "1-aaa"),
            createTestDoc("recording:seq2").copy(rev = "1-bbb")
        )

        store.bulkDocs(docs, newEdits = false)

        assertEquals(initialSeq + 2, store.getSequenceCounter())
    }

    @Test
    fun bulkDocs_newEditsFalse_handlesDeletedDocuments() = runTest {
        val docs = listOf(
            createTestDoc("recording:del1").copy(rev = "2-aaa", deleted = true)
        )

        val results = store.bulkDocs(docs, newEdits = false)

        assertTrue(results[0].ok)
        // get() returns null for deleted documents
        assertNull(store.get("recording:del1"))
    }

    @Test
    fun bulkDocs_newEditsFalse_emitsChangeEvents() = runTest {
        val changes = mutableListOf<DocumentChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.changes.collect { changes.add(it) }
        }

        val docs = listOf(
            createTestDoc("recording:emit1").copy(rev = "1-aaa"),
            createTestDoc("recording:emit2").copy(rev = "1-bbb", deleted = true)
        )

        store.bulkDocs(docs, newEdits = false)

        assertEquals(2, changes.size)
        assertEquals("recording:emit1", changes[0].id)
        assertFalse(changes[0].deleted)
        assertEquals("recording:emit2", changes[1].id)
        assertTrue(changes[1].deleted)
        job.cancel()
    }

    @Test
    fun bulkDocs_emptyList_returnsEmptyResults() = runTest {
        val results = store.bulkDocs(emptyList(), newEdits = true)
        assertTrue(results.isEmpty())

        val results2 = store.bulkDocs(emptyList(), newEdits = false)
        assertTrue(results2.isEmpty())
    }

    // --- changesSince tests ---

    @Test
    fun changesSince_noChanges_returnsEmpty() = runTest {
        val response = store.changesSince("0")

        assertTrue(response.results.isEmpty())
        assertEquals("0", response.lastSeq)
    }

    @Test
    fun changesSince_fromZero_returnsAllChanges() = runTest {
        store.put(createTestDoc("recording:ch1"))
        store.put(createTestDoc("recording:ch2"))
        store.put(createTestDoc("recording:ch3"))

        val response = store.changesSince("0")

        assertEquals(3, response.results.size)
        assertEquals("recording:ch1", response.results[0].id)
        assertEquals("recording:ch2", response.results[1].id)
        assertEquals("recording:ch3", response.results[2].id)
        assertEquals("3", response.lastSeq)
    }

    @Test
    fun changesSince_fromMiddle_returnsSubsequentChanges() = runTest {
        store.put(createTestDoc("recording:ch1"))
        store.put(createTestDoc("recording:ch2"))
        store.put(createTestDoc("recording:ch3"))

        val response = store.changesSince("1")

        assertEquals(2, response.results.size)
        assertEquals("recording:ch2", response.results[0].id)
        assertEquals("recording:ch3", response.results[1].id)
        assertEquals("3", response.lastSeq)
    }

    @Test
    fun changesSince_withLimit_respectsLimit() = runTest {
        store.put(createTestDoc("recording:ch1"))
        store.put(createTestDoc("recording:ch2"))
        store.put(createTestDoc("recording:ch3"))
        store.put(createTestDoc("recording:ch4"))

        val response = store.changesSince("0", limit = 2)

        assertEquals(2, response.results.size)
        assertEquals("recording:ch1", response.results[0].id)
        assertEquals("recording:ch2", response.results[1].id)
        assertEquals("2", response.lastSeq)
    }

    @Test
    fun changesSince_includesDeletedChanges() = runTest {
        val doc = store.put(createTestDoc("recording:del1"))
        store.delete("recording:del1", doc.rev!!)

        val response = store.changesSince("0")

        assertEquals(2, response.results.size)
        assertFalse(response.results[0].deleted) // put
        assertTrue(response.results[1].deleted)   // delete
    }

    @Test
    fun changesSince_afterBulkDocs_includesBulkChanges() = runTest {
        store.put(createTestDoc("recording:pre1"))

        val bulkDocs = listOf(
            createTestDoc("recording:bulk1").copy(rev = "1-aaa"),
            createTestDoc("recording:bulk2").copy(rev = "1-bbb")
        )
        store.bulkDocs(bulkDocs, newEdits = false)

        val response = store.changesSince("1") // After the first put

        assertEquals(2, response.results.size)
        assertEquals("recording:bulk1", response.results[0].id)
        assertEquals("recording:bulk2", response.results[1].id)
    }

    @Test
    fun changesSince_beyondCurrentSeq_returnsEmpty() = runTest {
        store.put(createTestDoc("recording:ch1"))

        val response = store.changesSince("999")

        assertTrue(response.results.isEmpty())
        assertEquals("999", response.lastSeq)
    }

    @Test
    fun changesSince_persistsAcrossInstances() = runTest {
        store.put(createTestDoc("recording:persist1"))
        store.put(createTestDoc("recording:persist2"))

        // Create a new store instance
        val store2 = LocalDocumentStore(tempDir)
        val response = store2.changesSince("0")

        assertEquals(2, response.results.size)
        assertEquals("recording:persist1", response.results[0].id)
        assertEquals("recording:persist2", response.results[1].id)
    }

    @Test
    fun changesSince_invalidSince_treatsAsZero() = runTest {
        store.put(createTestDoc("recording:ch1"))

        val response = store.changesSince("invalid")

        assertEquals(1, response.results.size)
    }

    // --- allDocs tests ---

    @Test
    fun allDocs_emptyStore_returnsEmpty() = runTest {
        val result = store.allDocs("recording:")
        assertTrue(result.isEmpty())
    }

    @Test
    fun allDocs_matchingPrefix_returnsDocuments() = runTest {
        store.put(createTestDoc("recording:r1"))
        store.put(createTestDoc("recording:r2"))
        store.put(createTestDoc("transcription:t1"))

        val recordings = store.allDocs("recording:")

        assertEquals(2, recordings.size)
        assertTrue(recordings.all { it.id.startsWith("recording:") })
    }

    @Test
    fun allDocs_noMatchingPrefix_returnsEmpty() = runTest {
        store.put(createTestDoc("recording:r1"))

        val result = store.allDocs("summary:")
        assertTrue(result.isEmpty())
    }

    @Test
    fun allDocs_excludesDeletedDocuments() = runTest {
        val doc = store.put(createTestDoc("recording:del1"))
        store.put(createTestDoc("recording:keep1"))
        store.delete("recording:del1", doc.rev!!)

        val result = store.allDocs("recording:")

        assertEquals(1, result.size)
        assertEquals("recording:keep1", result[0].id)
    }

    @Test
    fun allDocs_multipleTypes_onlyReturnsMatchingPrefix() = runTest {
        store.put(createTestDoc("recording:r1"))
        store.put(createTestDoc("recording:r2"))
        store.put(createTestDoc("transcription:t1"))
        store.put(createTestDoc("summary:s1"))

        val recordings = store.allDocs("recording:")
        assertEquals(2, recordings.size)

        val transcriptions = store.allDocs("transcription:")
        assertEquals(1, transcriptions.size)

        val summaries = store.allDocs("summary:")
        assertEquals(1, summaries.size)
    }

    @Test
    fun allDocs_returnedDocsHaveCorrectContent() = runTest {
        val body = buildJsonObject {
            put("id", "content1")
            put("filePath", "/tmp/content1.wav")
            put("durationMs", 60000)
            put("createdAt", System.currentTimeMillis())
            put("title", "My Recording")
        }
        store.put(createTestDoc("recording:content1", body = body))

        val result = store.allDocs("recording:")

        assertEquals(1, result.size)
        assertEquals("recording:content1", result[0].id)
        assertEquals(body, result[0].body)
        assertEquals("recording", result[0].type)
    }

    @Test
    fun allDocs_replicatedDocs_included() = runTest {
        // Write via bulkDocs with newEdits=false (replication mode)
        val docs = listOf(
            createTestDoc("recording:rep1").copy(rev = "3-abc"),
            createTestDoc("recording:rep2").copy(rev = "1-def")
        )
        store.bulkDocs(docs, newEdits = false)

        val result = store.allDocs("recording:")

        assertEquals(2, result.size)
    }

    // --- Helper ---

    private fun createTestDoc(
        id: String,
        body: kotlinx.serialization.json.JsonObject = defaultBodyForType(id.substringBefore(":"))
    ): CouchDocument {
        val type = id.substringBefore(":")
        return CouchDocument(
            id = id,
            type = type,
            deviceId = "test-device",
            modifiedAt = System.currentTimeMillis(),
            body = body
        )
    }

    /**
     * Creates a valid body for the given document type, passing schema validation.
     */
    private fun defaultBodyForType(type: String): kotlinx.serialization.json.JsonObject {
        return when (type) {
            "recording" -> buildJsonObject {
                put("id", "test-id")
                put("filePath", "/tmp/test.wav")
                put("durationMs", 60000)
                put("createdAt", System.currentTimeMillis())
                put("title", "Test Recording")
            }
            "transcription" -> buildJsonObject {
                put("recordingId", "test-id")
                put("text", "Test transcription text")
            }
            "summary" -> buildJsonObject {
                put("recordingId", "test-id")
                put("summary", "Test summary text")
            }
            else -> buildJsonObject {
                put("title", "Test")
            }
        }
    }
}
