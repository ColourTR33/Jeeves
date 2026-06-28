package com.jeeves.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.desktop.ui.JeevesApp
import com.jeeves.desktop.ui.appStateInstance
import com.jeeves.desktop.ui.shutdownSync
import com.jeeves.desktop.ui.components.AboutDialog
import com.jeeves.desktop.ui.screens.LocalAppState
import com.jeeves.desktop.ui.screens.SettingsScreen

fun main() = application {
    val hotkeyManager = HotkeyManager()
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            shutdownSync()
            hotkeyManager.shutdown()
            exitApplication()
        },
        title = "Jeeves - Meeting Recorder",
        state = rememberWindowState(width = 900.dp, height = 700.dp)
    ) {
        MenuBar {
            Menu("Jeeves") {
                Item("About Jeeves", onClick = { showAbout = true })
                Separator()
                Item("Settings...", onClick = { showSettings = true },
                    shortcut = KeyShortcut(key = Key.Comma, meta = true)
                )
                Separator()
                Item("Quit Jeeves", onClick = {
                    shutdownSync()
                    hotkeyManager.shutdown()
                    exitApplication()
                }, shortcut = KeyShortcut(key = Key.Q, meta = true))
            }
        }

        if (showAbout) {
            AboutDialog(onDismiss = { showAbout = false })
        }

        JeevesApp(hotkeyManager, onOpenSettings = { showSettings = true })
    }

    // Settings window (separate from main window, shares AppState)
    if (showSettings) {
        Window(
            onCloseRequest = { showSettings = false },
            title = "Jeeves Settings",
            state = rememberWindowState(width = 600.dp, height = 800.dp)
        ) {
            val appState = appStateInstance
            if (appState != null) {
                CompositionLocalProvider(LocalAppState provides appState) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
