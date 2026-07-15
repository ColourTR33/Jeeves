package com.jeeves.shared.ai

import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.DiarizationMode
import com.jeeves.shared.domain.TranscriptionResult
import com.jeeves.shared.domain.TranscriptionSegment
import com.jeeves.shared.domain.TranscriptionService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
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
    private val diarizationParser = DiarizationResponseParser()

    override suspend fun transcribe(
        audioFilePath: String,
        config: AiEndpointConfig,
        diarizationEnabled: Boolean,
        diarizationMode: DiarizationMode
    ): TranscriptionResult {
        val fileName = audioFilePath.substringAfterLast("/").substringAfterLast("\\")

        // Read file into memory (necessary for multipart upload)
        var audioBytes: ByteArray? = readAudioFile(audioFilePath)
        val fileSize = audioBytes!!.size

        AppLogger.info("WhisperClient", "Starting transcription of: $fileName ($fileSize bytes)")
        AppLogger.info("WhisperClient", "Using endpoint: ${config.baseUrl}, model: ${config.modelName}")
        if (diarizationEnabled) {
            AppLogger.info("WhisperClient", "Diarization enabled, mode: $diarizationMode")
        }

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

                // Re-read if we released the bytes on a previous failed attempt
                if (audioBytes == null) {
                    audioBytes = readAudioFile(audioFilePath)
                }
                val bytesToSend = audioBytes!!

                val response = httpClient.submitFormWithBinaryData(
                    url = endpoint,
                    formData = formData {
                        append("file", bytesToSend, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            append(HttpHeaders.ContentType, "audio/wav")
                        })
                        append("model", config.modelName)
                        append("response_format", "verbose_json")
                        append("language", "en")
                        if (diarizationEnabled) {
                            append("diarize", "true")
                        }
                    }
                )

                val responseBody: String = response.body()

                if (!response.status.isSuccess()) {
                    // Check if this is a diarization-related 4xx error that we can retry without diarize
                    if (diarizationEnabled && response.status.value in 400..499 &&
                        isDiarizationRelatedError(responseBody)
                    ) {
                        AppLogger.warn("WhisperClient", "Diarization-related error from $endpoint (HTTP ${response.status.value}): $responseBody")
                        val retryResult = retryWithoutDiarization(endpoint, bytesToSend, fileName, config)
                        if (retryResult != null) {
                            audioBytes = null // Release memory
                            return retryResult
                        }
                        // Retry failed, fall through to try next endpoint
                    }

                    AppLogger.error("WhisperClient", "HTTP ${response.status.value} from $endpoint: $responseBody")
                    lastError = RuntimeException("HTTP ${response.status.value}: $responseBody")
                    continue
                }

                // Success — release audio bytes immediately
                audioBytes = null

                AppLogger.info("WhisperClient", "Transcription successful from: $endpoint")
                val whisperResponse = json.decodeFromString<WhisperApiResponse>(responseBody)

                val segments: List<TranscriptionSegment> = if (diarizationEnabled && whisperResponse.segments != null) {
                    diarizationParser.parseSegments(whisperResponse.segments, diarizationMode)
                } else {
                    whisperResponse.segments?.map { segment ->
                        TranscriptionSegment(
                            startMs = (segment.start * 1000).toLong(),
                            endMs = (segment.end * 1000).toLong(),
                            text = segment.text,
                            speaker = null
                        )
                    } ?: emptyList()
                }

                return TranscriptionResult(
                    recordingId = "", // Set by caller
                    text = whisperResponse.text,
                    segments = segments,
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

    /**
     * Checks if an HTTP error response body indicates a diarization-related failure.
     * Matches on "diarize" or "unrecognized parameter" in the error body.
     */
    private fun isDiarizationRelatedError(responseBody: String): Boolean {
        val lowerBody = responseBody.lowercase()
        return lowerBody.contains("diarize") || lowerBody.contains("unrecognized parameter")
    }

    /**
     * Retries a transcription request to the same endpoint without the diarize form field.
     * Returns a TranscriptionResult with diarizationUnavailable=true and null speakers on success,
     * or null if the retry also fails.
     */
    private suspend fun retryWithoutDiarization(
        endpoint: String,
        audioBytes: ByteArray,
        fileName: String,
        config: AiEndpointConfig
    ): TranscriptionResult? {
        AppLogger.warn("WhisperClient", "Retrying $endpoint without diarize parameter")
        delay(1000) // Brief delay before retry to avoid hammering the server

        return try {
            val retryResponse = httpClient.submitFormWithBinaryData(
                url = endpoint,
                formData = formData {
                    append("file", audioBytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, "audio/wav")
                    })
                    append("model", config.modelName)
                    append("response_format", "verbose_json")
                    append("language", "en")
                    // diarize param intentionally omitted for retry
                }
            )

            val retryBody: String = retryResponse.body()

            if (!retryResponse.status.isSuccess()) {
                AppLogger.error("WhisperClient", "Retry without diarize also failed (HTTP ${retryResponse.status.value}): $retryBody")
                return null
            }

            AppLogger.info("WhisperClient", "Retry without diarize succeeded on $endpoint")
            val whisperResponse = json.decodeFromString<WhisperApiResponse>(retryBody)

            val segments = whisperResponse.segments?.map { segment ->
                TranscriptionSegment(
                    startMs = (segment.start * 1000).toLong(),
                    endMs = (segment.end * 1000).toLong(),
                    text = segment.text,
                    speaker = null // No speaker data available without diarization
                )
            } ?: emptyList()

            TranscriptionResult(
                recordingId = "", // Set by caller
                text = whisperResponse.text,
                segments = segments,
                language = whisperResponse.language ?: "en",
                durationMs = ((whisperResponse.duration ?: 0.0) * 1000).toLong(),
                diarizationUnavailable = true
            )
        } catch (e: Exception) {
            AppLogger.error("WhisperClient", "Retry without diarize threw exception: ${e.message}", e)
            null
        }
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
    val text: String,
    val speaker: String? = null
)
