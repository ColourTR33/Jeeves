package com.jeeves.shared.ai

import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.TranscriptionResult
import com.jeeves.shared.domain.TranscriptionSegment
import com.jeeves.shared.domain.TranscriptionService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for local Whisper-compatible transcription API.
 * Supports OpenAI-compatible /v1/audio/transcriptions endpoint
 * (used by faster-whisper-server, whisper.cpp server, LocalAI, etc.)
 */
class WhisperClient(
    private val httpClient: HttpClient
) : TranscriptionService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(
        audioFilePath: String,
        config: AiEndpointConfig
    ): TranscriptionResult {
        val audioBytes = readAudioFile(audioFilePath)
        val fileName = audioFilePath.substringAfterLast("/").substringAfterLast("\\")

        val response = httpClient.submitFormWithBinaryData(
            url = "${config.baseUrl}/v1/audio/transcriptions",
            formData = formData {
                append("file", audioBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, "audio/wav")
                })
                append("model", config.modelName)
                append("response_format", "verbose_json")
                append("language", "en")
            }
        )

        val responseBody: String = response.body()
        val whisperResponse = json.decodeFromString<WhisperApiResponse>(responseBody)

        return TranscriptionResult(
            recordingId = "", // Set by caller
            text = whisperResponse.text,
            segments = whisperResponse.segments?.map { segment ->
                TranscriptionSegment(
                    startMs = (segment.start * 1000).toLong(),
                    endMs = (segment.end * 1000).toLong(),
                    text = segment.text
                )
            } ?: emptyList(),
            language = whisperResponse.language ?: "en",
            durationMs = ((whisperResponse.duration ?: 0.0) * 1000).toLong()
        )
    }
}

/**
 * Platform-specific file reading. Implemented in desktopMain/iosMain.
 */
expect suspend fun readAudioFile(filePath: String): ByteArray

/**
 * Whisper API response format (OpenAI-compatible).
 */
@Serializable
data class WhisperApiResponse(
    val text: String,
    val segments: List<WhisperSegment>? = null,
    val language: String? = null,
    val duration: Double? = null
)

@Serializable
data class WhisperSegment(
    val start: Double,
    val end: Double,
    val text: String
)
