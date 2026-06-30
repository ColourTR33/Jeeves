package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.RecordingsRepository
import com.jeeves.shared.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Handles automatic archival and deletion of recording audio files.
 *
 * Policy (configurable in settings):
 * - After [archiveAfterDays] days: move audio file to archive subfolder
 * - After [deleteAudioAfterDays] days: delete the audio file entirely
 * - Transcription and summary data are NEVER deleted
 *
 * Runs once on app startup.
 */
class RecordingArchiver(
    private val recordingsRepository: RecordingsRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val archiveDir: File by lazy {
        val dir = File(System.getProperty("user.home"), "Jeeves/recordings/archive")
        dir.mkdirs()
        dir
    }

    /**
     * Run the archival check. Call once on app startup.
     */
    fun runArchival() {
        scope.launch {
            try {
                val settings = settingsRepository.getSettings()
                val recordings = recordingsRepository.getRecordings()
                val now = System.currentTimeMillis()

                var archived = 0
                var deleted = 0

                for (recording in recordings) {
                    val ageMs = now - recording.createdAt
                    val ageDays = (ageMs / 86_400_000).toInt()

                    val audioFile = File(recording.filePath)

                    // Delete audio after deleteAudioAfterDays (but keep metadata/transcription/summary)
                    if (ageDays >= settings.deleteAudioAfterDays) {
                        if (audioFile.exists()) {
                            audioFile.delete()
                            deleted++
                        }
                        // Also delete from archive if it was moved there
                        val archivedFile = File(archiveDir, audioFile.name)
                        if (archivedFile.exists()) {
                            archivedFile.delete()
                        }
                        continue
                    }

                    // Archive after archiveAfterDays (move audio to archive folder)
                    if (ageDays >= settings.archiveAfterDays) {
                        if (audioFile.exists() && !audioFile.absolutePath.contains("archive")) {
                            val dest = File(archiveDir, audioFile.name)
                            if (audioFile.renameTo(dest)) {
                                // Update recording's filePath to point to archive location
                                recordingsRepository.updateRecording(
                                    recording.copy(filePath = dest.absolutePath)
                                )
                                archived++
                            }
                        }
                    }
                }

                if (archived > 0 || deleted > 0) {
                    AppLogger.info("RecordingArchiver", "Archival complete: $archived archived, $deleted audio files deleted")
                }
            } catch (e: Exception) {
                AppLogger.error("RecordingArchiver", "Archival failed: ${e.message}", e)
            }
        }
    }
}
