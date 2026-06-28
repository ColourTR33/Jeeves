package com.jeeves.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeeves.shared.sync.AudioAttachmentManager
import com.jeeves.shared.sync.SyncConfiguration
import kotlinx.coroutines.launch

/**
 * Download state for the audio download prompt.
 */
enum class AudioDownloadState {
    /** Showing the download prompt with file size info. */
    PROMPT,
    /** Download is in progress. */
    DOWNLOADING,
    /** Download completed successfully. */
    SUCCESS,
    /** Download failed after all retries. */
    FAILED
}

/**
 * A dialog prompt shown when a user opens a recording whose audio is not available locally.
 *
 * Displays the remote file size and estimated download time, with Download/Cancel actions.
 * On confirm, triggers [AudioAttachmentManager.downloadAudio] and shows progress.
 *
 * **Validates: Requirements 4.3, 4.4**
 *
 * @param recordingId The recording identifier (without "recording:" prefix).
 * @param remoteFileSize The size of the remote audio file in bytes, or null if unknown.
 * @param audioAttachmentManager The manager to trigger the download.
 * @param syncConfig The sync configuration needed for the download.
 * @param onDismiss Called when the dialog is dismissed (cancel or after success).
 * @param onDownloadComplete Called when the download finishes successfully with the local file path.
 */
@Composable
fun AudioDownloadPrompt(
    recordingId: String,
    remoteFileSize: Long?,
    audioAttachmentManager: AudioAttachmentManager,
    syncConfig: SyncConfiguration,
    onDismiss: () -> Unit,
    onDownloadComplete: (String) -> Unit = {}
) {
    var downloadState by remember { mutableStateOf(AudioDownloadState.PROMPT) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (downloadState != AudioDownloadState.DOWNLOADING) {
                onDismiss()
            }
        },
        icon = {
            Icon(
                imageVector = if (downloadState == AudioDownloadState.FAILED)
                    Icons.Filled.Error else Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = if (downloadState == AudioDownloadState.FAILED)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = when (downloadState) {
                    AudioDownloadState.PROMPT -> "Audio Not Available Locally"
                    AudioDownloadState.DOWNLOADING -> "Downloading Audio…"
                    AudioDownloadState.SUCCESS -> "Download Complete"
                    AudioDownloadState.FAILED -> "Download Failed"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (downloadState) {
                    AudioDownloadState.PROMPT -> {
                        Text("This recording's audio file needs to be downloaded from the sync server.")
                        Spacer(modifier = Modifier.height(4.dp))

                        if (remoteFileSize != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "File size:",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatFileSize(remoteFileSize),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Estimated time:",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = estimateDownloadTime(remoteFileSize),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Text(
                                text = "File size unknown",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AudioDownloadState.DOWNLOADING -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Downloading audio file…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AudioDownloadState.SUCCESS -> {
                        Text("Audio file downloaded successfully and is now available for playback.")
                    }

                    AudioDownloadState.FAILED -> {
                        Text(
                            text = errorMessage ?: "Download failed after multiple attempts.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                AudioDownloadState.PROMPT -> {
                    Button(
                        onClick = {
                            downloadState = AudioDownloadState.DOWNLOADING
                            scope.launch {
                                try {
                                    val localPath = audioAttachmentManager.downloadAudio(
                                        recordingId, syncConfig
                                    )
                                    downloadState = AudioDownloadState.SUCCESS
                                    onDownloadComplete(localPath)
                                } catch (e: Exception) {
                                    downloadState = AudioDownloadState.FAILED
                                    errorMessage = e.message ?: "Unknown error"
                                }
                            }
                        }
                    ) {
                        Text("Download")
                    }
                }

                AudioDownloadState.DOWNLOADING -> {
                    // No confirm button while downloading
                }

                AudioDownloadState.SUCCESS -> {
                    Button(onClick = onDismiss) {
                        Text("OK")
                    }
                }

                AudioDownloadState.FAILED -> {
                    Button(
                        onClick = {
                            downloadState = AudioDownloadState.DOWNLOADING
                            errorMessage = null
                            scope.launch {
                                try {
                                    val localPath = audioAttachmentManager.downloadAudio(
                                        recordingId, syncConfig
                                    )
                                    downloadState = AudioDownloadState.SUCCESS
                                    onDownloadComplete(localPath)
                                } catch (e: Exception) {
                                    downloadState = AudioDownloadState.FAILED
                                    errorMessage = e.message ?: "Unknown error"
                                }
                            }
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        },
        dismissButton = {
            if (downloadState != AudioDownloadState.DOWNLOADING) {
                TextButton(onClick = onDismiss) {
                    Text(if (downloadState == AudioDownloadState.SUCCESS) "Close" else "Cancel")
                }
            }
        }
    )
}

/**
 * Formats a file size in bytes to a human-readable string (KB, MB, or GB).
 */
internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}

/**
 * Estimates download time based on file size assuming a typical broadband speed of 10 Mbps.
 * Returns a human-readable duration string.
 */
internal fun estimateDownloadTime(bytes: Long): String {
    // Assume 10 Mbps download speed (conservative for broadband)
    val bitsPerSecond = 10_000_000.0
    val seconds = (bytes * 8.0) / bitsPerSecond

    return when {
        seconds < 1.0 -> "< 1 second"
        seconds < 60.0 -> "${seconds.toInt()} seconds"
        seconds < 3600.0 -> {
            val minutes = (seconds / 60.0).toInt()
            val remainingSeconds = (seconds % 60.0).toInt()
            if (remainingSeconds > 0) "$minutes min $remainingSeconds sec"
            else "$minutes min"
        }
        else -> {
            val hours = (seconds / 3600.0).toInt()
            val remainingMinutes = ((seconds % 3600.0) / 60.0).toInt()
            if (remainingMinutes > 0) "$hours hr $remainingMinutes min"
            else "$hours hr"
        }
    }
}
