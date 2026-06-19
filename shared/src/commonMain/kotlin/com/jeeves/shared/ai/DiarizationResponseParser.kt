package com.jeeves.shared.ai

import com.jeeves.shared.domain.DiarizationMode
import com.jeeves.shared.domain.TranscriptionSegment

/**
 * Parses speaker diarization data from whisper-server responses.
 * Supports both --diarize (structured speaker field) and --tinydiarize (text markers) modes.
 */
class DiarizationResponseParser {

    companion object {
        private const val TAG = "DiarizationResponseParser"
        private const val SPEAKER_TURN_MARKER = "[SPEAKER_TURN]"
    }

    /**
     * Parse verbose_json response segments, extracting speaker labels based on configured mode.
     */
    fun parseSegments(
        segments: List<WhisperSegment>,
        mode: DiarizationMode
    ): List<TranscriptionSegment> {
        return when (mode) {
            DiarizationMode.DIARIZE -> parseDiarizeMode(segments)
            DiarizationMode.TINYDIARIZE -> parseTinyDiarizeMode(segments)
        }
    }

    /**
     * In diarize mode, the server provides a "speaker" field ("0", "1", "?") per segment.
     * Map non-empty speaker strings to "Speaker {value}" (e.g., "0" -> "Speaker 0").
     * Null or empty speaker values produce null.
     */
    private fun parseDiarizeMode(segments: List<WhisperSegment>): List<TranscriptionSegment> {
        return segments.mapIndexed { index, segment ->
            val speaker = mapSpeakerField(segment.speaker, index)
            TranscriptionSegment(
                startMs = (segment.start * 1000).toLong(),
                endMs = (segment.end * 1000).toLong(),
                text = segment.text,
                speaker = speaker
            )
        }
    }

    /**
     * Maps a raw speaker field value to a display label.
     * Non-empty strings -> "Speaker {value}" (including "?" -> "Speaker ?")
     * Null or empty -> null
     */
    private fun mapSpeakerField(speaker: String?, @Suppress("UNUSED_PARAMETER") segmentIndex: Int): String? {
        if (speaker.isNullOrEmpty()) {
            return null
        }
        return "Speaker $speaker"
    }

    /**
     * In tinydiarize mode, segments may contain [SPEAKER_TURN] markers in text.
     * Track speaker turns and assign incremental labels ("Speaker 0", "Speaker 1", ...).
     * Markers are stripped from the output text.
     *
     * Logic:
     * - Start with speaker counter at 0
     * - Each [SPEAKER_TURN] marker increments the counter
     * - If a marker is at the start of a segment, that segment's speaker is the NEW speaker (post-increment)
     * - If marker is not at the start, the segment uses the speaker before the first increment
     * - Multiple markers in one segment = multiple speaker increments
     */
    private fun parseTinyDiarizeMode(segments: List<WhisperSegment>): List<TranscriptionSegment> {
        var currentSpeakerIndex = 0
        val result = mutableListOf<TranscriptionSegment>()

        for (segment in segments) {
            val text = segment.text
            val markerCount = countMarkers(text)

            if (markerCount == 0) {
                // No markers - use current speaker, text unchanged
                result.add(
                    TranscriptionSegment(
                        startMs = (segment.start * 1000).toLong(),
                        endMs = (segment.end * 1000).toLong(),
                        text = text,
                        speaker = "Speaker $currentSpeakerIndex"
                    )
                )
            } else {
                val startsWithMarker = text.startsWith(SPEAKER_TURN_MARKER)
                val speakerForSegment: Int

                if (startsWithMarker) {
                    // Marker at start: increment first, then assign new speaker to this segment
                    currentSpeakerIndex++
                    speakerForSegment = currentSpeakerIndex
                    // Remaining markers (if any) increment further for subsequent segments
                    currentSpeakerIndex += (markerCount - 1)
                } else {
                    // Marker not at start: this segment uses current speaker
                    speakerForSegment = currentSpeakerIndex
                    // All markers increment for subsequent segments
                    currentSpeakerIndex += markerCount
                }

                val cleanedText = text.replace(SPEAKER_TURN_MARKER, "")

                result.add(
                    TranscriptionSegment(
                        startMs = (segment.start * 1000).toLong(),
                        endMs = (segment.end * 1000).toLong(),
                        text = cleanedText,
                        speaker = "Speaker $speakerForSegment"
                    )
                )
            }
        }

        return result
    }

    private fun countMarkers(text: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val index = text.indexOf(SPEAKER_TURN_MARKER, startIndex)
            if (index == -1) break
            count++
            startIndex = index + SPEAKER_TURN_MARKER.length
        }
        return count
    }
}
