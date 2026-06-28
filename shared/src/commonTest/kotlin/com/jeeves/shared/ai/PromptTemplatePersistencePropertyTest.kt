package com.jeeves.shared.ai

import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.domain.MeetingTemplate
import com.jeeves.shared.domain.SettingsRepository
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Feature: meeting-intelligence, Property 1: Prompt template persistence round-trip

/**
 * Property-based test for prompt template persistence round-trip.
 *
 * For any valid string and any MeetingTemplate, saving the prompt template
 * to settings then loading it back should return the identical string.
 *
 * **Validates: Requirements 1.2**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class PromptTemplatePersistencePropertyTest {

    private val config = PropTestConfig(iterations = 100)

    /**
     * In-memory stub for SettingsRepository to enable testing without file I/O.
     */
    private class InMemorySettingsRepository(
        private var settings: AppSettings = AppSettings()
    ) : SettingsRepository {
        override suspend fun getSettings(): AppSettings = settings
        override suspend fun saveSettings(settings: AppSettings) {
            this.settings = settings
        }
    }

    /**
     * Property 1: Prompt template persistence round-trip
     *
     * For any valid string and any MeetingTemplate:
     * 1. Create an InMemorySettingsRepository
     * 2. Save AppSettings with promptTemplates = mapOf(template to string)
     * 3. Load settings back
     * 4. Verify settings.promptTemplates[template] == the original string
     *
     * **Validates: Requirements 1.2**
     */
    @Test
    fun property1_promptTemplatePersistenceRoundTrip_preservesIdenticalString() = runTest {
        checkAll(config, Arb.enum<MeetingTemplate>(), Arb.string(0..200)) { template, promptString ->
            val settingsRepo = InMemorySettingsRepository()

            // Save settings with the prompt template
            val settingsToSave = AppSettings(promptTemplates = mapOf(template to promptString))
            settingsRepo.saveSettings(settingsToSave)

            // Load settings back
            val loadedSettings = settingsRepo.getSettings()

            // Verify the loaded prompt template is identical to the saved one
            assertEquals(
                promptString,
                loadedSettings.promptTemplates[template],
                "For template=$template, saved prompt should be identical after round-trip. " +
                    "Expected: \"$promptString\", Got: \"${loadedSettings.promptTemplates[template]}\""
            )
        }
    }
}
