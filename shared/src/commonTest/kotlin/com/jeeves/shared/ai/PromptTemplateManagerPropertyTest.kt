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

// Feature: meeting-intelligence, Property 2: Effective prompt selection with whitespace fallback

/**
 * Property-based test for PromptTemplateManager effective prompt selection.
 *
 * **Validates: Requirements 1.4, 1.5**
 */
class PromptTemplateManagerPropertyTest {

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
     * Property 2: Effective prompt selection with whitespace fallback
     *
     * For any MeetingTemplate and any stored string:
     * - If stored string is non-blank (not null and not only whitespace): effective prompt == stored string
     * - If stored string is blank or null: effective prompt == getDefaultPrompt(template)
     *
     * **Validates: Requirements 1.4, 1.5**
     */
    @Test
    fun property2_effectivePromptSelection_usesStoredIfNonBlank_elseDefault() = runTest {
        checkAll(config, Arb.enum<MeetingTemplate>(), Arb.string(0..100)) { template, storedPrompt ->
            val settingsRepo = InMemorySettingsRepository(
                AppSettings(promptTemplates = mapOf(template to storedPrompt))
            )
            val manager = PromptTemplateManager(settingsRepo)

            val effective = manager.getEffectivePrompt(template)
            val expectedDefault = manager.getDefaultPrompt(template)

            if (storedPrompt.isBlank()) {
                assertEquals(
                    expectedDefault,
                    effective,
                    "For template=$template with blank stored prompt \"$storedPrompt\", " +
                        "effective should equal default"
                )
            } else {
                assertEquals(
                    storedPrompt,
                    effective,
                    "For template=$template with non-blank stored prompt, " +
                        "effective should equal stored prompt"
                )
            }
        }
    }
}
