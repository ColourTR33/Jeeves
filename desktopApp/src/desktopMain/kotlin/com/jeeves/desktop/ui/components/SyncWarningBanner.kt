package com.jeeves.desktop.ui.components

import androidx.compose.runtime.*
import com.jeeves.shared.sync.SyncEngine
import kotlinx.coroutines.delay

/**
 * Threshold in milliseconds after which a sync warning is shown.
 * If the app has been running for this duration without a successful sync,
 * a persistent warning notification is displayed.
 *
 * **Validates: Requirement 6.6**
 */
const val SYNC_WARNING_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour

/**
 * Polling interval to check whether the sync warning threshold has been exceeded.
 */
private const val CHECK_INTERVAL_MS = 30_000L // Check every 30 seconds

/**
 * State holder for the sync warning banner logic.
 * Monitors `lastSyncTimestamp` from [SyncEngine] and determines whether
 * to show a persistent warning notification.
 *
 * The warning is shown when:
 * - The app has been running for more than 1 hour AND
 * - No successful sync has completed in that time
 *
 * The warning dismisses when:
 * - The user explicitly dismisses it
 * - A sync completes successfully (lastSyncTimestamp updates)
 */
@Composable
fun rememberSyncWarningState(
    syncEngine: SyncEngine?,
    currentTimeProvider: () -> Long = { System.currentTimeMillis() }
): SyncWarningState {
    val appStartTime = remember { currentTimeProvider() }
    val lastSyncTimestamp by syncEngine?.lastSyncTimestamp?.collectAsState()
        ?: remember { mutableStateOf(null) }

    var userDismissed by remember { mutableStateOf(false) }
    var showWarning by remember { mutableStateOf(false) }

    // Reset user dismissal when a successful sync occurs
    LaunchedEffect(lastSyncTimestamp) {
        if (lastSyncTimestamp != null) {
            userDismissed = false
            showWarning = false
        }
    }

    // Periodically check if the warning threshold has been exceeded
    LaunchedEffect(syncEngine, userDismissed, lastSyncTimestamp) {
        if (syncEngine == null) return@LaunchedEffect

        while (true) {
            val now = currentTimeProvider()
            val appRunningDuration = now - appStartTime
            val lastSync = lastSyncTimestamp

            val shouldWarn = appRunningDuration > SYNC_WARNING_THRESHOLD_MS &&
                (lastSync == null || (now - lastSync) > SYNC_WARNING_THRESHOLD_MS)

            showWarning = shouldWarn && !userDismissed
            delay(CHECK_INTERVAL_MS)
        }
    }

    return remember(showWarning, userDismissed) {
        SyncWarningState(
            shouldShow = showWarning,
            onDismiss = {
                userDismissed = true
                showWarning = false
            }
        )
    }
}

/**
 * Represents the current state of the sync warning banner.
 *
 * @property shouldShow Whether the warning banner should be visible.
 * @property onDismiss Callback to dismiss the warning (user action).
 */
data class SyncWarningState(
    val shouldShow: Boolean,
    val onDismiss: () -> Unit
)

/**
 * A composable that displays a persistent sync warning using [NotificationBanner]
 * when synchronization has not completed for more than 1 hour while the app is running.
 *
 * Usage:
 * ```kotlin
 * val syncWarning = rememberSyncWarningState(syncEngine = appState.syncEngine)
 * SyncWarningBanner(syncWarning)
 * ```
 *
 * **Validates: Requirement 6.6**
 */
@Composable
fun SyncWarningBanner(state: SyncWarningState) {
    NotificationBanner(
        message = if (state.shouldShow) "Sync hasn't completed in over an hour. Check your connection." else null,
        type = NotificationType.ERROR,
        onDismiss = if (state.shouldShow) state.onDismiss else null
    )
}
