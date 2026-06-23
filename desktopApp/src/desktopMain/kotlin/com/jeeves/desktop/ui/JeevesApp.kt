package com.jeeves.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.desktop.ui.components.*
import com.jeeves.desktop.ui.screens.LocalAppState
import com.jeeves.desktop.ui.screens.RecordingScreen
import com.jeeves.desktop.ui.screens.RecordingsListScreen
import com.jeeves.desktop.ui.screens.TimeTrackingScreen

enum class Screen {
    RECORDING,
    RECORDINGS_LIST,
    TIME_TRACKING
}

@Composable
fun JeevesAppContent(hotkeyManager: HotkeyManager, onOpenSettings: () -> Unit = {}) {
    var currentScreen by remember { mutableStateOf(Screen.RECORDING) }
    val appState = LocalAppState.current

    // Track window width for responsive compact mode
    val density = LocalDensity.current
    var windowWidthDp by remember { mutableStateOf(900) }
    val isCompact = windowWidthDp < COMPACT_WIDTH_THRESHOLD

    // Recording state + persistent timer (survives tab switches)
    val recordingState by appState.recordingManager.state.collectAsState()
    var elapsedSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(recordingState) {
        if (recordingState == com.jeeves.shared.domain.RecordingState.RECORDING) {
            elapsedSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                elapsedSeconds++
            }
        } else if (recordingState == com.jeeves.shared.domain.RecordingState.IDLE) {
            elapsedSeconds = 0
        }
        // PAUSED: keep current value, don't increment
    }

    // Observe error and progress from RecordingManager
    val error by appState.recordingManager.error.collectAsState()
    val progress by appState.recordingManager.transcriptionProgress.collectAsState()
    val queueItems by appState.recordingManager.processingQueue.queue.collectAsState()

    // Derive banner message from queue state
    val activeItem = queueItems.firstOrNull {
        it.status == com.jeeves.shared.recording.ProcessingStatus.TRANSCRIBING ||
        it.status == com.jeeves.shared.recording.ProcessingStatus.SUMMARIZING
    }
    val waitingCount = queueItems.count { it.status == com.jeeves.shared.recording.ProcessingStatus.WAITING }
    val failedItem = queueItems.lastOrNull { it.status == com.jeeves.shared.recording.ProcessingStatus.FAILED }

    // Recording indicator takes priority in banner
    val isRecordingActive = recordingState == com.jeeves.shared.domain.RecordingState.RECORDING ||
        recordingState == com.jeeves.shared.domain.RecordingState.PAUSED

    // Call detection
    val callDetected by appState.callDetector?.callDetected?.collectAsState() ?: remember { mutableStateOf(false) }
    val detectedApp by appState.callDetector?.detectedApp?.collectAsState() ?: remember { mutableStateOf(null) }

    val bannerMessage = when {
        isRecordingActive -> {
            val timeStr = formatElapsedTime(elapsedSeconds)
            val stateLabel = if (recordingState == com.jeeves.shared.domain.RecordingState.PAUSED) "Paused" else "Recording"
            "\uD83D\uDD34 $stateLabel — $timeStr"
        }
        callDetected && !isRecordingActive -> "\uD83D\uDCDE $detectedApp detected — Start recording?"
        error != null -> error
        activeItem != null -> {
            val statusLabel = if (activeItem.status == com.jeeves.shared.recording.ProcessingStatus.TRANSCRIBING) "Transcribing" else "Summarising"
            val queueSuffix = if (waitingCount > 0) " ($waitingCount more queued)" else ""
            "$statusLabel recording...$queueSuffix"
        }
        failedItem != null -> "Processing failed: ${failedItem.error ?: "unknown error"}"
        progress != null -> progress
        else -> null
    }
    val bannerType = when {
        isRecordingActive -> NotificationType.INFO
        callDetected && !isRecordingActive -> NotificationType.INFO
        error != null -> NotificationType.ERROR
        failedItem != null && activeItem == null -> NotificationType.ERROR
        activeItem != null || progress != null -> NotificationType.PROGRESS
        else -> NotificationType.INFO
    }

    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        // Provide compact mode to all child composables
        CompositionLocalProvider(LocalCompactMode provides isCompact) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        windowWidthDp = with(density) { size.width.toDp().value.toInt() }
                    },
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main content
                    Column(modifier = Modifier.fillMaxSize().padding(bottom = if (bannerMessage != null) 40.dp else 0.dp)) {
                        NavBar(currentScreen) { screen -> currentScreen = screen }

                        when (currentScreen) {
                            Screen.RECORDING -> RecordingScreen(hotkeyManager)
                            Screen.RECORDINGS_LIST -> RecordingsListScreen()
                            Screen.TIME_TRACKING -> TimeTrackingScreen()
                        }
                    }

                    // Bottom notification banner
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        NotificationBanner(
                            message = bannerMessage,
                            type = bannerType,
                            onDismiss = when {
                                isRecordingActive -> null
                                callDetected && !isRecordingActive -> {{ appState.callDetector?.dismiss() }}
                                error != null -> {{ appState.recordingManager.clearError() }}
                                failedItem != null && activeItem == null -> {{ appState.recordingManager.processingQueue.clearCompleted() }}
                                else -> null
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun formatElapsedTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

@Composable
private fun NavBar(currentScreen: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(icon = { }, label = { Text("Record") }, selected = currentScreen == Screen.RECORDING, onClick = { onNavigate(Screen.RECORDING) })
        NavigationBarItem(icon = { }, label = { Text("Recordings") }, selected = currentScreen == Screen.RECORDINGS_LIST, onClick = { onNavigate(Screen.RECORDINGS_LIST) })
        NavigationBarItem(icon = { }, label = { Text("Time") }, selected = currentScreen == Screen.TIME_TRACKING, onClick = { onNavigate(Screen.TIME_TRACKING) })
    }
}
