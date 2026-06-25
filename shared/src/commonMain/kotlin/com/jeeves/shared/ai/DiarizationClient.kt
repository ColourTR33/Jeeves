package com.jeeves.shared.ai

import com.jeeves.shared.domain.TranscriptionResult
import com.jeeves.shared.domain.TranscriptionSegment
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Client for the local pyannote diarization server.
 * Sends audio files to the server and receives speaker turn timestamps.
 * All processing is on-device — nothing leaves the machine.
 */
class DiarizationClient(
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Response from the diarization server.
     */
    @Serializable
    data class DiarizationResponse(
        val segments: List<DiarizationSegment>,
        val num_speakers: Int = 0
    )

    @Serializable
    data class DiarizationSegment(
        val speaker: String,
        val start: Double,
        val end: Double
    )

    /**
     * Send an audio file to the diarization server and get speaker segments back.
     *
     * @param audioFilePath path to the WAV file to diarize
     * @param serverUrl base URL of the diarization server (e.g. http://localhost:8180)
     * @return list of speaker segments, or empty list on failure
     */
    suspend fun diarize(audioFilePath: String, serverUrl: String): List<DiarizationSegment> {
        return try {
            val file = File(audioFilePath)
            if (!file.exists()) {
                AppLogger.error("DiarizationClient", "Audio file not found: $audioFilePath")
                return emptyList()
            }

            val response = httpClient.submitFormWithBinaryData(
                url = "$serverUrl/v1/diarize",
                formData = formData {
                    append("file", file.readBytes(), Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        append(HttpHeaders.ContentType, "audio/wav")
                    })
                }
            )

            if (response.status.isSuccess()) {
                val body: String = response.body()
                val diarizationResponse = json.decodeFromString<DiarizationResponse>(body)
                AppLogger.info(
                    "DiarizationClient",
                    "Diarization complete: ${diarizationResponse.segments.size} turns, " +
                        "${diarizationResponse.num_speakers} speakers"
                )
                diarizationResponse.segments
            } else {
                val errorBody: String = response.body()
                AppLogger.error("DiarizationClient", "Server error ${response.status}: $errorBody")
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.error("DiarizationClient", "Diarization request failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Align diarization speaker labels with transcription segments.
     *
     * For each transcription segment, determines which speaker was active at the
     * segment's midpoint. If a segment spans multiple speakers, the speaker with
     * the most overlap wins.
     *
     * @param transcription the existing transcription with timestamped segments
     * @param speakerSegments speaker turn boundaries from pyannote
     * @return updated transcription with speaker fields populated
     */
    fun alignSpeakers(
        transcription: TranscriptionResult,
        speakerSegments: List<DiarizationSegment>
    ): TranscriptionResult {
        if (speakerSegments.isEmpty() || transcription.segments.isEmpty()) {
            return transcription
        }

        val alignedSegments = transcription.segments.map { segment ->
            val segStartSec = segment.startMs / 1000.0
            val segEndSec = segment.endMs / 1000.0

            // Find the speaker with the most overlap for this segment
            val speaker = findDominantSpeaker(segStartSec, segEndSec, speakerSegments)
            segment.copy(speaker = speaker)
        }

        return transcription.copy(segments = alignedSegments)
    }

    /**
     * Given a time range, find which speaker has the most overlap with it.
     * Falls back to midpoint lookup if no overlap is found.
     */
    private fun findDominantSpeaker(
        startSec: Double,
        endSec: Double,
        speakerSegments: List<DiarizationSegment>
    ): String? {
        // Calculate overlap duration with each speaker segment
        val overlapBySpeaker = mutableMapOf<String, Double>()

        for (seg in speakerSegments) {
            val overlapStart = maxOf(startSec, seg.start)
            val overlapEnd = minOf(endSec, seg.end)
            val overlap = overlapEnd - overlapStart

            if (overlap > 0) {
                overlapBySpeaker[seg.speaker] = (overlapBySpeaker[seg.speaker] ?: 0.0) + overlap
            }
        }

        if (overlapBySpeaker.isNotEmpty()) {
            return overlapBySpeaker.maxByOrNull { it.value }?.key
        }

        // Fallback: find speaker active at the midpoint
        val midpoint = (startSec + endSec) / 2.0
        return speakerSegments.find { midpoint in it.start..it.end }?.speaker
    }

    /**
     * Check if the diarization server is reachable.
     */
    suspend fun isAvailable(serverUrl: String): Boolean {
        return try {
            val response = httpClient.get("$serverUrl/health")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }
}
