package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.shared.domain.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun RecordingScreen(hotkeyManager: HotkeyManager) {
    val appState = LocalAppState.current

    val recordingState by appState.recordingManager.state.collectAsState()
    val error by appState.recordingManager.error.collectAsState()
    val progress by appState.recordingManager.transcriptionProgress.collectAsState()

    // Timer display
    var elapsedSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            elapsedSeconds = 0
            while (isActive && recordingState == RecordingState.RECORDING) {
                delay(1000)
                elapsedSeconds++
            }
        } else if (recordingState == RecordingState.IDLE) {
            elapsedSeconds = 0
        }
    }

    // Register hotkey toggle
    LaunchedEffect(Unit) {
        hotkeyManager.onToggleRecording = {
            appState.recordingManager.toggleRecording()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status indicator
        Text(
            text = when (recordingState) {
                RecordingState.IDLE -> "Ready to Record"
                RecordingState.RECORDING -> "Recording..."
                RecordingState.PAUSED -> "Paused"
                RecordingState.PROCESSING -> "Processing..."
            },
            style = MaterialTheme.typography.headlineLarge,
            color = when (recordingState) {
                RecordingState.RECORDING -> Color.Red
                RecordingState.PROCESSING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onBackground
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Timer
        if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
            Text(
                text = formatDuration(elapsedSeconds),
                fontSize = 48.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Processing progress
        progress?.let { msg ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = msg, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Main record button
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Record / Stop button
            FilledIconButton(
                onClick = { appState.recordingManager.toggleRecording() },
                modifier = Modifier.size(80.dp),
                enabled = recordingState != RecordingState.PROCESSING,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (recordingState == RecordingState.RECORDING)
                        Color.Red else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (recordingState == RecordingState.RECORDING)
                        Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recordingState == RecordingState.RECORDING)
                        "Stop recording" else "Start recording",
                    modifier = Modifier.size(40.dp)
                )
            }

            // Pause button (only during recording)
            if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
                FilledTonalIconButton(
                    onClick = {
                        CoroutineScope(Dispatchers.Main).launch {
                            if (recordingState == RecordingState.PAUSED) {
                                appState.recordingManager.resumeRecording()
                            } else {
                                appState.recordingManager.pauseRecording()
                            }
                        }
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (recordingState == RecordingState.PAUSED)
                            Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (recordingState == RecordingState.PAUSED)
                            "Resume" else "Pause"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Hotkey hint
        Text(
            text = "Hotkey: Ctrl+Shift+R to toggle recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Error display
        error?.let { errorMsg ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { appState.recordingManager.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}
