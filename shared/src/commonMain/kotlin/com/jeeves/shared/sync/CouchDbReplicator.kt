package com.jeeves.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Result of a push or pull replication operation.
 */
@Serializable
data class ReplicationResult(
    val documentsReplicated: Int,
    val documentsFailed: Int,
    val lastSequence: String,
    val errors: List<ReplicationError> = emptyList()
)

/**
 * Describes a per-document replication error.
 */
@Serializable
data class ReplicationError(
    val documentId: String,
    val reason: String,
    val retriesRemaining: Int
)

/**
 * Exception thrown when the remote URL does not use HTTPS.
 */
class InsecureConnectionException(message: String) : Exception(message)

/**
 * Exception thrown when TLS certificate validation fails.
 */
class TlsCertificateException(message: String) : Exception(message)

/**
 * Exception thrown when authentication with the remote database fails.
 */
class AuthenticationException(message: String) : Exception(message)

/**
 * Implements the CouchDB replication protocol over HTTP using Ktor.
 *
 * Handles push (local → remote) and pull (remote → local) replication,
 * HTTPS-only enforcement, HTTP Basic Auth, and retry with exponential backoff.
 */
class CouchDbReplicator(
    private val httpClient: HttpClient,
    private val localStore: LocalDocumentStore,
    private val conflictResolver: ConflictResolver,
    private val passwordDecryptor: PasswordDecryptor = NoOpPasswordDecryptor
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        /** Maximum number of retries for 5xx server errors. */
        private const val MAX_RETRIES = 3

        /** Backoff delays in milliseconds: 5s, 10s, 20s. */
        private val BACKOFF_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L)
    }

    /**
     * Push local changes to the remote CouchDB instance.
     *
     * Reads local changes since the given sequence, collects the changed documents,
     * and POSTs them to the remote `/_bulk_docs` endpoint.
     *
     * @param config The sync configuration with remote URL and credentials.
     * @param since The sequence to read changes from (e.g., "0" for all).
     * @return ReplicationResult summarizing the push operation.
     * @throws InsecureConnectionException if the remote URL is not HTTPS.
     * @throws AuthenticationException if credentials are rejected (HTTP 401/403).
     */
    suspend fun pushChanges(config: SyncConfiguration, since: String): ReplicationResult {
        enforceHttps(config.remoteUrl)

        val changesResponse = localStore.changesSince(since)
        if (changesResponse.results.isEmpty()) {
            return ReplicationResult(
                documentsReplicated = 0,
                documentsFailed = 0,
                lastSequence = changesResponse.lastSeq
            )
        }

        // Collect documents for the changed IDs
        val documents = mutableListOf<CouchDocument>()
        for (change in changesResponse.results) {
            if (change.deleted) {
                // For deletions, create a minimal tombstone document
                documents.add(
                    CouchDocument(
                        id = change.id,
                        rev = change.rev,
                        deleted = true,
                        type = "",
                        deviceId = "",
                        modifiedAt = 0L,
                        body = buildJsonObject {}
                    )
                )
            } else {
                val doc = localStore.get(change.id)
                if (doc != null) {
                    documents.add(doc)
                }
            }
        }

        if (documents.isEmpty()) {
            return ReplicationResult(
                documentsReplicated = 0,
                documentsFailed = 0,
                lastSequence = changesResponse.lastSeq
            )
        }

        // Serialize documents for _bulk_docs
        val bulkDocsPayload = buildBulkDocsPayload(documents)
        val url = "${config.remoteUrl.trimEnd('/')}/_bulk_docs"

        val errors = mutableListOf<ReplicationError>()
        var replicated = 0
        var failed = 0

        val response = executeWithRetry(config) {
            httpClient.post(url) {
                header(HttpHeaders.Authorization, buildBasicAuthHeader(config))
                contentType(ContentType.Application.Json)
                setBody(bulkDocsPayload)
            }
        }

        if (response != null && response.status.isSuccess()) {
            // Parse response to count successes/failures
            val responseBody = response.bodyAsText()
            try {
                val results = json.decodeFromString<JsonArray>(responseBody)
                for (result in results) {
                    val obj = result.jsonObject
                    val ok = obj["ok"]?.jsonPrimitive?.boolean ?: false
                    if (ok) {
                        replicated++
                    } else {
                        failed++
                        val docId = obj["id"]?.jsonPrimitive?.content ?: "unknown"
                        val reason = obj["reason"]?.jsonPrimitive?.content ?: "unknown error"
                        errors.add(ReplicationError(documentId = docId, reason = reason, retriesRemaining = 0))
                    }
                }
            } catch (_: Exception) {
                // If we can't parse the response, treat all docs as replicated
                replicated = documents.size
            }
        } else {
            // All documents failed
            failed = documents.size
            val reason = response?.let { "HTTP ${it.status.value}" } ?: "request failed after retries"
            for (doc in documents) {
                errors.add(ReplicationError(documentId = doc.id, reason = reason, retriesRemaining = 0))
            }
        }

        return ReplicationResult(
            documentsReplicated = replicated,
            documentsFailed = failed,
            lastSequence = changesResponse.lastSeq,
            errors = errors
        )
    }

    /**
     * Pull remote changes from the CouchDB instance.
     *
     * Uses the `/_changes` endpoint with `feed=longpoll&include_docs=true` to fetch
     * new documents since the given sequence. Applies conflict resolution for
     * documents that already exist locally with different revisions.
     *
     * @param config The sync configuration with remote URL and credentials.
     * @param since The remote sequence to pull changes from.
     * @return ReplicationResult summarizing the pull operation.
     * @throws InsecureConnectionException if the remote URL is not HTTPS.
     * @throws AuthenticationException if credentials are rejected (HTTP 401/403).
     */
    suspend fun pullChanges(config: SyncConfiguration, since: String): ReplicationResult {
        enforceHttps(config.remoteUrl)

        val url = "${config.remoteUrl.trimEnd('/')}/_changes?since=$since&feed=longpoll&include_docs=true"

        val response = executeWithRetry(config) {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, buildBasicAuthHeader(config))
            }
        }

        if (response == null || !response.status.isSuccess()) {
            val reason = response?.let { "HTTP ${it.status.value}" } ?: "request failed after retries"
            return ReplicationResult(
                documentsReplicated = 0,
                documentsFailed = 0,
                lastSequence = since,
                errors = listOf(ReplicationError(documentId = "", reason = reason, retriesRemaining = 0))
            )
        }

        val responseBody = response.bodyAsText()
        val changesJson = json.decodeFromString<JsonObject>(responseBody)

        val results = changesJson["results"]?.jsonArray ?: JsonArray(emptyList())
        val lastSeq = changesJson["last_seq"]?.jsonPrimitive?.content ?: since

        if (results.isEmpty()) {
            return ReplicationResult(
                documentsReplicated = 0,
                documentsFailed = 0,
                lastSequence = lastSeq
            )
        }

        // Parse remote documents from the _changes response
        val remoteDocs = mutableListOf<CouchDocument>()
        for (result in results) {
            val obj = result.jsonObject
            val doc = obj["doc"]?.jsonObject ?: continue
            try {
                val couchDoc = json.decodeFromString<CouchDocument>(doc.toString())
                remoteDocs.add(couchDoc)
            } catch (_: Exception) {
                // Skip documents that can't be deserialized
            }
        }

        // Resolve conflicts for documents that exist locally
        val resolvedDocs = mutableListOf<CouchDocument>()
        val errors = mutableListOf<ReplicationError>()

        for (remoteDoc in remoteDocs) {
            try {
                val localDoc = localStore.get(remoteDoc.id)
                if (localDoc != null && localDoc.rev != remoteDoc.rev) {
                    // Conflict: resolve it
                    val resolved = conflictResolver.resolve(localDoc, remoteDoc)
                    resolvedDocs.add(resolved.winner)
                } else {
                    // No conflict or document is new locally
                    resolvedDocs.add(remoteDoc)
                }
            } catch (e: Exception) {
                errors.add(
                    ReplicationError(
                        documentId = remoteDoc.id,
                        reason = e.message ?: "conflict resolution failed",
                        retriesRemaining = 0
                    )
                )
            }
        }

        // Write resolved documents to local store (replication mode: newEdits=false)
        val bulkResults = localStore.bulkDocs(resolvedDocs, newEdits = false)

        var replicated = 0
        var failed = 0
        for (result in bulkResults) {
            if (result.ok) {
                replicated++
            } else {
                failed++
                errors.add(
                    ReplicationError(
                        documentId = result.id,
                        reason = result.reason ?: "write failed",
                        retriesRemaining = 0
                    )
                )
            }
        }

        return ReplicationResult(
            documentsReplicated = replicated,
            documentsFailed = failed,
            lastSequence = lastSeq,
            errors = errors
        )
    }

    /**
     * Upload an audio attachment to a remote document.
     *
     * Uses PUT /{db}/{docId}/{attachmentName}?rev={rev} with Content-Type: audio/wav.
     * Retries on 5xx server errors with exponential backoff.
     *
     * @param config The sync configuration with remote URL and credentials.
     * @param docId The document ID to attach to.
     * @param rev The current document revision (required by CouchDB for updates).
     * @param attachmentName The attachment filename (e.g., "audio.wav").
     * @param audioBytes The raw audio bytes to upload.
     * @return The new document revision after attachment upload.
     * @throws InsecureConnectionException if the remote URL is not HTTPS.
     * @throws AuthenticationException if credentials are rejected (HTTP 401/403).
     * @throws IllegalStateException if the upload fails after retries.
     */
    suspend fun pushAttachment(
        config: SyncConfiguration,
        docId: String,
        rev: String,
        attachmentName: String,
        audioBytes: ByteArray
    ): String {
        enforceHttps(config.remoteUrl)

        val url = "${config.remoteUrl.trimEnd('/')}/$docId/$attachmentName?rev=$rev"

        val response = executeWithRetry(config) {
            httpClient.put(url) {
                header(HttpHeaders.Authorization, buildBasicAuthHeader(config))
                contentType(ContentType("audio", "wav"))
                setBody(audioBytes)
            }
        }

        if (response != null && response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val responseJson = json.decodeFromString<JsonObject>(responseBody)
            return responseJson["rev"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("pushAttachment: response missing 'rev' field")
        }

        val reason = response?.let { "HTTP ${it.status.value}" } ?: "request failed after retries"
        throw IllegalStateException("pushAttachment failed for $docId/$attachmentName: $reason")
    }

    /**
     * Download an audio attachment from a remote document.
     *
     * Uses GET /{db}/{docId}/{attachmentName} and returns the raw bytes.
     * Retries on 5xx server errors with exponential backoff.
     *
     * @param config The sync configuration with remote URL and credentials.
     * @param docId The document ID to fetch the attachment from.
     * @param attachmentName The attachment filename (e.g., "audio.wav").
     * @return The raw bytes of the audio attachment.
     * @throws InsecureConnectionException if the remote URL is not HTTPS.
     * @throws AuthenticationException if credentials are rejected (HTTP 401/403).
     * @throws IllegalStateException if the download fails after retries.
     */
    suspend fun pullAttachment(
        config: SyncConfiguration,
        docId: String,
        attachmentName: String
    ): ByteArray {
        enforceHttps(config.remoteUrl)

        val url = "${config.remoteUrl.trimEnd('/')}/$docId/$attachmentName"

        val response = executeWithRetry(config) {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, buildBasicAuthHeader(config))
            }
        }

        if (response != null && response.status.isSuccess()) {
            return response.readBytes()
        }

        val reason = response?.let { "HTTP ${it.status.value}" } ?: "request failed after retries"
        throw IllegalStateException("pullAttachment failed for $docId/$attachmentName: $reason")
    }

    // --- Internal helpers ---

    /**
     * Enforces that the remote URL uses HTTPS.
     * Throws InsecureConnectionException if the URL does not start with "https://".
     */
    private fun enforceHttps(url: String) {
        if (!url.startsWith("https://")) {
            throw InsecureConnectionException(
                "Sync requires HTTPS. The remote URL must start with 'https://'. Got: ${url.take(50)}"
            )
        }
    }

    /**
     * Builds the HTTP Basic Auth header value from the sync configuration credentials.
     * Decrypts the stored password only at this point for HTTP transmission.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun buildBasicAuthHeader(config: SyncConfiguration): String {
        val decryptedPassword = passwordDecryptor.decrypt(config.encryptedPassword)
        val credentials = "${config.username}:$decryptedPassword"
        val encoded = Base64.encode(credentials.encodeToByteArray())
        return "Basic $encoded"
    }

    /**
     * Builds the JSON payload for the CouchDB `/_bulk_docs` endpoint.
     */
    private fun buildBulkDocsPayload(documents: List<CouchDocument>): String {
        val docsJson = json.encodeToString(documents)
        return """{"docs":$docsJson,"new_edits":false}"""
    }

    /**
     * Executes an HTTP request with retry logic for 5xx server errors.
     *
     * Retries up to 3 times with exponential backoff (5s, 10s, 20s).
     * Throws AuthenticationException immediately on 401/403 (no retry).
     * Returns null if all retries are exhausted.
     */
    private suspend fun executeWithRetry(
        config: SyncConfiguration,
        request: suspend () -> HttpResponse
    ): HttpResponse? {
        var lastResponse: HttpResponse? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                val response = request()
                lastResponse = response

                // Authentication failures: fail immediately, don't retry
                if (response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden
                ) {
                    throw AuthenticationException(
                        "Authentication failed with status ${response.status.value}. " +
                            "Check your username and password."
                    )
                }

                // Success: return immediately
                if (response.status.isSuccess()) {
                    return response
                }

                // 5xx server errors: retry with backoff
                if (response.status.value in 500..599) {
                    if (attempt < MAX_RETRIES) {
                        delay(BACKOFF_DELAYS_MS[attempt])
                        continue
                    }
                    // All retries exhausted
                    return response
                }

                // Other client errors (4xx except 401/403): don't retry
                return response
            } catch (e: AuthenticationException) {
                throw e
            } catch (e: InsecureConnectionException) {
                throw e
            } catch (e: Exception) {
                // Network errors, timeouts, TLS failures: retry with backoff
                if (attempt < MAX_RETRIES) {
                    delay(BACKOFF_DELAYS_MS[attempt])
                    continue
                }
                // Check if this looks like a TLS error
                val message = e.message ?: ""
                if (message.contains("SSL", ignoreCase = true) ||
                    message.contains("TLS", ignoreCase = true) ||
                    message.contains("certificate", ignoreCase = true)
                ) {
                    throw TlsCertificateException("TLS certificate validation failed: $message")
                }
                return null
            }
        }

        return lastResponse
    }
}
