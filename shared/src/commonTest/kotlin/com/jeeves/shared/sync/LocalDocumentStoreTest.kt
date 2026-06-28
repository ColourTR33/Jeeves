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
 * Unit tests for LocalDocumentStore core operations: get, put, delete.
 *
 * **Validates: Requirements 2.1, 2.6, 7.7**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDocumentStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: LocalDocumentStore

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("localDocStoreTest").toFile()
        store = LocalDocumentStore(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- get() tests ---

    @Test
    fun get_nonExistentDocument_returnsNull() = runTest {
        val result = store.get("recording:nonexistent")
        assertNull(result)
    }

    @Test
    fun get_existingDocument_returnsDocument() = runTest {
        val doc = createTestDoc("recording:test1")
        store.put(doc)

        val retrieved = store.get("recording:test1")
        assertNotNull(retrieved)
        assertEquals("recording:test1", retrieved.id)
        assertEquals("recording", retrieved.type)
    }

    @Test
    fun get_deletedDocument_returnsNull() = runTest {
        val doc = createTestDoc("recording:del1")
        val saved = store.put(doc)
        store.delete("recording:del1", saved.rev!!)

        val result = store.get("recording:del1")
        assertNull(result)
    }

    // --- put() tests ---

    @Test
    fun put_newDocument_assignsRev() = runTest {
        val doc = createTestDoc("recording:new1")
        val saved = store.put(doc)

        assertNotNull(saved.rev)
        assertTrue(saved.rev!!.startsWith("1-"))
    }

    @Test
    fun put_existingDocument_incrementsGeneration() = runTest {
        val doc = createTestDoc("recording:inc1")
        val first = store.put(doc)
        assertTrue(first.rev!!.startsWith("1-"))

        val updated = first.copy(body = buildJsonObject { put("title", "Updated") })
        val second = store.put(updated)
        assertTrue(second.rev!!.startsWith("2-"))
    }

    @Test
    fun put_multiplePuts_allRevsDistinct() = runTest {
        val doc = createTestDoc("recording:multi1")
        val revs = mutableSetOf<String>()

        var current = store.put(doc)
        revs.add(current.rev!!)

        for (i in 2..5) {
            current = store.put(current.copy(body = buildJsonObject { put("iteration", i) }))
            revs.add(current.rev!!)
        }

        assertEquals(5, revs.size, "All revisions should be distinct")
    }

    @Test
    fun put_conflictingRev_throwsException() = runTest {
        val doc = createTestDoc("recording:conflict1")
        val saved = store.put(doc)

        // Try to put with a wrong rev
        val wrongRev = saved.copy(rev = "99-fakehash")
        assertFailsWith<IllegalStateException> {
            store.put(wrongRev)
        }
    }

    @Test
    fun put_atomicWrite_createsFileInTypeDirectory() = runTest {
        val doc = createTestDoc("transcription:abc123")
        store.put(doc)

        val typeDir = File(tempDir, "transcription")
        assertTrue(typeDir.exists())
        assertTrue(typeDir.isDirectory)

        val docFile = File(typeDir, "transcription_abc123.json")
        assertTrue(docFile.exists())
    }

    @Test
    fun put_noTmpFileRemains() = runTest {
        val doc = createTestDoc("recording:tmp1")
        store.put(doc)

        val typeDir = File(tempDir, "recording")
        val tmpFiles = typeDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(tmpFiles.isEmpty(), "No .tmp files should remain after successful write")
    }

    @Test
    fun put_emitsDocumentChangeEvent() = runTest {
        val doc = createTestDoc("recording:emit1")

        val changes = mutableListOf<DocumentChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.changes.collect { changes.add(it) }
        }

        store.put(doc)

        assertEquals(1, changes.size)
        assertEquals("recording:emit1", changes[0].id)
        assertFalse(changes[0].deleted)
        job.cancel()
    }

    @Test
    fun put_preservesBodyContent() = runTest {
        val body = buildJsonObject {
            put("title", "Sprint Planning")
            put("durationMs", 3600000)
        }
        val doc = createTestDoc("recording:body1", body = body)
        store.put(doc)

        val retrieved = store.get("recording:body1")
        assertNotNull(retrieved)
        assertEquals(body, retrieved.body)
    }

    @Test
    fun put_differentTypes_organizedInSeparateDirectories() = runTest {
        store.put(createTestDoc("recording:r1"))
        store.put(createTestDoc("transcription:t1"))
        store.put(createTestDoc("summary:s1"))

        assertTrue(File(tempDir, "recording").exists())
        assertTrue(File(tempDir, "transcription").exists())
        assertTrue(File(tempDir, "summary").exists())
    }

    // --- delete() tests ---

    @Test
    fun delete_existingDocument_createsTombstone() = runTest {
        val doc = createTestDoc("recording:todel1")
        val saved = store.put(doc)

        val tombstone = store.delete("recording:todel1", saved.rev!!)
        assertTrue(tombstone.deleted)
        assertNotNull(tombstone.rev)
        assertTrue(tombstone.rev!! != saved.rev)
    }

    @Test
    fun delete_nonExistentDocument_throwsException() = runTest {
        assertFailsWith<IllegalStateException> {
            store.delete("recording:noexist", "1-abc")
        }
    }

    @Test
    fun delete_wrongRev_throwsException() = runTest {
        val doc = createTestDoc("recording:wrongrev1")
        store.put(doc)

        assertFailsWith<IllegalStateException> {
            store.delete("recording:wrongrev1", "99-wronghash")
        }
    }

    @Test
    fun delete_emitsDocumentChangeEvent() = runTest {
        val doc = createTestDoc("recording:delemit1")
        val saved = store.put(doc)

        val changes = mutableListOf<DocumentChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.changes.collect { changes.add(it) }
        }

        store.delete("recording:delemit1", saved.rev!!)

        assertEquals(1, changes.size)
        assertEquals("recording:delemit1", changes[0].id)
        assertTrue(changes[0].deleted)
        job.cancel()
    }

    @Test
    fun delete_incrementsGeneration() = runTest {
        val doc = createTestDoc("recording:delgen1")
        val saved = store.put(doc)
        assertTrue(saved.rev!!.startsWith("1-"))

        val tombstone = store.delete("recording:delgen1", saved.rev!!)
        assertTrue(tombstone.rev!!.startsWith("2-"))
    }

    // --- Sequence counter tests ---

    @Test
    fun put_incrementsSequenceCounter() = runTest {
        val initialSeq = store.getSequenceCounter()
        store.put(createTestDoc("recording:seq1"))
        assertEquals(initialSeq + 1, store.getSequenceCounter())

        store.put(createTestDoc("recording:seq2"))
        assertEquals(initialSeq + 2, store.getSequenceCounter())
    }

    @Test
    fun delete_incrementsSequenceCounter() = runTest {
        val doc = store.put(createTestDoc("recording:seqdel1"))
        val seqAfterPut = store.getSequenceCounter()

        store.delete("recording:seqdel1", doc.rev!!)
        assertEquals(seqAfterPut + 1, store.getSequenceCounter())
    }

    @Test
    fun sequenceCounter_persistsAcrossInstances() = runTest {
        store.put(createTestDoc("recording:persist1"))
        store.put(createTestDoc("recording:persist2"))
        val seq = store.getSequenceCounter()

        // Create a new store instance pointing to the same directory
        val store2 = LocalDocumentStore(tempDir)
        assertEquals(seq, store2.getSequenceCounter())
    }

    // --- Persistence tests ---

    @Test
    fun put_documentPersistsAcrossInstances() = runTest {
        val doc = createTestDoc("recording:persist3")
        val saved = store.put(doc)

        // Create a new store instance pointing to the same directory
        val store2 = LocalDocumentStore(tempDir)
        val retrieved = store2.get("recording:persist3")
        assertNotNull(retrieved)
        assertEquals(saved.rev, retrieved.rev)
    }

    @Test
    fun generationCounter_persistsAcrossInstances() = runTest {
        val doc = createTestDoc("recording:genpers1")
        val first = store.put(doc)
        assertTrue(first.rev!!.startsWith("1-"))

        // Create a new store instance
        val store2 = LocalDocumentStore(tempDir)
        val updated = first.copy(body = buildJsonObject { put("title", "Updated") })
        val second = store2.put(updated)
        assertTrue(second.rev!!.startsWith("2-"), "Generation should persist: got ${second.rev}")
    }

    // --- Migration marker tests ---

    @Test
    fun hasMigrationCompleted_initiallyFalse() = runTest {
        assertFalse(store.hasMigrationCompleted())
    }

    @Test
    fun markMigrationComplete_thenHasMigrationCompletedReturnsTrue() = runTest {
        store.markMigrationComplete()
        assertTrue(store.hasMigrationCompleted())
    }

    // --- Helper ---

    private fun createTestDoc(
        id: String,
        body: kotlinx.serialization.json.JsonObject = buildJsonObject { put("title", "Test") }
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
}
