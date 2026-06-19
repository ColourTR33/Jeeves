package com.jeeves.shared.ui

import com.jeeves.shared.domain.TranscriptionSegment

/**
 * A group of consecutive transcription segments that share the same speaker label.
 */
data class SpeakerGroup(
    val speaker: String?,
    val segments: List<TranscriptionSegment>
)

/**
 * Groups consecutive transcription segments by speaker label.
 *
 * Adjacent segments with the same speaker value (case-sensitive string equality, null == null)
 * are collected into a single [SpeakerGroup]. The resulting list preserves segment order and
 * guarantees that no two adjacent groups share the same speaker label.
 *
 * @param segments the ordered list of transcription segments to group
 * @return a list of [SpeakerGroup] where each group contains a contiguous run of segments
 *         with the same speaker. Returns an empty list if the input is empty.
 */
fun groupBySpeaker(segments: List<TranscriptionSegment>): List<SpeakerGroup> {
    if (segments.isEmpty()) return emptyList()

    val groups = mutableListOf<SpeakerGroup>()
    var currentSpeaker = segments.first().speaker
    var currentSegments = mutableListOf(segments.first())

    for (i in 1 until segments.size) {
        val segment = segments[i]
        if (segment.speaker == currentSpeaker) {
            currentSegments.add(segment)
        } else {
            groups.add(SpeakerGroup(speaker = currentSpeaker, segments = currentSegments))
            currentSpeaker = segment.speaker
            currentSegments = mutableListOf(segment)
        }
    }

    // Add the final group
    groups.add(SpeakerGroup(speaker = currentSpeaker, segments = currentSegments))

    return groups
}
