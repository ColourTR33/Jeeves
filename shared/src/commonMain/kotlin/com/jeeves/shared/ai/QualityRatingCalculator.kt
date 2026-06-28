package com.jeeves.shared.ai

import kotlin.math.roundToInt

/**
 * Pure utility for quality rating calculations.
 * Encapsulates scoring logic independently of the LLM pipeline.
 */
object QualityRatingCalculator {

    /**
     * Calculate overall score as arithmetic mean rounded to 1 decimal place.
     */
    fun calculateOverall(pacing: Int, questions: Int, goalSetting: Int, nextSteps: Int): Double {
        val mean = (pacing + questions + goalSetting + nextSteps) / 4.0
        return (mean * 10).roundToInt() / 10.0
    }

    /**
     * Determine whether quality rating should be generated.
     * Returns false if transcript has fewer than 50 whitespace-separated words.
     */
    fun shouldRate(transcriptionText: String): Boolean {
        return transcriptionText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size >= 50
    }
}
