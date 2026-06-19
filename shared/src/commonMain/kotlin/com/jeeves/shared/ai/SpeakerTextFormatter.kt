package com.jeeves.shared.ai

import com.jeeves.shared.domain.TranscriptionSegment

/**
 * Formats transcription segments with speaker labels for summarization.
 * Segments with a non-null speaker field get a "{speaker}: " prefix.
 * Segments with a null speaker field are included without any prefix.
 * Original segment order is preserved.
 */
fun formatWithSpeakers(segments: List<TranscriptionSegment>): String {
    return segments.joinToString("\n") { segment ->
        if (segment.speaker != null) {
            "${segment.speaker}: ${segment.text}"
        } else {
            segment.text
        }
    }
}

/**
 * Returns true if any segment in the list has a non-null speaker label.
 * Useful for determining whether to use speaker-attributed formatting.
 */
fun hasSpeakerLabels(segments: List<TranscriptionSegment>): Boolean {
    return segments.any { it.speaker != null }
}
