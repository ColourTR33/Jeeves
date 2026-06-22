package com.jeeves.desktop.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Type of notification to display in the banner.
 */
enum class NotificationType {
    ERROR,
    INFO,
    PROGRESS
}

/**
 * A bottom-anchored notification banner similar to Kiro's status bar.
 * Shows errors, progress messages, and info notifications.
 * Auto-dismisses info messages after a delay.
 */
@Composable
fun NotificationBanner(
    message: String?,
    type: NotificationType = NotificationType.INFO,
    onDismiss: (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val containerColor = when (type) {
            NotificationType.ERROR -> MaterialTheme.colorScheme.errorContainer
            NotificationType.INFO -> MaterialTheme.colorScheme.primaryContainer
            NotificationType.PROGRESS -> MaterialTheme.colorScheme.secondaryContainer
        }
        val contentColor = when (type) {
            NotificationType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            NotificationType.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
            NotificationType.PROGRESS -> MaterialTheme.colorScheme.onSecondaryContainer
        }
        val icon = when (type) {
            NotificationType.ERROR -> Icons.Filled.Error
            NotificationType.INFO -> Icons.Filled.Info
            NotificationType.PROGRESS -> null
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = containerColor,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (type == NotificationType.PROGRESS) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = contentColor
                    )
                }

                Text(
                    text = message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )

                if (onDismiss != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(16.dp),
                            tint = contentColor
                        )
                    }
                }
            }
        }
    }
}
