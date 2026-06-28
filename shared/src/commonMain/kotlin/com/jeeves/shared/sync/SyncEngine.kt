package com.jeeves.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Orchestrates bidirectional CouchDB replication sessions.
 *
 * Manages the lifecycle of continuous push/pull replication, exposes sync status
 * to the UI via StateFlows, handles auto-reconnect on network errors, and
 * stops retrying on authentication failures per Requirement 7.4.
 *
 * **Validates: Requirements 1.3, 1.5, 1.7, 3.1, 3.4, 3.5, 3.6, 6.3, 6.5, 6.7, 6.8, 7.4**
 */
interface SyncEngine {
    /** Current sync status (idle, syncing, error, offline). */
    val status: StateFlow<SyncStatus>

    /** Number of local changes pending push to remote. */
    val pendingChanges: StateFlow<Int>

    /** Epoch millis of last successful sync completion, or null if never synced. */
    val lastSyncTimestamp: StateFlow<Long?>

    /**
     * Start continuous bidirectional replication with the given configuration.
     * Validates the config before starting. If already running, stops and restarts.
     */
    suspend fun start(config: SyncConfiguration)

    /** Stop the replication session gracefully. */
    suspend fun stop()

    /**
     * Trigger an immediate push/pull cycle without waiting for the next loop iteration.
     * If sync is not started, this is a no-op.
     */
    suspend fun syncNow()

    /**
     * Test connectivity to the remote database.
     * Attempts a GET on the remote DB root and returns the result.
     */
    suspend fun testConnection(config: SyncConfiguration): ConnectionTestResult

    /** Returns the stable device UUID, generating and persisting it on first call. */
    fun getDeviceId(): String
}

/**
 * Default implementation of [SyncEngine].
 *
 * @param replicator The CouchDB replicator for push/pull operations.
 * @param localStore The local document store for tracking changes.
 * @param httpClient The Ktor HTTP client for connection testing.
 * @param deviceIdFile The file where the device UUID is persisted.
 */
class DefaultSyncEngine(
    private val replicator: CouchDbReplicator,
    private val localStore: LocalDocumentStore,
    private val httpClient: HttpClient,
    private val deviceIdFile: File,
    private val passwordDecryptor: PasswordDecryptor = NoOpPasswordDecryptor
) : SyncEngine {

    companion object {
        /** Delay between replication cycles in milliseconds. */
        internal const val SYNC_LOOP_INTERVAL_MS = 5_000L

        /** Maximum reconnect delay after a network error. */
        internal const val RECONNECT_DELAY_MS = 10_000L

        /** Connection test timeout in milliseconds. */
        internal const val CONNECTION_TEST_TIMEOUT_MS = 10_000L
    }

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _pendingChanges = MutableStateFlow(0)
    override val pendingChanges: StateFlow<Int> = _pendingChanges.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow<Long?>(null)
    override val lastSyncTimestamp: StateFlow<Long?> = _lastSyncTimestamp.asStateFlow()

    /** Scope for the replication coroutine. Cancelled on stop(). */
    private var syncScope: CoroutineScope? = null

    /** The active replication job, if running. */
    private var syncJob: Job? = null

    /** The active configuration, set on start(). */
    private var activeConfig: SyncConfiguration? = null

    /** Sequence marker for push replication (tracks what's been pushed). */
    private var pushSequence: String = "0"

    /** Sequence marker for pull replication (tracks what's been pulled). */
    private var pullSequence: String = "0"

    /** Used to trigger an immediate sync cycle from syncNow(). */
    private val syncNowTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Cached device ID loaded/generated on first access. */
    private var cachedDeviceId: String? = null

    override suspend fun start(config: SyncConfiguration) {
        // Stop any existing session
        stop()

        // Validate configuration
        val emptyFields = config.findEmptyFields()
        if (emptyFields.isNotEmpty()) {
            _status.value = SyncStatus.Error(
                message = "Missing fields: ${emptyFields.joinToString(", ")}",
                retryable = false
            )
            return
        }

        val validationErrors = config.validate()
        if (validationErrors.isNotEmpty()) {
            _status.value = SyncStatus.Error(
                message = validationErrors.first(),
                retryable = false
            )
            return
        }

        activeConfig = config

        // Start the replication loop in a dedicated scope
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        syncScope = scope

        syncJob = scope.launch {
            runReplicationLoop(config)
        }

        // Monitor local changes to update pending count
        scope.launch {
            localStore.changes.collect {
                updatePendingChanges()
            }
        }
    }

    override suspend fun stop() {
        syncJob?.cancel()
        syncJob = null
        syncScope?.cancel()
        syncScope = null
        activeConfig = null
        _status.value = SyncStatus.Idle
    }

    override suspend fun syncNow() {
        if (syncJob?.isActive == true) {
            // Trigger an immediate cycle
            syncNowTrigger.tryEmit(Unit)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun testConnection(config: SyncConfiguration): ConnectionTestResult {
        // Validate URL format first
        val urlError = config.validateUrl()
        if (urlError != null) {
            return ConnectionTestResult(
                success = false,
                errorType = ConnectionErrorType.INVALID_URL,
                message = urlError
            )
        }

        // Check for empty credentials
        val emptyFields = config.findEmptyFields()
        if (emptyFields.isNotEmpty()) {
            return ConnectionTestResult(
                success = false,
                errorType = ConnectionErrorType.INVALID_URL,
                message = "Missing fields: ${emptyFields.joinToString(", ")}"
            )
        }

        return try {
            val result = withTimeout(CONNECTION_TEST_TIMEOUT_MS) {
                val decryptedPassword = passwordDecryptor.decrypt(config.encryptedPassword)
                val credentials = "${config.username}:$decryptedPassword"
                val encoded = Base64.encode(credentials.encodeToByteArray())
                val authHeader = "Basic $encoded"

                val response = httpClient.get(config.remoteUrl.trimEnd('/')) {
                    header(HttpHeaders.Authorization, authHeader)
                }

                when {
                    response.status.isSuccess() -> ConnectionTestResult(
                        success = true,
                        message = "Connected successfully"
                    )
                    response.status == HttpStatusCode.Unauthorized ||
                        response.status == HttpStatusCode.Forbidden -> ConnectionTestResult(
                        success = false,
                        errorType = ConnectionErrorType.AUTHENTICATION_FAILED,
                        message = "Authentication failed: invalid username or password"
                    )
                    else -> ConnectionTestResult(
                        success = false,
                        errorType = ConnectionErrorType.NETWORK_UNREACHABLE,
                        message = "Server returned HTTP ${response.status.value}"
                    )
                }
            }
            result
        } catch (e: TimeoutCancellationException) {
            ConnectionTestResult(
                success = false,
                errorType = ConnectionErrorType.TIMEOUT,
                message = "Connection timed out after ${CONNECTION_TEST_TIMEOUT_MS / 1000} seconds"
            )
        } catch (e: Exception) {
            val message = e.message ?: "Unknown error"
            when {
                message.contains("SSL", ignoreCase = true) ||
                    message.contains("TLS", ignoreCase = true) ||
                    message.contains("certificate", ignoreCase = true) -> ConnectionTestResult(
                    success = false,
                    errorType = ConnectionErrorType.TLS_ERROR,
                    message = "TLS certificate validation failed"
                )
                else -> ConnectionTestResult(
                    success = false,
                    errorType = ConnectionErrorType.NETWORK_UNREACHABLE,
                    message = "Network unreachable: $message"
                )
            }
        }
    }

    override fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        // Try to read from file
        if (deviceIdFile.exists()) {
            val stored = deviceIdFile.readText().trim()
            if (stored.isNotEmpty()) {
                cachedDeviceId = stored
                return stored
            }
        }

        // Generate new UUID and persist
        val newId = UUID.randomUUID().toString()
        deviceIdFile.parentFile?.mkdirs()
        deviceIdFile.writeText(newId)
        cachedDeviceId = newId
        return newId
    }

    // --- Internal replication loop ---

    /**
     * Runs the continuous push/pull replication loop.
     * On auth failure, stops without retry (Requirement 7.4).
     * On network errors, reconnects within 10 seconds (Requirement 3.1).
     */
    private suspend fun runReplicationLoop(config: SyncConfiguration) {
        // Subscribe to syncNow triggers so we can race against the delay
        val syncNowSubscription = syncNowTrigger.asSharedFlow()

        while (currentCoroutineContext().isActive) {
            try {
                // Perform one push/pull cycle
                performSyncCycle(config)

                // Wait for either the interval to elapse or a syncNow trigger
                withTimeoutOrNull(SYNC_LOOP_INTERVAL_MS) {
                    syncNowSubscription.first()
                }
            } catch (e: CancellationException) {
                // Normal cancellation from stop()
                throw e
            } catch (e: AuthenticationException) {
                // Auth failure: stop retrying per Requirement 7.4
                _status.value = SyncStatus.Error(
                    message = e.message ?: "Authentication failed",
                    retryable = false
                )
                break
            } catch (e: InsecureConnectionException) {
                _status.value = SyncStatus.Error(
                    message = e.message ?: "Insecure connection refused",
                    retryable = false
                )
                break
            } catch (e: TlsCertificateException) {
                _status.value = SyncStatus.Error(
                    message = e.message ?: "TLS certificate validation failed",
                    retryable = false
                )
                break
            } catch (e: Exception) {
                // Network error: auto-reconnect within 10 seconds
                _status.value = SyncStatus.Error(
                    message = e.message ?: "Sync failed",
                    retryable = true
                )
                delay(RECONNECT_DELAY_MS)
                // After reconnect delay, loop continues
            }
        }
    }

    /**
     * Performs a single push/pull replication cycle.
     * Updates status flows throughout.
     */
    private suspend fun performSyncCycle(config: SyncConfiguration) {
        // Push local changes
        _status.value = SyncStatus.Syncing(SyncDirection.PUSH)
        val pushResult = replicator.pushChanges(config, pushSequence)
        pushSequence = pushResult.lastSequence

        // Pull remote changes
        _status.value = SyncStatus.Syncing(SyncDirection.PULL)
        val pullResult = replicator.pullChanges(config, pullSequence)
        pullSequence = pullResult.lastSequence

        // Cycle complete
        _lastSyncTimestamp.value = System.currentTimeMillis()
        updatePendingChanges()

        // Transition to idle if no errors in this cycle
        if (pushResult.errors.isEmpty() && pullResult.errors.isEmpty()) {
            _status.value = SyncStatus.Idle
        } else {
            // Partial success: report errors but continue
            val totalErrors = pushResult.errors.size + pullResult.errors.size
            _status.value = SyncStatus.Error(
                message = "$totalErrors document(s) failed to sync",
                retryable = true
            )
        }
    }

    /**
     * Updates the pending changes count based on local store sequence vs push sequence.
     */
    private suspend fun updatePendingChanges() {
        val localSeq = localStore.getSequenceCounter()
        val pushedSeq = pushSequence.toLongOrNull() ?: 0L
        val pending = (localSeq - pushedSeq).coerceAtLeast(0)
        _pendingChanges.value = pending.toInt()
    }
}
