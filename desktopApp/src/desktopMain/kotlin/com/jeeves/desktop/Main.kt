package com.jeeves.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.desktop.ui.JeevesApp

fun main() = application {
    val hotkeyManager = HotkeyManager()

    Window(
        onCloseRequest = {
            hotkeyManager.shutdown()
            exitApplication()
        },
        title = "Jeeves - Meeting Recorder",
        state = rememberWindowState(width = 900.dp, height = 700.dp)
    ) {
        JeevesApp(hotkeyManager)
    }
}
