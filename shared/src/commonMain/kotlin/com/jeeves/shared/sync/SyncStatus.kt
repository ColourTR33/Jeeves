package com.jeeves.shared.sync

import kotlinx.serialization.Serializable

/**
 * Represents the current state of the sync engine.
 * Used by the UI to display sync status to the user.
 */
@Serializable
sealed class SyncStatus {
    @Serializable
    data object Idle : SyncStatus()

    @Serializable
    data class Syncing(val direction: SyncDirection) : SyncStatus()

    @Serializable
    data class Error(val message: String, val retryable: Boolean) : SyncStatus()

    @Serializable
    data object Offline : SyncStatus()
}

/**
 * Direction of an active sync operation.
 */
@Serializable
enum class SyncDirection {
    PUSH,
    PULL,
    BOTH
}

/**
 * Result of a connection test to the remote CouchDB instance.
 */
@Serializable
data class ConnectionTestResult(
    val success: Boolean,
    val errorType: ConnectionErrorType? = null,
    val message: String = ""
)

/**
 * Categories of connection errors for diagnostic display.
 */
@Serializable
enum class ConnectionErrorType {
    INVALID_URL,
    NETWORK_UNREACHABLE,
    AUTHENTICATION_FAILED,
    TLS_ERROR,
    TIMEOUT
}
