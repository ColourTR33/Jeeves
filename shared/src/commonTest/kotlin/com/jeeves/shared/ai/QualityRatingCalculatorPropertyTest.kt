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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Feature: meeting-intelligence, Property 8: Short transcription omits quality rating

/**
 * Property-based tests for QualityRatingCalculator.shouldRate() threshold logic.
 *
 * **Validates: Requirements 4.6**
 */
class QualityRatingCalculatorPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    /**
     * Arbitrary for generating a single non-blank word with no whitespace.
     * Words are 1-20 characters long, filtered to exclude blank/whitespace-containing strings.
     */
    private val arbWord = Arb.string(1..20).filter { it.isNotBlank() && !it.contains("\\s".toRegex()) }

    /**
     * Arbitrary that generates a text string with fewer than 50 words.
     * Picks a random word count in [0, 49], then generates that many non-blank words joined by spaces.
     */
    private val arbShortText: Arb<String> = Arb.int(0..49).flatMap { wordCount ->
        if (wordCount == 0) {
            Arb.int(0..0).map { "" }
        } else {
            Arb.list(arbWord, wordCount..wordCount).map { words -> words.joinToString(" ") }
        }
    }

    /**
     * Arbitrary that generates a text string with 50 or more words.
     * Picks a random word count in [50, 150], then generates that many non-blank words joined by spaces.
     */
    private val arbLongText: Arb<String> = Arb.int(50..150).flatMap { wordCount ->
        Arb.list(arbWord, wordCount..wordCount).map { words -> words.joinToString(" ") }
    }

    /**
     * Property 8: Short transcription omits quality rating
     *
     * For any text with fewer than 50 whitespace-separated non-blank tokens,
     * QualityRatingCalculator.shouldRate(text) should return false.
     *
     * Strategy: Generate a random word count in [0, 49], then generate that many
     * random non-blank words joined with space. Verify shouldRate returns false.
     *
     * **Validates: Requirements 4.6**
     */
    @Test
    fun property8_shortTranscription_shouldRateReturnsFalse() = runTest {
        checkAll(config, arbShortText) { text ->
            assertFalse(
                QualityRatingCalculator.shouldRate(text),
                "Text with fewer than 50 words should not be rated. " +
                    "Word count: ${text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}"
            )
        }
    }

    /**
     * Property 8 (complementary): Text with >= 50 words should return true
     *
     * For any text with 50 or more whitespace-separated non-blank tokens,
     * QualityRatingCalculator.shouldRate(text) should return true.
     *
     * **Validates: Requirements 4.6**
     */
    @Test
    fun property8_sufficientTranscription_shouldRateReturnsTrue() = runTest {
        checkAll(config, arbLongText) { text ->
            assertTrue(
                QualityRatingCalculator.shouldRate(text),
                "Text with 50+ words should be rated. " +
                    "Word count: ${text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}"
            )
        }
    }
}
