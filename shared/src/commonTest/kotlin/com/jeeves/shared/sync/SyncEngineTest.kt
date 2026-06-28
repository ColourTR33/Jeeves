package com.jeeves.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for DefaultSyncEngine and CouchDbReplicator.
 *
 * Uses ktor-client-mock to simulate HTTP interactions for connection testing,
 * push/pull replication flows, retry logic, and lifecycle management.
 *
 * **Validates: Requirements 1.3, 1.5, 3.1, 3.7, 7.1, 7.3**
 */
class SyncEngineTest {

    private val validConfig = SyncConfiguration(
        remoteUrl = "https://couch.example.com/jeeves",
        username = "admin",
        encryptedPassword = "secret123"
    )

    // =========================================================================
    // testConnection - Success (Requirement 1.3)
    // =========================================================================

    @Test
    fun testConnection_success_returnsSuccessResult() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respondOk("{\"db_name\":\"jeeves\"}")
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertTrue(result.success, "Expected success but got: $result")
        assertEquals(null, result.errorType)
    }

    // =========================================================================
    // testConnection - Auth failure (Requirement 1.5)
    // =========================================================================

    @Test
    fun testConnection_authFailure_returnsAuthenticationFailed() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respondError(HttpStatusCode.Unauthorized)
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.AUTHENTICATION_FAILED, result.errorType)
    }

    @Test
    fun testConnection_forbidden_returnsAuthenticationFailed() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respondError(HttpStatusCode.Forbidden)
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.AUTHENTICATION_FAILED, result.errorType)
    }

    // =========================================================================
    // testConnection - Timeout (Requirement 1.3)
    // =========================================================================

    @Test
    fun testConnection_timeout_returnsTimeoutError() = runBlocking {
        val mockEngine = MockEngine { _ ->
            // Simulate a delay longer than the 10s connection test timeout
            delay(15_000)
            respondOk("")
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.TIMEOUT, result.errorType)
        assertTrue(result.message.contains("timed out", ignoreCase = true))
    }

    // =========================================================================
    // testConnection - TLS Error (Requirement 7.3)
    // =========================================================================

    @Test
    fun testConnection_tlsError_returnsTlsError() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw java.io.IOException("SSL handshake failed: certificate not trusted")
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.TLS_ERROR, result.errorType)
        assertTrue(result.message.contains("TLS", ignoreCase = true))
    }

    @Test
    fun testConnection_certificateError_returnsTlsError() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw java.io.IOException("Unable to verify certificate chain")
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.TLS_ERROR, result.errorType)
    }

    // =========================================================================
    // testConnection - Network Unreachable (Requirement 1.5)
    // =========================================================================

    @Test
    fun testConnection_networkUnreachable_returnsNetworkError() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw java.io.IOException("Network is unreachable")
        }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val result = engine.testConnection(validConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.NETWORK_UNREACHABLE, result.errorType)
    }

    // =========================================================================
    // testConnection - Invalid URL (non-HTTPS) (Requirement 7.1)
    // =========================================================================

    @Test
    fun testConnection_invalidUrl_returnsInvalidUrlError() = runBlocking {
        val mockEngine = MockEngine { _ -> respondOk("") }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val httpConfig = validConfig.copy(remoteUrl = "http://couch.example.com/jeeves")
        val result = engine.testConnection(httpConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.INVALID_URL, result.errorType)
    }

    @Test
    fun testConnection_emptyUrl_returnsInvalidUrlError() = runBlocking {
        val mockEngine = MockEngine { _ -> respondOk("") }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        val emptyConfig = validConfig.copy(remoteUrl = "")
        val result = engine.testConnection(emptyConfig)

        assertFalse(result.success)
        assertEquals(ConnectionErrorType.INVALID_URL, result.errorType)
    }

    // =========================================================================
    // CouchDbReplicator - pushChanges success (Requirement 3.7)
    // =========================================================================

    @Test
    fun pushChanges_success_returnsDocumentsReplicated() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)

            // Seed a document in the local store
            val doc = CouchDocument(
                id = "recording:test1",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                body = buildRecordingBody("test1", "My Recording")
            )
            localStore.put(doc)

            // Mock the _bulk_docs response from CouchDB
            val mockEngine = MockEngine { request ->
                assertTrue(request.url.toString().contains("_bulk_docs"))
                respond(
                    content = """[{"ok":true,"id":"recording:test1","rev":"1-abc"}]""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

            val result = replicator.pushChanges(validConfig, "0")

            assertEquals(1, result.documentsReplicated)
            assertEquals(0, result.documentsFailed)
            assertTrue(result.errors.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // CouchDbReplicator - pullChanges success
    // =========================================================================

    @Test
    fun pullChanges_success_returnsDocumentsReplicated() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)

            // Mock _changes response from CouchDB with one document
            val changesResponse = """
            {
                "results": [
                    {
                        "seq": "1",
                        "id": "recording:remote1",
                        "changes": [{"rev": "1-xyz"}],
                        "doc": {
                            "_id": "recording:remote1",
                            "_rev": "1-xyz",
                            "_deleted": false,
                            "type": "recording",
                            "deviceId": "device-b",
                            "modifiedAt": 1700000000000,
                            "body": {
                                "id": "remote1",
                                "filePath": "/path/to/audio.wav",
                                "durationMs": 60000,
                                "createdAt": 1700000000000,
                                "title": "Remote Recording",
                                "description": "",
                                "template": "GENERAL",
                                "tags": [],
                                "folder": "",
                                "highlights": [],
                                "attachments": [],
                                "postRecordingNote": ""
                            }
                        }
                    }
                ],
                "last_seq": "1"
            }
            """.trimIndent()

            val mockEngine = MockEngine { request ->
                assertTrue(request.url.toString().contains("_changes"))
                respond(
                    content = changesResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

            val result = replicator.pullChanges(validConfig, "0")

            assertEquals(1, result.documentsReplicated)
            assertEquals(0, result.documentsFailed)
            assertEquals("1", result.lastSequence)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // CouchDbReplicator - Retry on 5xx (Requirement 3.7)
    // =========================================================================

    @Test
    fun pushChanges_retryOn5xx_succeedsAfterRetry() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)

            // Seed a document
            val doc = CouchDocument(
                id = "recording:retry1",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                body = buildRecordingBody("retry1", "Retry Test")
            )
            localStore.put(doc)

            var requestCount = 0
            val mockEngine = MockEngine { _ ->
                requestCount++
                if (requestCount == 1) {
                    // First request: 500 Internal Server Error
                    respondError(HttpStatusCode.InternalServerError)
                } else {
                    // Second request: success
                    respond(
                        content = """[{"ok":true,"id":"recording:retry1","rev":"1-abc"}]""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

            val result = replicator.pushChanges(validConfig, "0")

            // Should succeed after retry
            assertEquals(1, result.documentsReplicated)
            assertEquals(0, result.documentsFailed)
            // Should have made 2 requests (first failed, second succeeded)
            assertEquals(2, requestCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // CouchDbReplicator - No retry on 401 (Requirement 7.3)
    // =========================================================================

    @Test
    fun pushChanges_noRetryOn401_failsImmediately() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)

            // Seed a document
            val doc = CouchDocument(
                id = "recording:auth1",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                body = buildRecordingBody("auth1", "Auth Test")
            )
            localStore.put(doc)

            var requestCount = 0
            val mockEngine = MockEngine { _ ->
                requestCount++
                respondError(HttpStatusCode.Unauthorized)
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

            var authExceptionThrown = false
            try {
                replicator.pushChanges(validConfig, "0")
            } catch (e: AuthenticationException) {
                authExceptionThrown = true
            }

            assertTrue(authExceptionThrown, "Should throw AuthenticationException on 401")
            // Verify only one request was made (no retries)
            assertEquals(1, requestCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // CouchDbReplicator - HTTPS enforcement (Requirement 7.1)
    // =========================================================================

    @Test
    fun pushChanges_httpUrl_throwsInsecureConnectionException() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)

            // Seed a document
            val doc = CouchDocument(
                id = "recording:http1",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                body = buildRecordingBody("http1", "HTTP Test")
            )
            localStore.put(doc)

            val mockEngine = MockEngine { _ -> respondOk("") }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)

            val httpConfig = validConfig.copy(remoteUrl = "http://insecure.example.com/jeeves")

            var insecureExceptionThrown = false
            try {
                replicator.pushChanges(httpConfig, "0")
            } catch (e: InsecureConnectionException) {
                insecureExceptionThrown = true
            }

            assertTrue(insecureExceptionThrown, "Should throw InsecureConnectionException for HTTP URL")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Device ID generation and persistence (Requirement 1.7)
    // =========================================================================

    @Test
    fun getDeviceId_generatesAndPersistsUuid() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val deviceIdFile = File(tempDir, "device_id")
            val mockEngine = MockEngine { _ -> respondOk("") }
            val httpClient = HttpClient(mockEngine)
            val engine = createSyncEngine(httpClient, deviceIdFile = deviceIdFile)

            val deviceId = engine.getDeviceId()

            // Should be a non-empty UUID format
            assertNotNull(deviceId)
            assertTrue(deviceId.isNotEmpty())
            assertTrue(
                deviceId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
                "Expected UUID format but got: $deviceId"
            )

            // Should be persisted to file
            assertTrue(deviceIdFile.exists())
            assertEquals(deviceId, deviceIdFile.readText().trim())

            // Should return the same ID on subsequent calls
            val deviceId2 = engine.getDeviceId()
            assertEquals(deviceId, deviceId2)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun getDeviceId_readsExistingFile() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val deviceIdFile = File(tempDir, "device_id")
            deviceIdFile.writeText("existing-device-uuid-123")

            val mockEngine = MockEngine { _ -> respondOk("") }
            val httpClient = HttpClient(mockEngine)
            val engine = createSyncEngine(httpClient, deviceIdFile = deviceIdFile)

            val deviceId = engine.getDeviceId()

            assertEquals("existing-device-uuid-123", deviceId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun getDeviceId_persistsAcrossInstances() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val deviceIdFile = File(tempDir, "device_id")
            val mockEngine = MockEngine { _ -> respondOk("") }
            val httpClient = HttpClient(mockEngine)

            // First engine generates the ID
            val engine1 = createSyncEngine(httpClient, deviceIdFile = deviceIdFile)
            val deviceId1 = engine1.getDeviceId()

            // Second engine reads the same ID from file
            val engine2 = createSyncEngine(httpClient, deviceIdFile = deviceIdFile)
            val deviceId2 = engine2.getDeviceId()

            assertEquals(deviceId1, deviceId2)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // SyncEngine start/stop lifecycle (Requirement 3.1)
    // =========================================================================

    @Test
    fun syncEngine_initialStatus_isIdle() = runBlocking {
        val mockEngine = MockEngine { _ -> respondOk("") }
        val httpClient = HttpClient(mockEngine)
        val engine = createSyncEngine(httpClient)

        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test
    fun syncEngine_stopAfterStart_returnsToIdle() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)
            val deviceIdFile = File(tempDir, "device_id")
            // Mock that responds with empty changes (quiet server)
            val mockEngine = MockEngine { request ->
                when {
                    request.url.encodedPath.contains("_bulk_docs") -> respond(
                        content = "[]",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                    request.url.encodedPath.contains("_changes") -> respond(
                        content = """{"results":[],"last_seq":"0"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                    else -> respondOk("{\"db_name\":\"jeeves\"}")
                }
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile)

            engine.start(validConfig)
            // Give it a moment to start the coroutine
            delay(200)

            engine.stop()

            assertEquals(SyncStatus.Idle, engine.status.value)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun syncEngine_startWithInvalidConfig_transitionsToError() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)
            val deviceIdFile = File(tempDir, "device_id")
            val mockEngine = MockEngine { _ -> respondOk("") }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile)

            // Start with missing username
            val invalidConfig = validConfig.copy(username = "")
            engine.start(invalidConfig)

            val status = engine.status.value
            assertTrue(status is SyncStatus.Error)
            assertFalse(status.retryable)
            assertTrue(status.message.contains("username", ignoreCase = true))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun syncEngine_startWithHttpUrl_transitionsToError() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(tempDir)
            val deviceIdFile = File(tempDir, "device_id")
            val mockEngine = MockEngine { _ -> respondOk("") }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile)

            val httpConfig = validConfig.copy(remoteUrl = "http://not-secure.com/db")
            engine.start(httpConfig)

            val status = engine.status.value
            assertTrue(status is SyncStatus.Error)
            assertTrue(status.message.contains("HTTPS"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun createSyncEngine(
        httpClient: HttpClient,
        deviceIdFile: File? = null
    ): DefaultSyncEngine {
        val tempDir = createTempDirectory()
        val localStore = LocalDocumentStore(tempDir)
        val file = deviceIdFile ?: File(tempDir, "device_id")
        val conflictResolver = ConflictResolver("test-device")
        val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
        return DefaultSyncEngine(replicator, localStore, httpClient, file)
    }

    private fun createTempDirectory(): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "sync_test_${System.nanoTime()}")
        tempDir.mkdirs()
        return tempDir
    }

    private fun buildRecordingBody(id: String, title: String): JsonObject {
        return buildJsonObject {
            put("id", id)
            put("filePath", "/path/to/$id.wav")
            put("durationMs", 60000L)
            put("createdAt", 1700000000000L)
            put("title", title)
            put("description", "")
            put("template", "GENERAL")
            put("tags", JsonArray(emptyList()))
            put("folder", "")
            put("highlights", JsonArray(emptyList()))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", "")
        }
    }
}
