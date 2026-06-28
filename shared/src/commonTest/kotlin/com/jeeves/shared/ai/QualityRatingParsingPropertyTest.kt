package com.jeeves.shared.ai

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.ktor.client.*
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Feature: meeting-intelligence, Property 6: Quality rating parsing produces valid scores

/**
 * Property-based test for OllamaClient.parseQualityRating() parsing and clamping behavior.
 *
 * **Validates: Requirements 4.1, 4.2**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class QualityRatingParsingPropertyTest {

    private val config = PropTestConfig(iterations = 100)
    private val client = OllamaClient(HttpClient())

    /**
     * Property 6: Quality rating parsing produces valid scores
     *
     * For any response string with a RATING section containing 4 labelled integer scores,
     * the parsed QualityRating:
     * - Is not null
     * - Has all four scores (pacing, questions, goalSetting, nextSteps) in [1, 5] inclusive
     * - Has overall in [1.0, 5.0]
     *
     * Strategy: Generate 4 random ints in a wide range (-10 to 15) to exercise clamping,
     * build a RATING section text, call parseQualityRating(), and verify constraints.
     *
     * **Validates: Requirements 4.1, 4.2**
     */
    @Test
    fun property6_qualityRatingParsingProducesValidScores() = runTest {
        checkAll(
            config,
            Arb.int(-10..15),
            Arb.int(-10..15),
            Arb.int(-10..15),
            Arb.int(-10..15)
        ) { p, q, g, n ->
            val ratingText = buildString {
                appendLine("Pacing: $p")
                appendLine("Questions: $q")
                appendLine("Goal-Setting: $g")
                appendLine("Summary/Next Steps: $n")
            }

            val rating = client.parseQualityRating(ratingText)

            assertNotNull(rating, "parseQualityRating should not return null for valid 4-label input (p=$p, q=$q, g=$g, n=$n)")

            assertTrue(
                rating.pacing in 1..5,
                "pacing should be in [1,5] but was ${rating.pacing} (input: $p)"
            )
            assertTrue(
                rating.questions in 1..5,
                "questions should be in [1,5] but was ${rating.questions} (input: $q)"
            )
            assertTrue(
                rating.goalSetting in 1..5,
                "goalSetting should be in [1,5] but was ${rating.goalSetting} (input: $g)"
            )
            assertTrue(
                rating.nextSteps in 1..5,
                "nextSteps should be in [1,5] but was ${rating.nextSteps} (input: $n)"
            )
            assertTrue(
                rating.overall in 1.0..5.0,
                "overall should be in [1.0,5.0] but was ${rating.overall}"
            )

            // Verify overall is correctly computed from clamped values
            val clampedP = p.coerceIn(1, 5)
            val clampedQ = q.coerceIn(1, 5)
            val clampedG = g.coerceIn(1, 5)
            val clampedN = n.coerceIn(1, 5)
            val expectedOverall = ((clampedP + clampedQ + clampedG + clampedN) / 4.0 * 10).roundToInt() / 10.0

            assertTrue(
                rating.overall == expectedOverall,
                "overall should be $expectedOverall but was ${rating.overall} (clamped inputs: $clampedP, $clampedQ, $clampedG, $clampedN)"
            )
        }
    }
}
