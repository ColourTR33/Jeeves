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
                json.decodeFromString<AppSettings>(settingsFile.readText())
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            AppSettings()
        }
    }

    override suspend fun saveSettings(settings: AppSettings) {
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
    }
}
