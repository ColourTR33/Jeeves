package com.jeeves.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for AudioAttachmentManager.
 *
 * Tests upload flow, download with retry on failure, local availability checks,
 * and remote audio size queries.
 *
 * **Validates: Requirements 4.1, 4.4, 4.6**
 */
class AudioAttachmentManagerTest {

    private val validConfig = SyncConfiguration(
        remoteUrl = "https://couch.example.com/jeeves",
        username = "admin",
        encryptedPassword = "secret123"
    )

    // =========================================================================
    // isAudioAvailableLocally - file does not exist (Requirement 4.1)
    // =========================================================================

    @Test
    fun isAudioAvailableLocally_returnsFalse_whenFileDoesNotExist() {
        val audioDir = createTempDirectory()
        try {
            val storeDir = createTempDirectory()
            val localStore = LocalDocumentStore(storeDir)
            val replicator = createMockReplicator(localStore)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val result = manager.isAudioAvailableLocally("nonexistent-recording")

            assertFalse(result, "Should return false when audio file does not exist")
        } finally {
            audioDir.deleteRecursively()
        }
    }

    // =========================================================================
    // isAudioAvailableLocally - file exists (Requirement 4.1)
    // =========================================================================

    @Test
    fun isAudioAvailableLocally_returnsTrue_whenFileExists() {
        val audioDir = createTempDirectory()
        try {
            val storeDir = createTempDirectory()
            val localStore = LocalDocumentStore(storeDir)
            val replicator = createMockReplicator(localStore)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            // Create the audio file
            File(audioDir, "test-recording.wav").writeText("fake audio data")

            val result = manager.isAudioAvailableLocally("test-recording")

            assertTrue(result, "Should return true when audio file exists")
        } finally {
            audioDir.deleteRecursively()
        }
    }

    // =========================================================================
    // downloadAudio - saves file to correct location (Requirement 4.4)
    // =========================================================================

    @Test
    fun downloadAudio_savesFileToCorrectLocation() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)
            val audioContent = "fake WAV audio content bytes"

            val mockEngine = MockEngine { request ->
                // Mock pullAttachment response
                respond(
                    content = audioContent,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "audio/wav")
                )
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val resultPath = manager.downloadAudio("rec123", validConfig)

            val expectedFile = File(audioDir, "rec123.wav")
            assertTrue(expectedFile.exists(), "Audio file should exist at expected location")
            assertEquals(expectedFile.absolutePath, resultPath)
            assertEquals(audioContent, expectedFile.readText())
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // downloadAudio - retries on failure (Requirement 4.6)
    // =========================================================================

    @Test
    fun downloadAudio_retriesOnFailure_succedsOnSecondAttempt() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)
            val audioContent = "audio data after retry"
            var requestCount = 0

            val mockEngine = MockEngine { _ ->
                requestCount++
                if (requestCount == 1) {
                    // First attempt fails
                    throw java.io.IOException("Connection reset")
                } else {
                    // Second attempt succeeds
                    respond(
                        content = audioContent,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/wav")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val resultPath = manager.downloadAudio("retry-rec", validConfig)

            val expectedFile = File(audioDir, "retry-rec.wav")
            assertTrue(expectedFile.exists(), "Audio file should exist after successful retry")
            assertEquals(audioContent, expectedFile.readText())
            // At least 2 requests made (original + retry through replicator's own retry logic)
            assertTrue(requestCount >= 2, "Should have made multiple requests due to retry")
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // downloadAudio - throws AudioDownloadException after max retries (Requirement 4.6)
    // =========================================================================

    @Test
    fun downloadAudio_throwsAudioDownloadException_afterMaxRetries() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)

            // All requests fail with 500
            val mockEngine = MockEngine { _ ->
                respondError(HttpStatusCode.InternalServerError)
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val exception = assertFailsWith<AudioDownloadException> {
                manager.downloadAudio("failed-rec", validConfig)
            }

            assertTrue(
                exception.message!!.contains("failed-rec"),
                "Exception message should reference the recording ID"
            )
            assertTrue(
                exception.message!!.contains("3"),
                "Exception message should reference the number of retry attempts"
            )

            // File should not exist
            val file = File(audioDir, "failed-rec.wav")
            assertFalse(file.exists(), "Audio file should not exist after failed download")
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // getRemoteAudioSize - returns attachment length from doc (Requirement 4.6)
    // =========================================================================

    @Test
    fun getRemoteAudioSize_returnsAttachmentLength_whenDocHasAttachment() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)

            // Store a document with an attachment stub
            val doc = CouchDocument(
                id = "recording:size-rec",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                attachments = mapOf(
                    "audio.wav" to AttachmentStub(
                        contentType = "audio/wav",
                        length = 104857600L, // 100MB
                        digest = "md5-abc123",
                        stub = true
                    )
                ),
                body = buildRecordingBody("size-rec", "Size Test")
            )
            localStore.put(doc)

            val replicator = createMockReplicator(localStore)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val size = manager.getRemoteAudioSize("size-rec", validConfig)

            assertEquals(104857600L, size)
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // getRemoteAudioSize - returns null when no attachments (Requirement 4.6)
    // =========================================================================

    @Test
    fun getRemoteAudioSize_returnsNull_whenNoAttachments() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)

            // Store a document without attachments
            val doc = CouchDocument(
                id = "recording:no-attach",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                attachments = null,
                body = buildRecordingBody("no-attach", "No Attachment Test")
            )
            localStore.put(doc)

            val replicator = createMockReplicator(localStore)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val size = manager.getRemoteAudioSize("no-attach", validConfig)

            assertNull(size, "Should return null when document has no attachments")
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // getRemoteAudioSize - returns null when doc not found
    // =========================================================================

    @Test
    fun getRemoteAudioSize_returnsNull_whenDocNotFound() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)
            val replicator = createMockReplicator(localStore)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)

            val size = manager.getRemoteAudioSize("nonexistent", validConfig)

            assertNull(size, "Should return null when document does not exist")
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // uploadAudio - reads file and calls pushAttachment (Requirement 4.1)
    // =========================================================================

    @Test
    fun uploadAudio_readsFileAndCallsPushAttachment() = runBlocking {
        val audioDir = createTempDirectory()
        val storeDir = createTempDirectory()
        try {
            val localStore = LocalDocumentStore(storeDir)

            // Store the recording document (required by uploadAudio)
            val doc = CouchDocument(
                id = "recording:upload-rec",
                type = "recording",
                deviceId = "device-a",
                modifiedAt = 1700000000000L,
                body = buildRecordingBody("upload-rec", "Upload Test")
            )
            val savedDoc = localStore.put(doc)

            // Create the audio file to upload
            val audioFile = File(audioDir, "upload-rec.wav")
            val audioContent = "fake WAV audio bytes for upload"
            audioFile.writeBytes(audioContent.toByteArray())

            var capturedUrl: String? = null
            var capturedContentType: String? = null

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedContentType = request.body.contentType?.toString()
                respond(
                    content = """{"ok":true,"id":"recording:upload-rec","rev":"2-newrev"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine)
            val conflictResolver = ConflictResolver("device-a")
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver)
            val manager = AudioAttachmentManager(replicator, localStore, audioDir)
            manager.setConfig(validConfig)

            manager.uploadAudio("upload-rec", audioFile.absolutePath)

            // Verify the PUT request was made to the correct URL with correct content type
            assertTrue(
                capturedUrl!!.contains("recording:upload-rec/audio.wav"),
                "Should PUT to the attachment URL. Got: $capturedUrl"
            )
            assertEquals("audio/wav", capturedContentType)
        } finally {
            audioDir.deleteRecursively()
            storeDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun createTempDirectory(): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "audio_test_${System.nanoTime()}")
        tempDir.mkdirs()
        return tempDir
    }

    private fun createMockReplicator(localStore: LocalDocumentStore): CouchDbReplicator {
        val mockEngine = MockEngine { _ ->
            respondOk("")
        }
        val httpClient = HttpClient(mockEngine)
        val conflictResolver = ConflictResolver("device-a")
        return CouchDbReplicator(httpClient, localStore, conflictResolver)
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
