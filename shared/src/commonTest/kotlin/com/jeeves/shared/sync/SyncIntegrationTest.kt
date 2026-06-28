package com.jeeves.shared.sync

import com.jeeves.shared.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/**
 * Integration tests for end-to-end sync flow.
 *
 * Tests the interaction between CouchDbReplicator, DefaultSyncEngine,
 * ConflictResolver, and LocalDocumentStore working together as a system.
 * Uses ktor-client-mock for HTTP mocking (no real CouchDB needed).
 *
 * **Validates: Requirements 3.1, 3.2, 3.3, 5.1**
 */
class SyncIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var tempDir: File

    private val validConfig = SyncConfiguration(
        remoteUrl = "https://couch.example.com/jeeves",
        username = "admin",
        encryptedPassword = "secret123"
    )

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "sync_integration_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. Full push/pull cycle (Requirements 3.2, 3.3)
    // =========================================================================

    @Test
    fun fullPushPullCycle_localDocumentPushedAndRemoteDocumentPulled() = runTest {
        val localStoreDir = File(tempDir, "local")
        val localStore = LocalDocumentStore(localStoreDir)
        val conflictResolver = ConflictResolver("device-a")

        // --- Phase 1: Write a document locally ---
        val localDoc = CouchDocument(
            id = "recording:local1",
            type = "recording",
            deviceId = "device-a",
            modifiedAt = 1700000000000L,
            body = buildRecordingBody("local1", "Local Recording", "Notes from device A")
        )
        val savedDoc = localStore.put(localDoc)
        assertNotNull(savedDoc.rev, "Saved doc should have a revision")

        // --- Phase 2: Push to mock remote ---
        // The mock server accepts the bulk_docs push and returns success
        var pushReceived = false
        val remoteDoc = CouchDocument(
            id = "recording:remote1",
            rev = "1-remotehash",
            type = "recording",
            deviceId = "device-b",
            modifiedAt = 1700000050000L,
            body = buildRecordingBody("remote1", "Remote Recording", "Notes from device B")
        )

        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("_bulk_docs") -> {
                    pushReceived = true
                    respond(
                        content = """[{"ok":true,"id":"recording:local1","rev":"1-abc"}]""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                url.contains("_changes") -> {
                    // Simulate remote having a new document
                    val changesJson = buildJsonObject {
                        put("results", buildJsonArray {
                            add(buildJsonObject {
                                put("seq", "1")
                                put("id", remoteDoc.id)
                                put("changes", buildJsonArray {
                                    add(buildJsonObject { put("rev", remoteDoc.rev!!) })
                                })
                                put("doc", json.encodeToJsonElement(CouchDocument.serializer(), remoteDoc))
                            })
                        })
                        put("last_seq", "1")
                    }
                    respond(
                        content = changesJson.toString(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respondOk("{}")
            }
        }

        val httpClient = HttpClient(mockEngine)
        val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

        // --- Push ---
        val pushResult = replicator.pushChanges(validConfig, "0")
        assertTrue(pushReceived, "Push should send to _bulk_docs")
        assertEquals(1, pushResult.documentsReplicated)
        assertEquals(0, pushResult.documentsFailed)

        // --- Pull ---
        val pullResult = replicator.pullChanges(validConfig, "0")
        assertEquals(1, pullResult.documentsReplicated)
        assertEquals("1", pullResult.lastSequence)

        // Verify the remote document is now in the local store
        val pulledDoc = localStore.get("recording:remote1")
        assertNotNull(pulledDoc, "Remote document should be in local store after pull")
        assertEquals("Remote Recording", pulledDoc.body["title"]?.jsonPrimitive?.content)
        assertEquals("device-b", pulledDoc.deviceId)
    }

    // =========================================================================
    // 2. Conflict resolution end-to-end (Requirement 5.1)
    // =========================================================================

    @Test
    fun conflictResolution_twoDevicesEditSameDocument_resolvedOnPull() = runTest {
        val localStoreDir = File(tempDir, "conflict")
        val localStore = LocalDocumentStore(localStoreDir)
        val conflictResolver = ConflictResolver("device-a")

        // --- Setup: Write a base document locally (simulating a previously synced doc) ---
        val baseDoc = CouchDocument(
            id = "recording:shared1",
            type = "recording",
            deviceId = "device-a",
            modifiedAt = 1700000000000L,
            body = buildRecordingBody(
                id = "shared1",
                title = "Original Title",
                note = "Original note",
                tags = listOf("meeting"),
                highlights = listOf(1000L)
            )
        )
        val savedBase = localStore.put(baseDoc)

        // --- Local edit: device-a updates the title and adds a tag ---
        val localEdit = savedBase.copy(
            rev = savedBase.rev,
            modifiedAt = 1700000100000L,
            deviceId = "device-a",
            body = buildRecordingBody(
                id = "shared1",
                title = "Title from Device A",
                note = "Note from device A",
                tags = listOf("meeting", "sprint"),
                highlights = listOf(1000L, 5000L)
            )
        )
        localStore.put(localEdit)

        // --- Remote edit: device-b modified the same document concurrently with more recent timestamp ---
        val remoteEdit = CouchDocument(
            id = "recording:shared1",
            rev = "3-remotehash",
            type = "recording",
            deviceId = "device-b",
            modifiedAt = 1700000200000L, // More recent than device-a
            body = buildRecordingBody(
                id = "shared1",
                title = "Title from Device B",
                note = "Note from device B",
                tags = listOf("meeting", "review"),
                highlights = listOf(1000L, 9000L)
            )
        )

        // Simulate pulling the remote edit
        val mockEngine = MockEngine { _ ->
            val changesJson = buildJsonObject {
                put("results", buildJsonArray {
                    add(buildJsonObject {
                        put("seq", "5")
                        put("id", remoteEdit.id)
                        put("changes", buildJsonArray {
                            add(buildJsonObject { put("rev", remoteEdit.rev!!) })
                        })
                        put("doc", json.encodeToJsonElement(CouchDocument.serializer(), remoteEdit))
                    })
                })
                put("last_seq", "5")
            }
            respond(
                content = changesJson.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine)
        val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

        // Pull triggers conflict resolution
        val pullResult = replicator.pullChanges(validConfig, "0")
        assertEquals(1, pullResult.documentsReplicated)

        // Verify conflict resolution result in local store
        val resolvedDoc = localStore.get("recording:shared1")
        assertNotNull(resolvedDoc, "Resolved document should be in local store")

        // Title/description: last-write-wins → device-b wins (modifiedAt 200000 > 100000)
        assertEquals(
            "Title from Device B",
            resolvedDoc.body["title"]?.jsonPrimitive?.content,
            "LWW should pick device-b title (more recent)"
        )

        // Tags: union merge → meeting, sprint, review
        val resolvedTags = resolvedDoc.body["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertTrue(resolvedTags.contains("meeting"), "Union merge should include 'meeting'")
        assertTrue(resolvedTags.contains("sprint"), "Union merge should include 'sprint'")
        assertTrue(resolvedTags.contains("review"), "Union merge should include 'review'")

        // Highlights: union merge → 1000, 5000, 9000
        val resolvedHighlights = resolvedDoc.body["highlights"]?.jsonArray?.map { it.jsonPrimitive.long } ?: emptyList()
        assertTrue(resolvedHighlights.contains(1000L), "Union merge should include 1000")
        assertTrue(resolvedHighlights.contains(5000L), "Union merge should include 5000")
        assertTrue(resolvedHighlights.contains(9000L), "Union merge should include 9000")

        // postRecordingNote: concatenation with both notes present, ordered by timestamp
        val resolvedNote = resolvedDoc.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""
        assertTrue(resolvedNote.contains("Note from device A"), "Concatenated note should contain device-a note")
        assertTrue(resolvedNote.contains("Note from device B"), "Concatenated note should contain device-b note")
        assertTrue(resolvedNote.contains("device-a"), "Concatenated note should reference device-a")
        assertTrue(resolvedNote.contains("device-b"), "Concatenated note should reference device-b")
    }

    // =========================================================================
    // 3. Migration integration (Requirements 3.1, 3.3)
    //    Create recordings in a local store, verify accessible via repository
    // =========================================================================

    @Test
    fun migrationIntegration_recordingsAccessibleViaRepositoryAfterMigration() = runTest {
        val storeDir = File(tempDir, "migration")
        val localStore = LocalDocumentStore(storeDir)
        val deviceId = "migration-device"

        // Simulate migrating recordings by writing CouchDB documents directly
        // (this mimics what DataMigrator does: converts domain objects to CouchDocuments)
        val recording = Recording(
            id = "migrated-rec-1",
            filePath = "/audio/migrated-rec-1.wav",
            durationMs = 3600000L,
            createdAt = 1700000000000L,
            title = "Migrated Meeting",
            description = "A meeting that was migrated from SQLite",
            template = MeetingTemplate.STANDUP,
            tags = listOf("standup", "team"),
            folder = "work",
            highlights = listOf(60000L, 1800000L),
            attachments = listOf(
                Attachment(id = "att-1", filePath = "/img/screen.png", timestampMs = 30000L, caption = "Diagram")
            ),
            postRecordingNote = "Action items assigned"
        )

        val transcription = TranscriptionResult(
            recordingId = "migrated-rec-1",
            text = "Good morning everyone, let's start with yesterday's updates...",
            segments = listOf(
                TranscriptionSegment(startMs = 0, endMs = 5000, text = "Good morning everyone", speaker = "Speaker 1")
            ),
            language = "en",
            durationMs = 3600000L,
            diarizationUnavailable = false
        )

        val summary = SummaryResult(
            recordingId = "migrated-rec-1",
            summary = "Team discussed blockers and progress on API redesign.",
            keyPoints = listOf("API redesign on track", "QA backlog needs attention"),
            actionItems = listOf("Mark: finish API spec"),
            questions = listOf("When is the deadline?"),
            tags = listOf("standup", "api"),
            modelUsed = "qwen3:8b",
            recommendedQuestions = listOf("What dependencies remain?"),
            qualityRating = QualityRating(pacing = 4, questions = 3, goalSetting = 5, nextSteps = 4, overall = 4.0)
        )

        // Convert to CouchDB documents (simulating migration)
        val recordingDoc = recording.toCouchDocument(deviceId, 1700000000000L)
        val transcriptionDoc = transcription.toCouchDocument(deviceId, 1700000000000L)
        val summaryDoc = summary.toCouchDocument(deviceId, 1700000000000L)

        // Bulk write (like DataMigrator does)
        val bulkResults = localStore.bulkDocs(
            listOf(recordingDoc, transcriptionDoc, summaryDoc),
            newEdits = true
        )
        assertTrue(bulkResults.all { it.ok }, "All documents should be written successfully")

        // Mark migration complete
        localStore.markMigrationComplete()
        assertTrue(localStore.hasMigrationCompleted(), "Migration marker should be set")

        // Now verify data is accessible through the repository layer
        // (using the same LocalDocumentStore)
        val retrievedRecordingDoc = localStore.get("recording:migrated-rec-1")
        assertNotNull(retrievedRecordingDoc)
        val retrievedRecording = retrievedRecordingDoc.toRecording()

        assertEquals(recording.id, retrievedRecording.id)
        assertEquals(recording.title, retrievedRecording.title)
        assertEquals(recording.description, retrievedRecording.description)
        assertEquals(recording.template, retrievedRecording.template)
        assertEquals(recording.tags, retrievedRecording.tags)
        assertEquals(recording.folder, retrievedRecording.folder)
        assertEquals(recording.highlights, retrievedRecording.highlights)
        assertEquals(recording.attachments, retrievedRecording.attachments)
        assertEquals(recording.postRecordingNote, retrievedRecording.postRecordingNote)
        assertEquals(recording.filePath, retrievedRecording.filePath)
        assertEquals(recording.durationMs, retrievedRecording.durationMs)
        assertEquals(recording.createdAt, retrievedRecording.createdAt)

        // Verify transcription
        val retrievedTransDoc = localStore.get("transcription:migrated-rec-1")
        assertNotNull(retrievedTransDoc)
        val retrievedTranscription = retrievedTransDoc.toTranscription()
        assertEquals(transcription.recordingId, retrievedTranscription.recordingId)
        assertEquals(transcription.text, retrievedTranscription.text)
        assertEquals(transcription.segments.size, retrievedTranscription.segments.size)
        assertEquals(transcription.language, retrievedTranscription.language)

        // Verify summary
        val retrievedSummaryDoc = localStore.get("summary:migrated-rec-1")
        assertNotNull(retrievedSummaryDoc)
        val retrievedSummary = retrievedSummaryDoc.toSummary()
        assertEquals(summary.recordingId, retrievedSummary.recordingId)
        assertEquals(summary.summary, retrievedSummary.summary)
        assertEquals(summary.keyPoints, retrievedSummary.keyPoints)
        assertEquals(summary.actionItems, retrievedSummary.actionItems)
        assertEquals(summary.modelUsed, retrievedSummary.modelUsed)
        assertNotNull(retrievedSummary.qualityRating)
        assertEquals(summary.qualityRating!!.overall, retrievedSummary.qualityRating!!.overall)
    }

    // =========================================================================
    // 4. SyncEngine lifecycle: start → sync → stop (Requirement 3.1)
    // =========================================================================

    @Test
    fun syncEngineLifecycle_startSyncStop_statusTransitions() = kotlinx.coroutines.runBlocking {
        val storeDir = File(tempDir, "lifecycle")
        val localStore = LocalDocumentStore(storeDir)
        val deviceIdFile = File(tempDir, "device_id")
        val conflictResolver = ConflictResolver("device-a")

        // Mock that simulates a working server with empty responses
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("_bulk_docs") -> {
                    respond(
                        content = "[]",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                url.contains("_changes") -> {
                    respond(
                        content = """{"results":[],"last_seq":"0"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respondOk("{\"db_name\":\"jeeves\"}")
            }
        }

        val httpClient = HttpClient(mockEngine)
        val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
        val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile)

        // Initial state: Idle
        assertEquals(SyncStatus.Idle, engine.status.value)

        // Start sync
        engine.start(validConfig)

        // Allow the replication loop to perform at least one cycle (real time)
        delay(1000)

        // After a cycle, the engine should be back to idle
        val statusAfterSync = engine.status.value
        assertTrue(
            statusAfterSync is SyncStatus.Idle || statusAfterSync is SyncStatus.Syncing,
            "After starting, status should be Idle (completed cycle) or Syncing (mid-cycle), got: $statusAfterSync"
        )

        // Verify lastSyncTimestamp is set after a successful cycle
        if (engine.status.value is SyncStatus.Idle) {
            assertNotNull(engine.lastSyncTimestamp.value, "Last sync timestamp should be set after successful sync")
        }

        // Stop the engine
        engine.stop()

        // After stop, should return to Idle
        assertEquals(SyncStatus.Idle, engine.status.value, "Engine should be idle after stop")
    }

    @Test
    fun syncEngineLifecycle_startWithError_transitionsToErrorState() = kotlinx.coroutines.runBlocking {
        val storeDir = File(tempDir, "lifecycle-error")
        val localStore = LocalDocumentStore(storeDir)
        val deviceIdFile = File(tempDir, "device_id_err")
        val conflictResolver = ConflictResolver("device-a")

        // Mock that returns auth failure
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"error":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine)
        val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
        val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile)

        // Seed a doc so push has something to send
        localStore.put(
            CouchDocument(
                id = "recording:err1",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                body = buildRecordingBody("err1", "Test", "")
            )
        )

        engine.start(validConfig)

        // Wait for the engine to encounter the auth error (real time needed)
        delay(1000)

        val status = engine.status.value
        assertIs<SyncStatus.Error>(status, "Engine should be in error state after auth failure, got: $status")
        assertFalse(status.retryable, "Auth errors should not be retryable")

        engine.stop()
    }

    @Test
    fun syncEngineLifecycle_syncNowTriggersImmediateCycle() = kotlinx.coroutines.runBlocking {
        val storeDir = File(tempDir, "lifecycle-syncnow")
        val localStore = LocalDocumentStore(storeDir)
        val deviceIdFile = File(tempDir, "device_id_sn")
        val conflictResolver = ConflictResolver("device-a")

        var requestCount = 0
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            requestCount++
            when {
                url.contains("_bulk_docs") -> respond(
                    content = "[]",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                url.contains("_changes") -> respond(
                    content = """{"results":[],"last_seq":"0"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respondOk("{}")
            }
        }

        val httpClient = HttpClient(mockEngine)
        val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
        val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile)

        engine.start(validConfig)
        delay(1000) // Let first cycle run (real time needed)

        val countAfterFirstCycle = requestCount

        // Trigger immediate sync
        engine.syncNow()
        delay(1000) // Let syncNow cycle run (real time needed)

        // Should have made additional requests
        assertTrue(
            requestCount > countAfterFirstCycle,
            "syncNow should trigger additional requests (before: $countAfterFirstCycle, after: $requestCount)"
        )

        engine.stop()
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun buildRecordingBody(
        id: String,
        title: String,
        note: String,
        tags: List<String> = emptyList(),
        highlights: List<Long> = emptyList()
    ): JsonObject {
        return buildJsonObject {
            put("id", id)
            put("filePath", "/path/to/$id.wav")
            put("durationMs", 60000L)
            put("createdAt", 1700000000000L)
            put("title", title)
            put("description", "")
            put("template", "GENERAL")
            put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
            put("folder", "")
            put("highlights", JsonArray(highlights.map { JsonPrimitive(it) }))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", note)
        }
    }
}
