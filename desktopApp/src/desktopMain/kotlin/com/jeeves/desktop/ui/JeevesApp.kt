package com.jeeves.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.jeeves.desktop.hotkey.HotkeyManager
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

    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                NavBar(currentScreen) { screen -> currentScreen = screen }

                when (currentScreen) {
                    Screen.RECORDING -> RecordingScreen(hotkeyManager)
                    Screen.RECORDINGS_LIST -> RecordingsListScreen()
                    Screen.TIME_TRACKING -> TimeTrackingScreen()
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
