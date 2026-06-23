package com.jeeves.shared.recording

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.ai.OllamaClient
import com.jeeves.shared.ai.WhisperClient
import com.jeeves.shared.ai.formatWithSpeakers
import com.jeeves.shared.ai.hasSpeakerLabels
import com.jeeves.shared.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Callback interface for streaming transcription integration.
 * Implemented in the desktop app to bridge RecordingManager (shared) with
 * StreamingTranscriber (desktop-only).
 */
interface StreamingCallback {
    /** Called before recording starts, allowing platform-specific setup (e.g., setting audio device). */
    fun onPreRecordingSetup(settings: AppSettings)

    /** Called after recording has started successfully. */
    fun onRecordingStarted(settings: AppSettings)

    /** Called at the start of stopRecording, before the audio recorder is stopped. */
    fun onRecordingStopping()

    /**
     * Returns the accumulated streaming transcript text captured during recording.
     * Returns null or empty string if streaming was not active or captured nothing.
     */
    fun getStreamingTranscript(): String?
}

/**
 * Central manager for recording sessions.
 * Coordinates recording start/stop and delegates transcription/summarization
 * to the ProcessingQueue for async, non-blocking processing.
 */
class RecordingManager(
    private val audioRecorder: AudioRecorder,
    private val whisperClient: WhisperClient,
    private val ollamaClient: OllamaClient,
    private val settingsRepository: SettingsRepository,
    private val recordingsRepository: RecordingsRepository,
    private val scope: CoroutineScope,
    private val streamingCallback: StreamingCallback? = null
) {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _currentRecording = MutableStateFlow<Recording?>(null)
    val currentRecording: StateFlow<Recording?> = _currentRecording.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow<String?>(null)
    val transcriptionProgress: StateFlow<String?> = _transcriptionProgress.asStateFlow()

    /** The processing queue handles transcription and summarization asynchronously. */
    val processingQueue = ProcessingQueue(
        whisperClient = whisperClient,
        ollamaClient = ollamaClient,
        settingsRepository = settingsRepository,
        recordingsRepository = recordingsRepository,
        scope = scope
    )

    private var recordingStartTime: Long = 0

    /** Pending metadata set by the UI during recording, consumed on stop. */
    var pendingTitle: String = ""
    var pendingDescription: String = ""
    var pendingAttachments: List<Attachment> = emptyList()

    /**
     * Toggle recording on/off. Called by hotkey or button press.
     * Uses pending metadata fields when stopping.
     */
    fun toggleRecording() {
        scope.launch {
            when (_state.value) {
                RecordingState.IDLE -> startRecording()
                RecordingState.RECORDING -> stopRecording(pendingTitle, pendingDescription, pendingAttachments)
                RecordingState.PAUSED -> resumeRecording()
                RecordingState.PROCESSING -> { /* Ignore while processing */ }
            }
        }
    }

    /**
     * Start a new recording.
     */
    suspend fun startRecording() {
        try {
            _error.value = null
            val settings = settingsRepository.getSettings()
            val outputPath = generateOutputPath(settings.audioFormat)
            val useStereo = settings.diarizationEnabled && settings.stereoRecording

            // Allow platform to configure audio device before recording starts
            streamingCallback?.onPreRecordingSetup(settings)

            recordingStartTime = currentTimeMillis()
            audioRecorder.startRecording(outputPath, stereo = useStereo)
            _state.value = RecordingState.RECORDING

            // Notify streaming callback after recording starts successfully
            streamingCallback?.onRecordingStarted(settings)
        } catch (e: Exception) {
            _error.value = "Failed to start recording: ${e.message}"
            _state.value = RecordingState.IDLE
        }
    }

    /**
     * Stop recording and enqueue for async transcription/summarization.
     * Returns immediately to IDLE so the user can start another recording.
     * Accepts optional metadata (title, description, attachments) set during recording.
     */
    suspend fun stopRecording(
        title: String = "Untitled Meeting",
        description: String = "",
        attachments: List<Attachment> = emptyList()
    ) {
        try {
            // Capture streaming transcript before stopping
            val streamingText = streamingCallback?.getStreamingTranscript()

            // Notify streaming callback before stopping the recorder
            streamingCallback?.onRecordingStopping()

            val filePath = audioRecorder.stopRecording()
            val duration = currentTimeMillis() - recordingStartTime

            val recording = Recording(
                id = generateId(),
                filePath = filePath,
                durationMs = duration,
                createdAt = currentTimeMillis(),
                title = title.ifBlank { "Untitled Meeting" },
                description = description,
                attachments = attachments
            )

            _currentRecording.value = recording
            recordingsRepository.saveRecording(recording)

            // Enqueue for async processing — returns immediately
            processingQueue.enqueue(recording, streamingTranscript = streamingText)
            _state.value = RecordingState.IDLE

            AppLogger.info("RecordingManager", "Recording saved and enqueued: ${recording.id}")
        } catch (e: Exception) {
            _error.value = "Failed to stop recording: ${e.message}"
            _state.value = RecordingState.IDLE
        }
    }

    /**
     * Pause current recording.
     */
    suspend fun pauseRecording() {
        audioRecorder.pauseRecording()
        _state.value = RecordingState.PAUSED
    }

    /**
     * Resume paused recording.
     */
    suspend fun resumeRecording() {
        audioRecorder.resumeRecording()
        _state.value = RecordingState.RECORDING
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Retranscribe an existing recording.
     * Enqueues it for async processing via the queue.
     */
    fun retranscribeRecording(recording: Recording) {
        processingQueue.enqueue(recording)
    }
}

/**
 * Platform-specific utilities.
 */
expect fun currentTimeMillis(): Long
expect fun generateId(): String
expect fun generateOutputPath(format: String): String
