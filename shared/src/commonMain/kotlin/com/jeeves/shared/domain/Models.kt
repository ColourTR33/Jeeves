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
 * Meeting template determines the summarization style.
 */
@Serializable
enum class MeetingTemplate {
    GENERAL,    // Default — summary, key points, action items
    STANDUP,    // Yesterday, today, blockers format
    ONE_ON_ONE, // Discussion topics, decisions, action items, feedback
    INTERVIEW,  // Candidate responses, impressions, recommendation
    BRAINSTORM  // Ideas generated, themes, next steps
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
    val title: String = "Untitled Meeting",
    val template: MeetingTemplate = MeetingTemplate.GENERAL,
    val tags: List<String> = emptyList(),
    val folder: String = ""
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
    val durationMs: Long = 0,
    val diarizationUnavailable: Boolean = false
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
 * Diarization mode matching whisper.cpp server flags.
 * DIARIZE: Stereo channel-based speaker identification (requires 2-channel audio and --diarize server flag)
 * TINYDIARIZE: Model-based speaker turn detection (requires --tinydiarize server flag and tdrz model)
 */
@Serializable
enum class DiarizationMode {
    DIARIZE,
    TINYDIARIZE
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
    val sampleRate: Int = 16000,
    val diarizationEnabled: Boolean = false,
    val diarizationMode: DiarizationMode = DiarizationMode.TINYDIARIZE,
    val stereoRecording: Boolean = false,
    val streamingEnabled: Boolean = true,
    val chunkIntervalSeconds: Int = 5,
    val overlapWindowSeconds: Float = 2.0f,
    val obsidianVaultPath: String = ""
)
