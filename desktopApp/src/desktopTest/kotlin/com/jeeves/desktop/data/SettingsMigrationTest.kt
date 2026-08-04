package com.jeeves.desktop.data

import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.AiEndpointType
import com.jeeves.shared.domain.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * Tests for FileSettingsRepository settings migration.
 * Ensures corrupted settings with port 8179 are properly cleaned up.
 */
class SettingsMigrationTest {

    private lateinit var tempDir: File
    private lateinit var settingsFile: File
    private lateinit var repository: FileSettingsRepository

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "settings-migration-test-${System.nanoTime()}")
        tempDir.mkdirs()

        // Override the settings file location for testing by changing user.home
        System.setProperty("user.home", tempDir.absolutePath)

        settingsFile = File(tempDir, "Jeeves/settings.json")
        settingsFile.parentFile.mkdirs()

        repository = FileSettingsRepository()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Test: streamingTranscriptionEndpoint with port 8179 is nullified on load.
     */
    @Test
    fun migration_removesStreamingEndpointWith8179() = runTest {
        // Write settings with corrupted streamingTranscriptionEndpoint pointing to 8179
        val corruptedSettings = AppSettings(
            streamingTranscriptionEndpoint = AiEndpointConfig(
                name = "Corrupted Streaming",
                baseUrl = "http://localhost:8179",
                modelName = "whisper-small",
                type = AiEndpointType.WHISPER_TRANSCRIPTION
            )
        )
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), corruptedSettings))

        // Load through repository (should trigger migration)
        val loaded = repository.getSettings()

        // Verify streamingTranscriptionEndpoint is null (removed by migration)
        assertNull(
            loaded.streamingTranscriptionEndpoint,
            "streamingTranscriptionEndpoint with port 8179 should be nullified"
        )
    }

    /**
     * Test: transcriptionEndpoint with port 8179 is fixed to 8178 on load.
     */
    @Test
    fun migration_fixesTranscriptionEndpointWith8179() = runTest {
        // Write settings with corrupted transcriptionEndpoint pointing to 8179
        val corruptedSettings = AppSettings(
            transcriptionEndpoint = AiEndpointConfig(
                name = "Local Whisper",
                baseUrl = "http://localhost:8179",
                modelName = "whisper-small",
                type = AiEndpointType.WHISPER_TRANSCRIPTION
            )
        )
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), corruptedSettings))

        // Load through repository (should trigger migration)
        val loaded = repository.getSettings()

        // Verify transcriptionEndpoint is fixed to 8178
        assertEquals(
            "http://localhost:8178",
            loaded.transcriptionEndpoint.baseUrl,
            "transcriptionEndpoint with port 8179 should be fixed to 8178"
        )
    }

    /**
     * Test: Saving settings also applies migration (no 8179 gets persisted).
     */
    @Test
    fun migration_appliedOnSave() = runTest {
        // Start with default settings
        val defaultSettings = AppSettings()
        repository.saveSettings(defaultSettings)

        // Manually corrupt the file
        val corruptedSettings = defaultSettings.copy(
            transcriptionEndpoint = defaultSettings.transcriptionEndpoint.copy(
                baseUrl = "http://localhost:8179"
            )
        )
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), corruptedSettings))

        // Load (triggers migration) and save
        val loaded = repository.getSettings()
        repository.saveSettings(loaded)

        // Read raw JSON to verify 8179 is not in the file
        val rawJson = settingsFile.readText()
        assertFalse(
            rawJson.contains(":8179"),
            "Saved settings should not contain port 8179"
        )
    }

    /**
     * Test: Valid settings without 8179 are not modified.
     */
    @Test
    fun migration_preservesValidSettings() = runTest {
        // Write valid settings
        val validSettings = AppSettings(
            transcriptionEndpoint = AiEndpointConfig(
                name = "Local Whisper",
                baseUrl = "http://localhost:8178",
                modelName = "whisper-small",
                type = AiEndpointType.WHISPER_TRANSCRIPTION
            ),
            summarizationEndpoint = AiEndpointConfig(
                name = "Local Ollama",
                baseUrl = "http://localhost:11434",
                modelName = "qwen3:8b",
                type = AiEndpointType.LLM_SUMMARIZATION
            )
        )
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), validSettings))

        // Load through repository
        val loaded = repository.getSettings()

        // Verify settings are unchanged
        assertEquals(
            "http://localhost:8178",
            loaded.transcriptionEndpoint.baseUrl,
            "Valid transcriptionEndpoint should be preserved"
        )
        assertEquals(
            "http://localhost:11434",
            loaded.summarizationEndpoint.baseUrl,
            "Valid summarizationEndpoint should be preserved"
        )
    }
}
