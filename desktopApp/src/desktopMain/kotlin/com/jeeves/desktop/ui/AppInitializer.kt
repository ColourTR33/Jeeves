package com.jeeves.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.jeeves.desktop.audio.DesktopAudioRecorder
import com.jeeves.desktop.audio.DesktopAudioPlayer
import com.jeeves.desktop.audio.StreamingTranscriber
import com.jeeves.desktop.data.CalendarService
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
import com.jeeves.shared.ai.PromptTemplateManager
import com.jeeves.shared.ai.WhisperClient
import com.jeeves.shared.ai.createHttpClient
import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.domain.AudioSource
import com.jeeves.shared.recording.RecordingManager
import com.jeeves.shared.recording.StreamingCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop implementation of StreamingCallback that bridges RecordingManager
 * (shared module) with StreamingTranscriber (desktop-only).
 * Also handles configuring the audio device before recording starts.
 */
class DesktopStreamingCallback(
    private val streamingTranscriber: StreamingTranscriber,
    private val audioRecorder: DesktopAudioRecorder,
    private val scope: CoroutineScope
) : StreamingCallback {
    override fun onPreRecordingSetup(settings: AppSettings) {
        // Configure the audio device based on settings
        audioRecorder.deviceName = when (settings.audioSource) {
            AudioSource.SPECIFIC_DEVICE -> settings.audioDeviceName
            AudioSource.DEFAULT_MICROPHONE -> ""
        }
        // Configure system audio capture for recording both sides of calls
        audioRecorder.captureSystemAudio = settings.captureSystemAudio
    }

    override fun onRecordingStarted(settings: AppSettings) {
        streamingTranscriber.startStreaming(audioRecorder, settings, scope)
    }

    override fun onRecordingStopping() {
        streamingTranscriber.stopStreaming()
    }

    override fun getStreamingTranscript(): String? {
        return streamingTranscriber.liveTranscript.value.takeIf { it.isNotBlank() }
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
        val calendarService = CalendarService()
        val promptTemplateManager = PromptTemplateManager(settingsRepository)

        val timeRepo = com.jeeves.desktop.data.FileTimeTrackingRepository()
        val timeManager = com.jeeves.shared.time.TimeTrackingManager(timeRepo, scope)
        timeManager.initialize()
        val reminderService = com.jeeves.desktop.time.TimeReminderService(timeManager, scope)
        reminderService.start()

        val recordingManager = RecordingManager(
            audioRecorder = audioRecorder,
            whisperClient = whisperClient,
            ollamaClient = ollamaClient,
            settingsRepository = settingsRepository,
            recordingsRepository = recordingsRepository,
            scope = scope,
            streamingCallback = streamingCallback,
            groqWhisperClient = com.jeeves.shared.ai.GroqWhisperClient(httpClient),
            diarizationClient = com.jeeves.shared.ai.DiarizationClient(httpClient),
            promptTemplateManager = promptTemplateManager
        )

        // Wire recording → timesheet integration: auto-log meeting time (+10 min handoff)
        recordingManager.onRecordingSaved = { recording, projectId ->
            val meetingDurationWithHandoff = recording.durationMs + 600_000L  // +10 min
            val date = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(recording.createdAt)
            timeManager.logMeetingTime(
                projectId = projectId,
                recordingId = recording.id,
                title = recording.title,
                durationMs = meetingDurationWithHandoff,
                date = date
            )
        }

        val callDetector = com.jeeves.desktop.audio.CallDetector(scope)
        callDetector.start()

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
            obsidianExportService = obsidianExportService,
            calendarService = calendarService,
            timeManager = timeManager,
            reminderService = reminderService,
            callDetector = callDetector
        )
    }

    // Store globally for settings window access
    appStateInstance = appState

    CompositionLocalProvider(LocalAppState provides appState) {
        com.jeeves.desktop.ui.JeevesAppContent(hotkeyManager, onOpenSettings)
    }
}
