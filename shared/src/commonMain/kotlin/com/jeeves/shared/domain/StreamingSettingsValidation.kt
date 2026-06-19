package com.jeeves.shared.domain

/**
 * Validation functions for streaming transcription settings.
 */

/**
 * Validates that the chunk interval is within the allowed range [3, 30] seconds.
 */
fun validateChunkInterval(value: Int): Boolean {
    return value in 3..30
}

/**
 * Validates that the overlap window is within the allowed range [0.5, 5.0] seconds.
 */
fun validateOverlapWindow(value: Float): Boolean {
    return value in 0.5f..5.0f
}

/**
 * Validates that the overlap window is strictly less than the chunk interval.
 */
fun validateOverlapLessThanInterval(overlap: Float, interval: Int): Boolean {
    return overlap < interval
}
