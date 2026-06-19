package com.jeeves.shared.ai

import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.TranscriptionResult
import com.jeeves.shared.domain.TranscriptionSegment
import com.jeeves.shared.domain.TranscriptionService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for local Whisper-compatible transcription API.
 * Supports both:
 *   - whisper.cpp server (endpoint: /inference)
 *   - OpenAI-compatible servers (endpoint: /v1/audio/transcriptions)
 *
 * The client tries the whisper.cpp endpoint first, then falls back to OpenAI format.
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

        AppLogger.info("WhisperClient", "Starting transcription of: $fileName (${audioBytes.size} bytes)")
        AppLogger.info("WhisperClient", "Using endpoint: ${config.baseUrl}, model: ${config.modelName}")

        // Try whisper.cpp server endpoints, then OpenAI-compatible endpoint
        val endpoints = listOf(
            "${config.baseUrl}/v1/audio/inference",
            "${config.baseUrl}/inference",
            "${config.baseUrl}/v1/audio/transcriptions"
        )

        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                AppLogger.info("WhisperClient", "Trying endpoint: $endpoint")

                val response = httpClient.submitFormWithBinaryData(
                    url = endpoint,
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

                if (!response.status.isSuccess()) {
                    AppLogger.error("WhisperClient", "HTTP ${response.status.value} from $endpoint: $responseBody")
                    lastError = RuntimeException("HTTP ${response.status.value}: $responseBody")
                    continue
                }

                AppLogger.info("WhisperClient", "Transcription successful from: $endpoint")
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
            } catch (e: Exception) {
                AppLogger.error("WhisperClient", "Failed on $endpoint: ${e.message}", e)
                lastError = e
            }
        }

        throw lastError ?: RuntimeException("All transcription endpoints failed for ${config.baseUrl}")
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
