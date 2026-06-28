package com.jeeves.shared.ai

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.ktor.client.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

// Feature: meeting-intelligence, Property 3: Recommended questions parsing and capping

/**
 * Property-based test for recommended questions parsing and capping.
 *
 * For any well-formed LLM response text containing a FOLLOW_UP_QUESTIONS section
 * with N bullet-point items (0-10), the parsed `recommendedQuestions` list should
 * contain exactly min(N, 5) items, and each item should be a non-empty string.
 *
 * **Validates: Requirements 2.1, 2.2**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class RecommendedQuestionsParsingPropertyTest {

    private val config = PropTestConfig(iterations = 100)
    private val client = OllamaClient(HttpClient())

    /**
     * Arbitrary for generating a single non-empty question string without line breaks.
     * Strings are 5-50 characters, filtered to exclude blank strings and those with newlines.
     */
    private val arbQuestionString = Arb.string(5..50)
        .filter { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }

    /**
     * Arbitrary that generates a list of 0-10 question strings.
     * Picks a random count in [0, 10], then generates that many non-empty question strings.
     */
    private val arbQuestionsList: Arb<List<String>> = Arb.int(0..10).flatMap { count ->
        if (count == 0) {
            Arb.int(0..0).map { emptyList() }
        } else {
            Arb.list(arbQuestionString, count..count)
        }
    }

    /**
     * Builds a well-formed response string with all required sections,
     * including a FOLLOW_UP_QUESTIONS section with the given question items as bullet points.
     */
    private fun buildResponseWithQuestions(questions: List<String>): String {
        return buildString {
            appendLine("SUMMARY:")
            appendLine("This is a test meeting summary.")
            appendLine()
            appendLine("KEY POINTS:")
            appendLine("- Key point 1")
            appendLine()
            appendLine("ACTION ITEMS:")
            appendLine("- Action item 1")
            appendLine()
            appendLine("QUESTIONS:")
            appendLine("- Question raised during meeting")
            appendLine()
            appendLine("TAGS:")
            appendLine("#test #meeting")
            appendLine()
            appendLine("FOLLOW_UP_QUESTIONS:")
            for (q in questions) {
                appendLine("- $q")
            }
            appendLine()
            appendLine("RATING:")
            appendLine("Pacing: 4")
            appendLine("Questions: 3")
            appendLine("Goal-Setting: 5")
            appendLine("Summary/Next Steps: 4")
        }
    }

    /**
     * Property 3: Recommended questions parsing and capping
     *
     * For any response string containing a FOLLOW_UP_QUESTIONS section with N bullet items (0-10):
     * - The parsed `recommendedQuestions` list has length == min(N, 5)
     * - The parsed `recommendedQuestions` list has length ≤ 5
     * - All items in the list are non-empty strings
     *
     * **Validates: Requirements 2.1, 2.2**
     */
    @Test
    fun property3_recommendedQuestionsParsedAndCappedAt5() = runTest {
        checkAll(config, arbQuestionsList) { questions ->
            val response = buildResponseWithQuestions(questions)
            val result = client.parseSummaryResponse("test-recording", response, "test-model")

            val expectedCount = minOf(questions.size, 5)

            // Property: parsed list length is exactly min(N, 5)
            assertEquals(
                expectedCount,
                result.recommendedQuestions.size,
                "For ${questions.size} input questions, expected $expectedCount parsed but got ${result.recommendedQuestions.size}"
            )

            // Property: parsed list length ≤ 5
            assertTrue(
                result.recommendedQuestions.size <= 5,
                "recommendedQuestions should be capped at 5, got ${result.recommendedQuestions.size}"
            )

            // Property: all items are non-empty strings
            result.recommendedQuestions.forEach { question ->
                assertTrue(
                    question.isNotEmpty(),
                    "Each recommended question should be non-empty, got empty string"
                )
            }
        }
    }
}
