package com.jeeves.desktop.data

import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.domain.SettingsRepository
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based settings persistence for desktop.
 */
class FileSettingsRepository : SettingsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val settingsFile: File
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves")
            dir.mkdirs()
            return File(dir, "settings.json")
        }

    override suspend fun getSettings(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                val settings = json.decodeFromString<AppSettings>(settingsFile.readText())
                migrateSettings(settings)
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            AppSettings()
        }
    }

    override suspend fun saveSettings(settings: AppSettings) {
        val migrated = migrateSettings(settings)
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), migrated))
    }

    /**
     * Apply settings migrations to fix known corruption issues.
     * This runs on every load and save to ensure stale values don't persist.
     */
    private fun migrateSettings(settings: AppSettings): AppSettings {
        var s = settings

        // Migration: Remove streamingTranscriptionEndpoint if it points to port 8179
        // (nothing runs on 8179 — this was a bad auto-suggestion that keeps reappearing)
        val streamingEp = s.streamingTranscriptionEndpoint
        if (streamingEp != null && streamingEp.baseUrl.contains(":8179")) {
            s = s.copy(streamingTranscriptionEndpoint = null)
        }

        return s
    }
}
