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

    // Observe error and progress from RecordingManager
    val error by appState.recordingManager.error.collectAsState()
    val progress by appState.recordingManager.transcriptionProgress.collectAsState()

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
                    Column(modifier = Modifier.fillMaxSize().padding(bottom = if (error != null || progress != null) 40.dp else 0.dp)) {
                        NavBar(currentScreen) { screen -> currentScreen = screen }

                        when (currentScreen) {
                            Screen.RECORDING -> RecordingScreen(hotkeyManager)
                            Screen.RECORDINGS_LIST -> RecordingsListScreen()
                            Screen.TIME_TRACKING -> TimeTrackingScreen()
                        }
                    }

                    // Bottom notification banner
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        when {
                            error != null -> NotificationBanner(
                                message = error,
                                type = NotificationType.ERROR,
                                onDismiss = { appState.recordingManager.clearError() }
                            )
                            progress != null -> NotificationBanner(
                                message = progress,
                                type = NotificationType.PROGRESS
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBar(currentScreen: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(icon = { }, label = { Text("Record") }, selected = currentScreen == Screen.RECORDING, onClick = { onNavigate(Screen.RECORDING) })
        NavigationBarItem(icon = { }, label = { Text("Recordings") }, selected = currentScreen == Screen.RECORDINGS_LIST, onClick = { onNavigate(Screen.RECORDINGS_LIST) })
        NavigationBarItem(icon = { }, label = { Text("Time") }, selected = currentScreen == Screen.TIME_TRACKING, onClick = { onNavigate(Screen.TIME_TRACKING) })
    }
}
