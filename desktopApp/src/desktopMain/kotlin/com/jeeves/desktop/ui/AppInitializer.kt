package com.jeeves.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.jeeves.desktop.audio.DesktopAudioRecorder
import com.jeeves.desktop.audio.DesktopAudioPlayer
import com.jeeves.desktop.audio.StreamingTranscriber
import com.jeeves.desktop.data.CalendarService
import com.jeeves.desktop.data.CouchDbRecordingsRepository
import com.jeeves.desktop.data.CredentialEncryption
import com.jeeves.desktop.data.DataMigrator
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
import com.jeeves.shared.domain.RecordingsRepository
import com.jeeves.shared.recording.RecordingManager
import com.jeeves.shared.recording.StreamingCallback
import com.jeeves.shared.sync.AudioDownloadPolicy
import com.jeeves.shared.sync.ConflictResolver
import com.jeeves.shared.sync.CouchDbReplicator
import com.jeeves.shared.sync.DefaultSyncEngine
import com.jeeves.shared.sync.LocalDocumentStore
import com.jeeves.shared.sync.SyncConfiguration
import com.jeeves.shared.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

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
 * Gracefully shuts down the SyncEngine if running.
 * Should be called on application exit.
 */
fun shutdownSync() {
    val engine = appStateInstance?.syncEngine ?: return
    runBlocking { engine.stop() }
}

/**
 * Resolves the stable device UUID from the given file.
 * If the file does not exist or is empty, generates a new UUID and persists it.
 */
private fun resolveDeviceId(deviceIdFile: File): String {
    if (deviceIdFile.exists()) {
        val stored = deviceIdFile.readText().trim()
        if (stored.isNotEmpty()) return stored
    }
    val newId = UUID.randomUUID().toString()
    deviceIdFile.parentFile?.mkdirs()
    deviceIdFile.writeText(newId)
    return newId
}

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
        val fileRecordingsRepository = FileRecordingsRepository()

        // Load settings to determine sync configuration
        val settings = runBlocking { settingsRepository.getSettings() }

        // Initialize verbose logging based on settings
        val logDir = File(System.getProperty("user.home"), "Jeeves/logs").absolutePath
        com.jeeves.shared.ai.AppLogger.setVerbose(settings.verboseLogging, logDir)

        // Determine the active RecordingsRepository and SyncEngine based on sync settings
        val syncEngine: SyncEngine?
        val recordingsRepository: RecordingsRepository

        if (settings.syncEnabled && settings.syncRemoteUrl.isNotBlank()) {
            // Sync enabled: set up CouchDB-backed components
            val syncBaseDir = File(System.getProperty("user.home"), "Jeeves/sync")
            val localStore = LocalDocumentStore(syncBaseDir)
            val credentialEncryption = CredentialEncryption.createDefault()
            val deviceIdFile = File(System.getProperty("user.home"), "Jeeves/.keys/device-id")

            // Resolve device ID (read from file or generate new UUID)
            val deviceId = resolveDeviceId(deviceIdFile)

            // Create SyncEngine with proper conflict resolver
            val conflictResolver = ConflictResolver(deviceId)
            val replicator = CouchDbReplicator(httpClient, localStore, conflictResolver, credentialEncryption)
            val engine = DefaultSyncEngine(replicator, localStore, httpClient, deviceIdFile, credentialEncryption)

            // Run migration from SQLite if needed
            val migrator = DataMigrator(fileRecordingsRepository, localStore, deviceId)
            runBlocking { migrator.migrateIfNeeded() }

            // Use CouchDB-backed repository
            recordingsRepository = CouchDbRecordingsRepository(localStore, deviceId)

            // Build SyncConfiguration from settings and start the engine
            val audioPolicy = try {
                AudioDownloadPolicy.valueOf(settings.syncAudioDownloadPolicy)
            } catch (_: Exception) {
                AudioDownloadPolicy.ON_DEMAND
            }
            val syncConfig = SyncConfiguration(
                remoteUrl = settings.syncRemoteUrl,
                username = settings.syncUsername,
                encryptedPassword = settings.syncPassword,
                enabled = true,
                audioDownloadPolicy = audioPolicy,
                deviceId = deviceId
            )
            runBlocking { engine.start(syncConfig) }
            syncEngine = engine
        } else {
            // Sync disabled: use file-based repository as before
            recordingsRepository = fileRecordingsRepository
            syncEngine = null
        }

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
        // Ensure a Holiday project exists for blocking out availability
        scope.launch {
            val existingProjects = timeRepo.getProjects()
            if (existingProjects.none { it.name.equals("Holiday", ignoreCase = true) }) {
                timeRepo.saveProject(com.jeeves.shared.domain.Project(
                    id = com.jeeves.shared.recording.generateId(),
                    name = "Holiday",
                    isBillable = false,
                    isDistributed = false,
                    color = "#9C27B0"
                ))
            }
        }
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

        // Wire recording → timesheet integration:
        // On record START: start a running timer against the selected project
        recordingManager.onRecordingStarted = { projectId, title ->
            timeManager.startTimer(projectId, title)
        }
        // On record STOP: stop the running timer, then add +10 min handoff to the entry
        recordingManager.onRecordingSaved = { recording, projectId ->
            timeManager.stopTimerAndAddHandoff(recording.id, 600_000L)  // +10 min
        }

        val callDetector = com.jeeves.desktop.audio.CallDetector(scope)
        callDetector.start()

        val mantraRepo = com.jeeves.desktop.data.FileMantraRepository()
        val mantraManager = com.jeeves.shared.mantra.MantraManager(mantraRepo, timeManager, scope)
        mantraManager.initialize()

        val meetingScheduleRepo = com.jeeves.desktop.data.FileMeetingScheduleRepository()
        val meetingScheduleManager = com.jeeves.desktop.meeting.MeetingScheduleManager(meetingScheduleRepo, scope)
        meetingScheduleManager.initialize()

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
            mantraManager = mantraManager,
            meetingScheduleManager = meetingScheduleManager,
            callDetector = callDetector,
            syncEngine = syncEngine
        )
    }

    // Store globally for settings window access
    appStateInstance = appState

    CompositionLocalProvider(LocalAppState provides appState) {
        com.jeeves.desktop.ui.JeevesAppContent(hotkeyManager, onOpenSettings)
    }
}
