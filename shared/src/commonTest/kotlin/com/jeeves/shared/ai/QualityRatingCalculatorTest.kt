package com.jeeves.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for QualityRatingCalculator.
 *
 * Validates Requirements: 4.3, 4.6
 */
class QualityRatingCalculatorTest {

    // --- calculateOverall tests ---

    @Test
    fun calculateOverall_allSameValues_returnsExactValue() {
        // (3+3+3+3)/4.0 = 3.0
        assertEquals(3.0, QualityRatingCalculator.calculateOverall(3, 3, 3, 3))
    }

    @Test
    fun calculateOverall_allMinValues_returns1() {
        // (1+1+1+1)/4.0 = 1.0
        assertEquals(1.0, QualityRatingCalculator.calculateOverall(1, 1, 1, 1))
    }

    @Test
    fun calculateOverall_allMaxValues_returns5() {
        // (5+5+5+5)/4.0 = 5.0
        assertEquals(5.0, QualityRatingCalculator.calculateOverall(5, 5, 5, 5))
    }

    @Test
    fun calculateOverall_mixedValues_roundsTo1Decimal() {
        // (4+3+5+2)/4.0 = 14/4.0 = 3.5
        assertEquals(3.5, QualityRatingCalculator.calculateOverall(4, 3, 5, 2))
    }

    @Test
    fun calculateOverall_resultRequiringRounding() {
        // (1+2+3+4)/4.0 = 10/4.0 = 2.5
        assertEquals(2.5, QualityRatingCalculator.calculateOverall(1, 2, 3, 4))
    }

    @Test
    fun calculateOverall_nonTrivialRounding() {
        // (5+4+3+1)/4.0 = 13/4.0 = 3.25 → rounds to 3.3
        assertEquals(3.3, QualityRatingCalculator.calculateOverall(5, 4, 3, 1))
    }

    @Test
    fun calculateOverall_anotherRoundingCase() {
        // (1+1+1+2)/4.0 = 5/4.0 = 1.25 → rounds to 1.3
        assertEquals(1.3, QualityRatingCalculator.calculateOverall(1, 1, 1, 2))
    }

    // --- shouldRate tests ---

    @Test
    fun shouldRate_emptyString_returnsFalse() {
        assertFalse(QualityRatingCalculator.shouldRate(""))
    }

    @Test
    fun shouldRate_singleWord_returnsFalse() {
        assertFalse(QualityRatingCalculator.shouldRate("hello"))
    }

    @Test
    fun shouldRate_49Words_returnsFalse() {
        val words = (1..49).joinToString(" ") { "word$it" }
        assertFalse(QualityRatingCalculator.shouldRate(words))
    }

    @Test
    fun shouldRate_exactly50Words_returnsTrue() {
        val words = (1..50).joinToString(" ") { "word$it" }
        assertTrue(QualityRatingCalculator.shouldRate(words))
    }

    @Test
    fun shouldRate_moreThan50Words_returnsTrue() {
        val words = (1..100).joinToString(" ") { "word$it" }
        assertTrue(QualityRatingCalculator.shouldRate(words))
    }

    @Test
    fun shouldRate_multipleSpacesBetweenWords_countsCorrectly() {
        // Extra whitespace should not inflate word count
        val words = (1..30).joinToString("   ") { "word$it" }
        assertFalse(QualityRatingCalculator.shouldRate(words))
    }

    @Test
    fun shouldRate_newlinesAndTabs_countsCorrectly() {
        val words = (1..50).joinToString("\n") { "word$it" }
        assertTrue(QualityRatingCalculator.shouldRate(words))
    }

    @Test
    fun shouldRate_leadingTrailingWhitespace_ignored() {
        val words = "   " + (1..50).joinToString(" ") { "word$it" } + "   "
        assertTrue(QualityRatingCalculator.shouldRate(words))
    }
}
