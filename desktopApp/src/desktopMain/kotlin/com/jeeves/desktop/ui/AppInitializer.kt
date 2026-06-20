package com.jeeves.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.jeeves.desktop.audio.DesktopAudioRecorder
import com.jeeves.desktop.audio.DesktopAudioPlayer
import com.jeeves.desktop.audio.StreamingTranscriber
import com.jeeves.desktop.data.EmailExportService
import com.jeeves.desktop.data.ExportService
import com.jeeves.desktop.data.FileRecordingsRepository
import com.jeeves.desktop.data.FileSettingsRepository
import com.jeeves.desktop.data.ObsidianExportService
import com.jeeves.desktop.data.RemindersExportService
import com.jeeves.desktop.data.SearchService
import com.jeeves.desktop.data.SpeakerNameService
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.desktop.ui.screens.AppState
import com.jeeves.desktop.ui.screens.LocalAppState
import com.jeeves.shared.ai.OllamaClient
import com.jeeves.shared.ai.WhisperClient
import com.jeeves.shared.ai.createHttpClient
import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.recording.RecordingManager
import com.jeeves.shared.recording.StreamingCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop implementation of StreamingCallback that bridges RecordingManager
 * (shared module) with StreamingTranscriber (desktop-only).
 */
class DesktopStreamingCallback(
    private val streamingTranscriber: StreamingTranscriber,
    private val audioRecorder: DesktopAudioRecorder,
    private val scope: CoroutineScope
) : StreamingCallback {
    override fun onRecordingStarted(settings: AppSettings) {
        streamingTranscriber.startStreaming(audioRecorder, settings, scope)
    }

    override fun onRecordingStopping() {
        streamingTranscriber.stopStreaming()
    }
}

/** Global AppState instance shared between main window and settings window. */
var appStateInstance: com.jeeves.desktop.ui.screens.AppState? = null
    private set

/**
 * Initializes all application dependencies and provides them via CompositionLocal.
 */
@Composable
fun JeevesApp(hotkeyManager: HotkeyManager, onOpenSettings: () -> Unit = {}) {
    val appState = remember {
        val httpClient = createHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val audioRecorder = DesktopAudioRecorder()
        val audioPlayer = DesktopAudioPlayer()
        val whisperClient = WhisperClient(httpClient)
        val ollamaClient = OllamaClient(httpClient)
        val settingsRepository = FileSettingsRepository()
        val recordingsRepository = FileRecordingsRepository()

        val streamingTranscriber = StreamingTranscriber(httpClient)
        val streamingCallback = DesktopStreamingCallback(streamingTranscriber, audioRecorder, scope)
        val searchService = SearchService(recordingsRepository)
        val exportService = ExportService()
        val remindersExportService = RemindersExportService()
        val speakerNameService = SpeakerNameService()
        val emailExportService = EmailExportService()
        val obsidianExportService = ObsidianExportService()

        val recordingManager = RecordingManager(
            audioRecorder = audioRecorder,
            whisperClient = whisperClient,
            ollamaClient = ollamaClient,
            settingsRepository = settingsRepository,
            recordingsRepository = recordingsRepository,
            scope = scope,
            streamingCallback = streamingCallback
        )

        AppState(
            recordingManager = recordingManager,
            settingsRepository = settingsRepository,
            recordingsRepository = recordingsRepository,
            audioPlayer = audioPlayer,
            audioRecorder = audioRecorder,
            streamingTranscriber = streamingTranscriber,
            searchService = searchService,
            exportService = exportService,
            remindersExportService = remindersExportService,
            speakerNameService = speakerNameService,
            emailExportService = emailExportService,
            obsidianExportService = obsidianExportService
        )
    }

    // Store globally for settings window access
    appStateInstance = appState

    CompositionLocalProvider(LocalAppState provides appState) {
        com.jeeves.desktop.ui.JeevesAppContent(hotkeyManager, onOpenSettings)
    }
}
