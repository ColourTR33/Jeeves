package com.jeeves.shared.ai

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

// Feature: meeting-intelligence, Property 7: Overall score is arithmetic mean

/**
 * Property-based test for QualityRatingCalculator.calculateOverall().
 *
 * **Validates: Requirements 4.3**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class QualityRatingOverallScorePropertyTest {

    private val config = PropTestConfig(iterations = 100)

    /**
     * Property 7: Overall score is arithmetic mean
     *
     * For any four ints p, q, g, n each in [1, 5],
     * calculateOverall(p, q, g, n) equals (p + q + g + n) / 4.0 rounded to 1 decimal place.
     *
     * **Validates: Requirements 4.3**
     */
    @Test
    fun property7_overallScoreIsArithmeticMean() = runTest {
        checkAll(
            config,
            Arb.int(1..5),
            Arb.int(1..5),
            Arb.int(1..5),
            Arb.int(1..5)
        ) { p, q, g, n ->
            val expected = ((p + q + g + n) / 4.0 * 10).roundToInt() / 10.0
            val actual = QualityRatingCalculator.calculateOverall(p, q, g, n)
            assertEquals(
                expected,
                actual,
                "calculateOverall($p, $q, $g, $n) should be $expected but was $actual"
            )
        }
    }
}
