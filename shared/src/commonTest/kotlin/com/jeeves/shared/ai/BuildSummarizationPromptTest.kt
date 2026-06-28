package com.jeeves.shared.ai

import com.jeeves.shared.domain.*
import io.ktor.client.*
import io.ktor.client.engine.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for OllamaClient.buildSummarizationPrompt().
 *
 * Validates Requirements: 1.4, 2.1, 2.2, 2.5, 4.1, 4.6
 */
class BuildSummarizationPromptTest {

    private val client = OllamaClient(
        httpClient = HttpClient()
    )

    private fun makeTranscription(text: String) = TranscriptionResult(
        recordingId = "test-id",
        text = text,
        segments = emptyList()
    )

    private val shortText = "This is a short meeting."
    private val longText = (1..60).joinToString(" ") { "word$it" }

    // --- Custom prompt template usage ---

    @Test
    fun usesCustomPromptTemplate_whenNonEmpty() {
        val customPrompt = "Summarize this meeting as bullet points only."
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = customPrompt,
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertTrue(result.startsWith(customPrompt), "Prompt should start with custom template")
    }

    @Test
    fun usesDefaultPrompt_whenTemplateIsEmpty() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "Please summarise the following meeting transcription")
    }

    @Test
    fun usesDefaultPrompt_whenTemplateIsWhitespaceOnly() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "   \t\n  ",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "Please summarise the following meeting transcription")
    }

    // --- FOLLOW_UP_QUESTIONS section ---

    @Test
    fun alwaysAppendsFollowUpQuestionsSection() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "FOLLOW_UP_QUESTIONS:")
        assertContains(result, "suggest 1-5 follow-up questions")
    }

    @Test
    fun followUpQuestionsSection_includedWithCustomTemplate() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "Custom instructions here.",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "FOLLOW_UP_QUESTIONS:")
    }

    // --- INTERVIEW template guidance ---

    @Test
    fun interviewTemplate_addsAssessmentGapsGuidance() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.INTERVIEW
        )
        assertContains(result, "candidate assessment gaps")
        assertContains(result, "unexplored competency areas")
    }

    @Test
    fun nonInterviewTemplate_doesNotAddAssessmentGapsGuidance() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertFalse(result.contains("candidate assessment gaps"))
    }

    @Test
    fun standupTemplate_doesNotAddAssessmentGapsGuidance() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.STANDUP
        )
        assertFalse(result.contains("candidate assessment gaps"))
    }

    // --- RATING section conditional inclusion ---

    @Test
    fun ratingSection_includedForLongTranscriptions() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(longText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "RATING:")
        assertContains(result, "Pacing: [1-5]")
        assertContains(result, "Questions: [1-5]")
        assertContains(result, "Goal-Setting: [1-5]")
        assertContains(result, "Summary/Next Steps: [1-5]")
    }

    @Test
    fun ratingSection_omittedForShortTranscriptions() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertFalse(result.contains("RATING:"))
        assertFalse(result.contains("Pacing: [1-5]"))
    }

    @Test
    fun ratingSection_omittedForExactly49Words() {
        val text49Words = (1..49).joinToString(" ") { "word$it" }
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(text49Words),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertFalse(result.contains("RATING:"))
    }

    // --- Transcription text is always appended ---

    @Test
    fun alwaysAppendsTranscriptionText() {
        val transcriptionText = "The meeting discussed important topics."
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(transcriptionText),
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "TRANSCRIPTION:")
        assertContains(result, transcriptionText)
    }

    // --- Description and attachments still work ---

    @Test
    fun descriptionIncludedWhenProvided() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            description = "Weekly sprint review",
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "MEETING CONTEXT/AGENDA:")
        assertContains(result, "Weekly sprint review")
    }

    @Test
    fun attachmentNoteIncludedWhenPositive() {
        val result = client.buildSummarizationPrompt(
            transcription = makeTranscription(shortText),
            attachmentCount = 3,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL
        )
        assertContains(result, "3 screenshot(s) were captured")
    }
}
