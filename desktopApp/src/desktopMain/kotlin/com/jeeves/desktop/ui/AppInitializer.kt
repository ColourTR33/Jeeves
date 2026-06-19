package com.jeeves.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.jeeves.desktop.audio.DesktopAudioRecorder
import com.jeeves.desktop.audio.DesktopAudioPlayer
import com.jeeves.desktop.data.FileRecordingsRepository
import com.jeeves.desktop.data.FileSettingsRepository
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.desktop.ui.screens.AppState
import com.jeeves.desktop.ui.screens.LocalAppState
import com.jeeves.shared.ai.OllamaClient
import com.jeeves.shared.ai.WhisperClient
import com.jeeves.shared.ai.createHttpClient
import com.jeeves.shared.recording.RecordingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Initializes all application dependencies and provides them via CompositionLocal.
 */
@Composable
fun JeevesApp(hotkeyManager: HotkeyManager) {
    val appState = remember {
        val httpClient = createHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val audioRecorder = DesktopAudioRecorder()
        val audioPlayer = DesktopAudioPlayer()
        val whisperClient = WhisperClient(httpClient)
        val ollamaClient = OllamaClient(httpClient)
        val settingsRepository = FileSettingsRepository()
        val recordingsRepository = FileRecordingsRepository()

        val recordingManager = RecordingManager(
            audioRecorder = audioRecorder,
            whisperClient = whisperClient,
            ollamaClient = ollamaClient,
            settingsRepository = settingsRepository,
            recordingsRepository = recordingsRepository,
            scope = scope
        )

        AppState(
            recordingManager = recordingManager,
            settingsRepository = settingsRepository,
            recordingsRepository = recordingsRepository,
            audioPlayer = audioPlayer
        )
    }

    CompositionLocalProvider(LocalAppState provides appState) {
        com.jeeves.desktop.ui.JeevesAppContent(hotkeyManager)
    }
}
