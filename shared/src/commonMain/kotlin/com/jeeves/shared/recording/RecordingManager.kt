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
 * Coordinates the recording, transcription, and summarization pipeline.
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

    private var recordingStartTime: Long = 0

    /**
     * Toggle recording on/off. Called by hotkey or button press.
     */
    fun toggleRecording() {
        scope.launch {
            when (_state.value) {
                RecordingState.IDLE -> startRecording()
                RecordingState.RECORDING -> stopRecording()
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
     * Stop recording and begin transcription pipeline.
     */
    suspend fun stopRecording() {
        try {
            // Notify streaming callback before stopping the recorder
            streamingCallback?.onRecordingStopping()

            val filePath = audioRecorder.stopRecording()
            val duration = currentTimeMillis() - recordingStartTime

            val recording = Recording(
                id = generateId(),
                filePath = filePath,
                durationMs = duration,
                createdAt = currentTimeMillis()
            )

            _currentRecording.value = recording
            recordingsRepository.saveRecording(recording)
            _state.value = RecordingState.PROCESSING

            processRecording(recording)
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

    /**
     * Process a recording: transcribe then summarise.
     * If a streaming transcript was captured during recording, use it directly
     * instead of re-transcribing the full file (avoids timeouts on long recordings).
     */
    private suspend fun processRecording(recording: Recording) {
        try {
            val settings = settingsRepository.getSettings()

            // Step 1: Transcribe — prefer streaming transcript for long recordings
            AppLogger.info("RecordingManager", "Starting transcription for recording: ${recording.id}")
            AppLogger.info("RecordingManager", "Audio file: ${recording.filePath}")
            _transcriptionProgress.value = "Transcribing audio..."

            val streamingText = streamingCallback?.getStreamingTranscript()
            val useStreamingTranscript = !streamingText.isNullOrBlank() &&
                recording.durationMs > 120_000 // Use streaming for recordings > 2 min

            val transcription = if (useStreamingTranscript) {
                AppLogger.info("RecordingManager", "Using streaming transcript (${streamingText!!.length} chars) — skipping full-file transcription")
                TranscriptionResult(
                    recordingId = recording.id,
                    text = streamingText,
                    segments = emptyList(),
                    language = "en",
                    durationMs = recording.durationMs
                )
            } else {
                AppLogger.info("RecordingManager", "Transcription endpoint: ${settings.transcriptionEndpoint.baseUrl}")
                AppLogger.info("RecordingManager", "Diarization: enabled=${settings.diarizationEnabled}, mode=${settings.diarizationMode}")
                whisperClient.transcribe(
                    audioFilePath = recording.filePath,
                    config = settings.transcriptionEndpoint,
                    diarizationEnabled = settings.diarizationEnabled,
                    diarizationMode = settings.diarizationMode
                ).copy(recordingId = recording.id)
            }

            AppLogger.info("RecordingManager", "Transcription complete: ${transcription.text.take(100)}...")
            recordingsRepository.saveTranscription(transcription)
            _transcriptionProgress.value = "Transcription complete. Summarising..."

            // Step 2: Summarise — use speaker-attributed text when speakers are present
            AppLogger.info("RecordingManager", "Starting summarization with: ${settings.summarizationEndpoint.baseUrl}")
            val transcriptionForSummary = if (hasSpeakerLabels(transcription.segments)) {
                AppLogger.info("RecordingManager", "Using speaker-attributed text for summarization")
                transcription.copy(text = formatWithSpeakers(transcription.segments))
            } else {
                transcription
            }
            val summary = ollamaClient.summarize(
                transcription = transcriptionForSummary,
                config = settings.summarizationEndpoint
            )

            recordingsRepository.saveSummary(summary)
            AppLogger.info("RecordingManager", "Summarization complete")
            _transcriptionProgress.value = null
            _state.value = RecordingState.IDLE
        } catch (e: Exception) {
            AppLogger.error("RecordingManager", "Processing failed: ${e.message}", e)
            _error.value = "Processing failed: ${e.message}"
            _transcriptionProgress.value = null
            _state.value = RecordingState.IDLE
        }
    }

    fun clearError() {
        _error.value = null
    }
}

/**
 * Platform-specific utilities.
 */
expect fun currentTimeMillis(): Long
expect fun generateId(): String
expect fun generateOutputPath(format: String): String
