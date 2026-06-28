package com.jeeves.desktop.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.jeeves.shared.sync.SyncStatus
import kotlinx.coroutines.delay

/**
 * A compact sync status indicator suitable for toolbar/header placement.
 *
 * Displays the current sync state with appropriate icons:
 * - Idle: checkmark (green tint)
 * - Syncing: animated rotating sync icon with pending changes count
 * - Error: warning icon with tooltip showing error message (max 200 chars)
 * - Offline: cloud-off / disconnected icon
 *
 * Click behaviour:
 * - Idle, Error, or Offline: triggers "Sync Now" action
 * - Syncing: shows brief "sync already in progress" feedback
 *
 * **Validates: Requirements 6.1, 6.2, 6.3, 6.5, 6.8**
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SyncStatusIndicator(
    syncStatus: SyncStatus,
    pendingChanges: Int,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Brief "already syncing" snackbar-like feedback state
    var showAlreadySyncing by remember { mutableStateOf(false) }

    // Dismiss the feedback after a short delay
    LaunchedEffect(showAlreadySyncing) {
        if (showAlreadySyncing) {
            delay(2000L)
            showAlreadySyncing = false
        }
    }

    // Rotation animation for syncing state
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_icon_rotation"
    )

    val tooltipText = when (syncStatus) {
        is SyncStatus.Idle -> "Synced — click to sync now"
        is SyncStatus.Syncing -> {
            if (pendingChanges > 0) "Syncing ($pendingChanges pending)..."
            else "Syncing..."
        }
        is SyncStatus.Error -> syncStatus.message.take(200)
        is SyncStatus.Offline -> "Offline — click to retry"
    }

    Box(modifier = modifier) {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 4.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = tooltipText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        if (showAlreadySyncing) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Sync already in progress",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            },
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp))
        ) {
            Row(
                modifier = Modifier
                    .clickable {
                        when (syncStatus) {
                            is SyncStatus.Idle,
                            is SyncStatus.Error,
                            is SyncStatus.Offline -> onSyncNow()
                            is SyncStatus.Syncing -> {
                                showAlreadySyncing = true
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (syncStatus) {
                    is SyncStatus.Idle -> {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Synced",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    is SyncStatus.Syncing -> {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = "Syncing",
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(rotation),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        if (pendingChanges > 0) {
                            Text(
                                text = "$pendingChanges",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    is SyncStatus.Error -> {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Sync error",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    is SyncStatus.Offline -> {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = "Offline",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Show "already syncing" inline feedback
                if (showAlreadySyncing) {
                    Text(
                        text = "Sync in progress",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
