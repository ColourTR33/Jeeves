package com.jeeves.shared.domain

import kotlinx.serialization.Serializable

/**
 * Represents the current state of a recording session.
 */
enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    PROCESSING
}

/**
 * A completed recording with metadata.
 */
@Serializable
data class Recording(
    val id: String,
    val filePath: String,
    val durationMs: Long,
    val createdAt: Long,
    val title: String = "Untitled Meeting"
)

/**
 * Result of transcribing a recording.
 */
@Serializable
data class TranscriptionResult(
    val recordingId: String,
    val text: String,
    val segments: List<TranscriptionSegment> = emptyList(),
    val language: String = "en",
    val durationMs: Long = 0
)

/**
 * A timestamped segment within a transcription.
 */
@Serializable
data class TranscriptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null
)

/**
 * Result of summarising a transcription.
 */
@Serializable
data class SummaryResult(
    val recordingId: String,
    val summary: String,
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val modelUsed: String = ""
)

/**
 * Configuration for an AI endpoint.
 */
@Serializable
data class AiEndpointConfig(
    val name: String,
    val baseUrl: String,
    val modelName: String,
    val type: AiEndpointType
)

/**
 * Type of AI endpoint.
 */
@Serializable
enum class AiEndpointType {
    WHISPER_TRANSCRIPTION,
    LLM_SUMMARIZATION
}

/**
 * Application settings.
 */
@Serializable
data class AppSettings(
    val transcriptionEndpoint: AiEndpointConfig = AiEndpointConfig(
        name = "Local Whisper",
        baseUrl = "http://localhost:8080",
        modelName = "whisper-large-v3",
        type = AiEndpointType.WHISPER_TRANSCRIPTION
    ),
    val summarizationEndpoint: AiEndpointConfig = AiEndpointConfig(
        name = "Local Ollama",
        baseUrl = "http://localhost:11434",
        modelName = "llama3",
        type = AiEndpointType.LLM_SUMMARIZATION
    ),
    val recordingHotkey: String = "Ctrl+Shift+R",
    val audioFormat: String = "wav",
    val sampleRate: Int = 16000
)
