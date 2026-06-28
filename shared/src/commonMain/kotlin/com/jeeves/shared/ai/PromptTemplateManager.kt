package com.jeeves.shared.ai

import com.jeeves.shared.domain.MeetingTemplate
import com.jeeves.shared.domain.SettingsRepository

/**
 * Resolves the effective prompt for a given MeetingTemplate.
 * Loads custom prompts from settings, falling back to hardcoded defaults when
 * the stored prompt is null or blank.
 */
class PromptTemplateManager(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Returns the effective prompt for the given template type.
     * Falls back to default if the stored prompt is null or blank.
     */
    suspend fun getEffectivePrompt(template: MeetingTemplate): String {
        val settings = settingsRepository.getSettings()
        val stored = settings.promptTemplates[template]
        return if (stored.isNullOrBlank()) getDefaultPrompt(template) else stored
    }

    /**
     * Returns the hardcoded default prompt for the given template type.
     * If a template has no specific default (shouldn't happen), falls back to GENERAL.
     */
    fun getDefaultPrompt(template: MeetingTemplate): String {
        return DEFAULT_PROMPTS[template] ?: DEFAULT_PROMPTS[MeetingTemplate.GENERAL]!!
    }

    companion object {
        val DEFAULT_PROMPTS: Map<MeetingTemplate, String> = mapOf(
            MeetingTemplate.GENERAL to """
                |Summarise this meeting transcription. Provide:
                |1. A concise summary (2-3 paragraphs)
                |2. Key points discussed (bullet points)
                |3. Action items identified with owners where possible (bullet points)
                |4. Questions raised during the meeting (bullet points)
                |5. Hashtag tags for categorizing this meeting
            """.trimMargin(),

            MeetingTemplate.STANDUP to """
                |Summarise this standup meeting transcription using the following structure:
                |1. For each participant, extract:
                |   - Yesterday: what they completed
                |   - Today: what they plan to work on
                |   - Blockers: any impediments or issues raised
                |2. Shared announcements or discussion points
                |3. Action items to resolve blockers
                |4. Hashtag tags for categorizing this meeting
            """.trimMargin(),

            MeetingTemplate.ONE_ON_ONE to """
                |Summarise this one-on-one meeting transcription. Provide:
                |1. Discussion topics covered
                |2. Key decisions made
                |3. Action items with owners
                |4. Feedback given or received (both positive and constructive)
                |5. Follow-up items for next meeting
                |6. Hashtag tags for categorizing this meeting
            """.trimMargin(),

            MeetingTemplate.INTERVIEW to """
                |Summarise this interview transcription. Provide:
                |1. Candidate responses to key questions (organized by topic)
                |2. Notable impressions (strengths, concerns, communication style)
                |3. Technical or domain knowledge demonstrated
                |4. Cultural fit indicators
                |5. Overall recommendation (hire/no-hire/further discussion) with reasoning
                |6. Suggested follow-up questions for next round
                |7. Hashtag tags for categorizing this meeting
            """.trimMargin(),

            MeetingTemplate.BRAINSTORM to """
                |Summarise this brainstorming session transcription. Provide:
                |1. All ideas generated (grouped by theme or category)
                |2. Key themes that emerged
                |3. Ideas that received the most discussion or enthusiasm
                |4. Potential next steps for the most promising ideas
                |5. Open questions or areas needing further exploration
                |6. Hashtag tags for categorizing this meeting
            """.trimMargin()
        )
    }
}
