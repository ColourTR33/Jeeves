package com.jeeves.shared.ai

import com.jeeves.shared.domain.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Cloud transcription client using Groq's Whisper API.
 * Offers whisper-large-v3 quality at near-instant speed.
 * OpenAI-compatible endpoint: https://api.groq.com/openai/v1/audio/transcriptions
 *
 * Groq has a 25MB file size limit, so files larger than that are chunked
 * by the whisper-server (which is still used for chunking), or the ProcessingQueue
 * should use streaming transcript for long recordings.
 */
class GroqWhisperClient(
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val groqBaseUrl = "https://api.groq.com/openai/v1/audio/transcriptions"

    /**
     * Transcribe an audio file using Groq's cloud Whisper API.
     * Returns a TranscriptionResult on success.
     *
     * @param audioFilePath path to the WAV file
     * @param apiKey Groq API key (Bearer token)
     * @param model Groq whisper model (whisper-large-v3, whisper-large-v3-turbo, distil-whisper-large-v3-en)
     */
    suspend fun transcribe(
        audioFilePath: String,
        apiKey: String,
        model: String = "whisper-large-v3-turbo"
    ): TranscriptionResult {
        val audioBytes = readAudioFile(audioFilePath)
        val fileName = audioFilePath.substringAfterLast("/").substringAfterLast("\\")

        AppLogger.info("GroqWhisperClient", "Transcribing via Groq: $fileName (${audioBytes.size} bytes)")
        AppLogger.info("GroqWhisperClient", "Model: $model")

        // Groq has a 25MB limit — check file size
        if (audioBytes.size > 25 * 1024 * 1024) {
            AppLogger.warn("GroqWhisperClient", "File exceeds 25MB Groq limit (${audioBytes.size} bytes). Consider using streaming transcript for long recordings.")
            // Still attempt — Groq may reject it, and the ProcessingQueue will fall back to local
        }

        val response = httpClient.submitFormWithBinaryData(
            url = groqBaseUrl,
            formData = formData {
                append("file", audioBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, "audio/wav")
                })
                append("model", model)
                append("response_format", "verbose_json")
                append("language", "en")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }

        val responseBody: String = response.body()

        if (!response.status.isSuccess()) {
            AppLogger.error("GroqWhisperClient", "Groq API error: HTTP ${response.status.value}: $responseBody")
            throw RuntimeException("Groq transcription failed: HTTP ${response.status.value}")
        }

        AppLogger.info("GroqWhisperClient", "Groq transcription successful")
        val whisperResponse = json.decodeFromString<WhisperApiResponse>(responseBody)

        val segments = whisperResponse.segments?.map { segment ->
            TranscriptionSegment(
                startMs = (segment.start * 1000).toLong(),
                endMs = (segment.end * 1000).toLong(),
                text = segment.text,
                speaker = null
            )
        } ?: emptyList()

        return TranscriptionResult(
            recordingId = "",
            text = whisperResponse.text,
            segments = segments,
            language = whisperResponse.language ?: "en",
            durationMs = ((whisperResponse.duration ?: 0.0) * 1000).toLong()
        )
    }
}
