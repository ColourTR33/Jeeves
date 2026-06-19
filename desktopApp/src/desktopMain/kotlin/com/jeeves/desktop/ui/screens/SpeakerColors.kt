package com.jeeves.desktop.ui.screens

import androidx.compose.ui.graphics.Color
import com.jeeves.shared.domain.TranscriptionSegment

/**
 * A fixed palette of soft background tones for speaker colour coding.
 * Colours cycle when the number of unique speakers exceeds palette size.
 */
val SPEAKER_PALETTE = listOf(
    Color(0xFFE3F2FD), // Blue 50
    Color(0xFFFCE4EC), // Pink 50
    Color(0xFFF3E5F5), // Purple 50
    Color(0xFFE8F5E9), // Green 50
    Color(0xFFFFF3E0), // Orange 50
    Color(0xFFE0F7FA), // Cyan 50
)

/**
 * Builds a mapping from speaker label to palette colour, assigning colours
 * in order of each speaker's first appearance in the segment list.
 *
 * The Nth unique speaker gets the colour at index (N-1) mod palette_size,
 * ensuring the same speaker always maps to the same colour within a transcription.
 */
fun buildSpeakerColorMap(segments: List<TranscriptionSegment>): Map<String, Color> {
    val speakers = segments.mapNotNull { it.speaker }.distinct()
    return speakers.mapIndexed { index, speaker ->
        speaker to SPEAKER_PALETTE[index % SPEAKER_PALETTE.size]
    }.toMap()
}
