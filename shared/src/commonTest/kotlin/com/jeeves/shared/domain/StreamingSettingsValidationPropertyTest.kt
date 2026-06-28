package com.jeeves.shared.domain

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property-based tests for streaming settings validation functions.
 *
 * **Validates: Requirements 6.2, 6.3, 6.7**
 */
class StreamingSettingsValidationPropertyTest {

    private val config = PropTestConfig(iterations = 200)

    /**
     * Property 5: Settings Range Validation — chunkIntervalSeconds
     *
     * For any integer, validateChunkInterval returns true iff value is in [3, 30].
     *
     * **Validates: Requirements 6.2**
     */
    @Test
    fun property5_validateChunkInterval_returnsTrueIffInRange() = runTest {
        checkAll(config, Arb.int(-100..100)) { value ->
            val expected = value in 3..30
            assertEquals(
                expected,
                validateChunkInterval(value),
                "validateChunkInterval($value) should be $expected"
            )
        }
    }

    /**
     * Property 5: Settings Range Validation — overlapWindowSeconds
     *
     * For any float, validateOverlapWindow returns true iff value is in [0.5, 5.0].
     *
     * **Validates: Requirements 6.3**
     */
    @Test
    fun property5_validateOverlapWindow_returnsTrueIffInRange() = runTest {
        checkAll(config, Arb.float(min = -10.0f, max = 20.0f)) { value ->
            val expected = value in 0.5f..5.0f
            assertEquals(
                expected,
                validateOverlapWindow(value),
                "validateOverlapWindow($value) should be $expected"
            )
        }
    }

    /**
     * Property 6: Overlap Must Be Less Than Interval
     *
     * For any pair (overlap, interval), cross-field validation returns valid
     * iff overlap < interval.
     *
     * **Validates: Requirements 6.7**
     */
    @Test
    fun property6_overlapMustBeLessThanInterval() = runTest {
        checkAll(config, Arb.float(min = 0.0f, max = 35.0f), Arb.int(1..35)) { overlap, interval ->
            val expected = overlap < interval
            assertEquals(
                expected,
                validateOverlapLessThanInterval(overlap, interval),
                "validateOverlapLessThanInterval($overlap, $interval) should be $expected"
            )
        }
    }
}
