package com.jeeves.desktop.ui.screens

import androidx.compose.runtime.compositionLocalOf
import com.jeeves.desktop.audio.DesktopAudioPlayer
import com.jeeves.desktop.audio.DesktopAudioRecorder
import com.jeeves.desktop.audio.StreamingTranscriber
import com.jeeves.desktop.data.EmailExportService
import com.jeeves.desktop.data.ExportService
import com.jeeves.desktop.data.ObsidianExportService
import com.jeeves.desktop.data.RemindersExportService
import com.jeeves.desktop.data.SearchService
import com.jeeves.desktop.data.SpeakerNameService
import com.jeeves.shared.domain.RecordingsRepository
import com.jeeves.shared.domain.SettingsRepository
import com.jeeves.shared.recording.RecordingManager

/**
 * Application state holder, provided via CompositionLocal.
 */
data class AppState(
    val recordingManager: RecordingManager,
    val settingsRepository: SettingsRepository,
    val recordingsRepository: RecordingsRepository,
    val audioPlayer: DesktopAudioPlayer,
    val audioRecorder: DesktopAudioRecorder,
    val streamingTranscriber: StreamingTranscriber,
    val searchService: SearchService,
    val exportService: ExportService,
    val remindersExportService: RemindersExportService,
    val speakerNameService: SpeakerNameService,
    val emailExportService: EmailExportService,
    val obsidianExportService: ObsidianExportService
)

val LocalAppState = compositionLocalOf<AppState> {
    error("No AppState provided")
}
