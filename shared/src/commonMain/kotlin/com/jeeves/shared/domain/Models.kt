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
    val description: String = "",  // agenda, context, attendee info
    val template: MeetingTemplate = MeetingTemplate.GENERAL,
    val tags: List<String> = emptyList(),
    val folder: String = "",
    val highlights: List<Long> = emptyList(),  // Timestamp ms values of bookmarked moments
    val attachments: List<Attachment> = emptyList()  // Screenshots captured during recording
)

/**
 * An attachment (screenshot/image) captured during a recording session.
 */
@Serializable
data class Attachment(
    val id: String,
    val filePath: String,       // path to saved image file
    val timestampMs: Long,      // ms into recording when captured
    val caption: String = ""    // optional user annotation
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
    val questions: List<String> = emptyList(),
    val tags: List<String> = emptyList(),  // Auto-generated hashtag tags for organization
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
 * Audio input source selection.
 * DEFAULT_MICROPHONE: Uses the system default audio input device.
 * SPECIFIC_DEVICE: Uses a named audio input device (e.g., BlackHole for system audio capture).
 */
@Serializable
enum class AudioSource {
    DEFAULT_MICROPHONE,
    SPECIFIC_DEVICE
}

/**
 * Transcription provider selection.
 * LOCAL: Uses local Whisper server (faster-whisper, whisper.cpp)
 * GROQ_CLOUD: Uses Groq's cloud Whisper API (whisper-large-v3, much more accurate)
 */
@Serializable
enum class TranscriptionProvider {
    LOCAL,
    GROQ_CLOUD
}

/**
 * Application settings.
 */
@Serializable
data class AppSettings(
    val transcriptionEndpoint: AiEndpointConfig = AiEndpointConfig(
        name = "Local Whisper",
        baseUrl = "http://localhost:8178",
        modelName = "whisper-small",
        type = AiEndpointType.WHISPER_TRANSCRIPTION
    ),
    val summarizationEndpoint: AiEndpointConfig = AiEndpointConfig(
        name = "Local Ollama",
        baseUrl = "http://localhost:11434",
        modelName = "qwen3:8b",
        type = AiEndpointType.LLM_SUMMARIZATION
    ),
    val transcriptionProvider: TranscriptionProvider = TranscriptionProvider.LOCAL,
    val groqApiKey: String = "",  // Groq cloud API key for whisper-large-v3
    val groqModel: String = "whisper-large-v3-turbo",  // whisper-large-v3, whisper-large-v3-turbo, distil-whisper-large-v3-en
    val recordingHotkey: String = "Ctrl+Shift+R",
    val audioFormat: String = "wav",
    val sampleRate: Int = 16000,
    val diarizationEnabled: Boolean = false,
    val diarizationMode: DiarizationMode = DiarizationMode.TINYDIARIZE,
    val stereoRecording: Boolean = false,
    val streamingEnabled: Boolean = true,
    val chunkIntervalSeconds: Int = 5,
    val overlapWindowSeconds: Float = 2.0f,
    val customVocabulary: String = "",  // Comma-separated custom terms
    val obsidianVaultPath: String = "",
    val audioSource: AudioSource = AudioSource.DEFAULT_MICROPHONE,
    val audioDeviceName: String = "",  // Name of the specific audio input device (e.g., "BlackHole 2ch")
    val captureSystemAudio: Boolean = false,  // When true, also records system audio (other people on calls)
    /**
     * Optional dedicated Whisper endpoint used only for streaming (live) transcription.
     * When set, streaming chunks are sent here instead of [transcriptionEndpoint], so a
     * second whisper-server instance can handle live chunks without competing with the
     * full-file transcription of the previous recording.
     * Null (default) = share the main [transcriptionEndpoint] for both purposes.
     */
    val streamingTranscriptionEndpoint: AiEndpointConfig? = null
)
