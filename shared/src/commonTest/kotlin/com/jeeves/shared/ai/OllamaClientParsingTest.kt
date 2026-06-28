package com.jeeves.shared.ai

import com.jeeves.shared.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import io.ktor.client.*
import io.ktor.client.engine.*

/**
 * Unit tests for OllamaClient.parseSummaryResponse() with new sections.
 *
 * Validates Requirements: 2.1, 2.2, 4.1, 4.2, 4.3
 */
class OllamaClientParsingTest {

    // We need an OllamaClient instance to test internal methods.
    // The HttpClient won't be used for parsing tests.
    private val client = OllamaClient(HttpClient())

    // --- FOLLOW_UP_QUESTIONS parsing tests ---

    @Test
    fun parseSummaryResponse_withFollowUpQuestions_parsesBulletItems() {
        val response = """
            SUMMARY:
            A brief meeting summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Question 1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - What about the budget?
            - Have we considered timeline risks?
            - Who owns the deployment?

            RATING:
            Pacing: 4
            Questions: 3
            Goal-Setting: 5
            Summary/Next Steps: 4
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertEquals(3, result.recommendedQuestions.size)
        assertEquals("What about the budget?", result.recommendedQuestions[0])
        assertEquals("Have we considered timeline risks?", result.recommendedQuestions[1])
        assertEquals("Who owns the deployment?", result.recommendedQuestions[2])
    }

    @Test
    fun parseSummaryResponse_followUpQuestionsCappedAt5() {
        val response = """
            SUMMARY:
            Summary here.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - Question 1
            - Question 2
            - Question 3
            - Question 4
            - Question 5
            - Question 6
            - Question 7

            RATING:
            Pacing: 3
            Questions: 3
            Goal-Setting: 3
            Summary/Next Steps: 3
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertEquals(5, result.recommendedQuestions.size)
        assertEquals("Question 1", result.recommendedQuestions[0])
        assertEquals("Question 5", result.recommendedQuestions[4])
    }

    @Test
    fun parseSummaryResponse_noFollowUpQuestionsSection_returnsEmptyList() {
        val response = """
            SUMMARY:
            A meeting summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertEquals(emptyList(), result.recommendedQuestions)
    }

    @Test
    fun parseSummaryResponse_emptyFollowUpQuestions_returnsEmptyList() {
        val response = """
            SUMMARY:
            Summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:

            RATING:
            Pacing: 4
            Questions: 4
            Goal-Setting: 4
            Summary/Next Steps: 4
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertEquals(emptyList(), result.recommendedQuestions)
    }

    // --- RATING parsing tests ---

    @Test
    fun parseSummaryResponse_withValidRating_parsesQualityRating() {
        val response = """
            SUMMARY:
            Meeting summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - Follow up?

            RATING:
            Pacing: 4
            Questions: 3
            Goal-Setting: 5
            Summary/Next Steps: 2
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertNotNull(result.qualityRating)
        assertEquals(4, result.qualityRating!!.pacing)
        assertEquals(3, result.qualityRating!!.questions)
        assertEquals(5, result.qualityRating!!.goalSetting)
        assertEquals(2, result.qualityRating!!.nextSteps)
        assertEquals(3.5, result.qualityRating!!.overall)
    }

    @Test
    fun parseSummaryResponse_ratingValuesClamped() {
        val response = """
            SUMMARY:
            Summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - Follow up?

            RATING:
            Pacing: 0
            Questions: 7
            Goal-Setting: -2
            Summary/Next Steps: 10
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertNotNull(result.qualityRating)
        assertEquals(1, result.qualityRating!!.pacing)
        assertEquals(5, result.qualityRating!!.questions)
        assertEquals(1, result.qualityRating!!.goalSetting)
        assertEquals(5, result.qualityRating!!.nextSteps)
    }

    @Test
    fun parseSummaryResponse_missingRatingCriterion_returnsNull() {
        val response = """
            SUMMARY:
            Summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - Follow up?

            RATING:
            Pacing: 4
            Questions: 3
            Goal-Setting: 5
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertNull(result.qualityRating)
    }

    @Test
    fun parseSummaryResponse_unparseableRatingValue_returnsNull() {
        val response = """
            SUMMARY:
            Summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - Follow up?

            RATING:
            Pacing: 4
            Questions: excellent
            Goal-Setting: 5
            Summary/Next Steps: 3
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertNull(result.qualityRating)
    }

    @Test
    fun parseSummaryResponse_noRatingSection_returnsNullRating() {
        val response = """
            SUMMARY:
            Summary.

            KEY POINTS:
            - Point 1

            ACTION ITEMS:
            - Action 1

            QUESTIONS:
            - Q1

            TAGS:
            #tag1

            FOLLOW_UP_QUESTIONS:
            - Follow up?
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertNull(result.qualityRating)
    }

    @Test
    fun parseSummaryResponse_noNewSections_preservesExistingParsing() {
        val response = """
            SUMMARY:
            The team discussed the Q4 roadmap.

            KEY POINTS:
            - Agreed on priorities
            - Budget approved

            ACTION ITEMS:
            - Create timeline
            - Send invites

            QUESTIONS:
            - When is the deadline?

            TAGS:
            #roadmap #q4-planning
        """.trimIndent()

        val result = client.parseSummaryResponse("rec-1", response, "test-model")

        assertEquals("The team discussed the Q4 roadmap.", result.summary)
        assertEquals(2, result.keyPoints.size)
        assertEquals(2, result.actionItems.size)
        assertEquals(1, result.questions.size)
        assertEquals(listOf("roadmap", "q4-planning"), result.tags)
        assertEquals(emptyList(), result.recommendedQuestions)
        assertNull(result.qualityRating)
    }

    // --- parseQualityRating tests ---

    @Test
    fun parseQualityRating_blankText_returnsNull() {
        assertNull(client.parseQualityRating(""))
        assertNull(client.parseQualityRating("   "))
    }

    @Test
    fun parseQualityRating_allValidScores_returnsRating() {
        val text = """
            Pacing: 4
            Questions: 3
            Goal-Setting: 5
            Summary/Next Steps: 4
        """.trimIndent()

        val rating = client.parseQualityRating(text)

        assertNotNull(rating)
        assertEquals(4, rating.pacing)
        assertEquals(3, rating.questions)
        assertEquals(5, rating.goalSetting)
        assertEquals(4, rating.nextSteps)
        assertEquals(4.0, rating.overall)
    }

    @Test
    fun parseQualityRating_scoresOutOfRange_clamped() {
        val text = """
            Pacing: 0
            Questions: 10
            Goal-Setting: -1
            Summary/Next Steps: 6
        """.trimIndent()

        val rating = client.parseQualityRating(text)

        assertNotNull(rating)
        assertEquals(1, rating.pacing)
        assertEquals(5, rating.questions)
        assertEquals(1, rating.goalSetting)
        assertEquals(5, rating.nextSteps)
    }

    @Test
    fun parseQualityRating_missingCriterion_returnsNull() {
        val text = """
            Pacing: 4
            Questions: 3
            Summary/Next Steps: 4
        """.trimIndent()

        assertNull(client.parseQualityRating(text))
    }

    @Test
    fun parseQualityRating_nonIntegerValue_returnsNull() {
        val text = """
            Pacing: 4
            Questions: three
            Goal-Setting: 5
            Summary/Next Steps: 4
        """.trimIndent()

        assertNull(client.parseQualityRating(text))
    }

    @Test
    fun parseQualityRating_overallScoreCalculation() {
        val text = """
            Pacing: 5
            Questions: 4
            Goal-Setting: 3
            Summary/Next Steps: 1
        """.trimIndent()

        val rating = client.parseQualityRating(text)

        assertNotNull(rating)
        // (5+4+3+1)/4.0 = 13/4.0 = 3.25 → rounds to 3.3
        assertEquals(3.3, rating.overall)
    }
}
