package com.jeeves.shared.recording

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.ai.DiarizationClient
import com.jeeves.shared.ai.GroqWhisperClient
import com.jeeves.shared.ai.OllamaClient
import com.jeeves.shared.ai.PromptTemplateManager
import com.jeeves.shared.ai.WhisperClient
import com.jeeves.shared.ai.formatWithSpeakers
import com.jeeves.shared.ai.hasSpeakerLabels
import com.jeeves.shared.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Processing status for a recording's transcription and summarization pipeline.
 */
enum class ProcessingStatus {
    /** Queued but not yet started */
    WAITING,
    /** Transcription is in progress */
    TRANSCRIBING,
    /** Speaker diarization is in progress */
    DIARIZING,
    /** Summarization is in progress */
    SUMMARIZING,
    /** Both transcription and summarization completed successfully */
    COMPLETE,
    /** Processing failed at some stage */
    FAILED
}

/**
 * Represents the processing state of a single recording in the queue.
 */
data class ProcessingItem(
    val recordingId: String,
    val status: ProcessingStatus,
    val error: String? = null,
    /** Optional streaming transcript text to use instead of full-file transcription */
    val streamingTranscript: String? = null,
    /** When true, skip transcription and go straight to summarization */
    val summarizeOnly: Boolean = false
)

/**
 * Asynchronous processing queue that handles transcription and summarization
 * for recordings independently. Items are processed sequentially (one at a time)
 * to avoid overloading the local Whisper server and to minimize memory usage.
 *
 * Memory management:
 * - Only one recording is processed at a time (sequential processing)
 * - Audio bytes are not held in this class (WhisperClient handles its own memory)
 * - Completed items are auto-pruned to keep the queue small
 * - GC is hinted between major processing steps
 */
class ProcessingQueue(
    private val whisperClient: WhisperClient,
    private val ollamaClient: OllamaClient,
    private val settingsRepository: SettingsRepository,
    private val recordingsRepository: RecordingsRepository,
    private val scope: CoroutineScope,
    private val groqClient: GroqWhisperClient? = null,
    private val diarizationClient: DiarizationClient? = null,
    private val promptTemplateManager: PromptTemplateManager? = null
) {
    private val _queue = MutableStateFlow<List<ProcessingItem>>(emptyList())
    val queue: StateFlow<List<ProcessingItem>> = _queue.asStateFlow()

    private val mutex = Mutex()
    private var processingJob: Job? = null

    /** Maximum items to keep in queue history (completed/failed). Prevents unbounded growth. */
    private val MAX_HISTORY = 20

    /**
     * Get the current processing status of a recording, or null if not in queue.
     */
    fun getStatus(recordingId: String): ProcessingStatus? {
        return _queue.value.find { it.recordingId == recordingId }?.status
    }

    /**
     * Enqueue a recording for transcription and summarization.
     * If already in queue (and not COMPLETE/FAILED), it won't be added again.
     * If COMPLETE or FAILED, it will be re-queued (for retranscribe).
     */
    fun enqueue(recording: Recording, streamingTranscript: String? = null) {
        val existing = _queue.value.find { it.recordingId == recording.id }
        if (existing != null && existing.status != ProcessingStatus.COMPLETE && existing.status != ProcessingStatus.FAILED) {
            return // Already queued/in progress
        }

        _queue.update { currentQueue ->
            val filtered = currentQueue.filter { it.recordingId != recording.id }
            filtered + ProcessingItem(
                recordingId = recording.id,
                status = ProcessingStatus.WAITING,
                streamingTranscript = streamingTranscript
            )
        }

        // Start processing if not already running
        startProcessingIfIdle()
    }

    /**
     * Enqueue a recording for summarization only (transcription already exists).
     * Skips the transcription step entirely.
     */
    fun enqueueSummarizeOnly(recording: Recording) {
        val existing = _queue.value.find { it.recordingId == recording.id }
        if (existing != null && existing.status != ProcessingStatus.COMPLETE && existing.status != ProcessingStatus.FAILED) {
            return
        }

        _queue.update { currentQueue ->
            val filtered = currentQueue.filter { it.recordingId != recording.id }
            filtered + ProcessingItem(
                recordingId = recording.id,
                status = ProcessingStatus.WAITING,
                summarizeOnly = true
            )
        }

        startProcessingIfIdle()
    }

    /**
     * Remove completed/failed items from the queue (cleanup).
     */
    fun clearCompleted() {
        _queue.update { it.filter { item -> item.status != ProcessingStatus.COMPLETE } }
    }

    /**
     * Remove a specific item from the queue (e.g., cancel waiting item).
     */
    fun remove(recordingId: String) {
        val item = _queue.value.find { it.recordingId == recordingId }
        if (item != null && item.status == ProcessingStatus.WAITING) {
            _queue.update { it.filter { i -> i.recordingId != recordingId } }
        }
    }

    private fun startProcessingIfIdle() {
        if (processingJob?.isActive == true) return

        processingJob = scope.launch {
            processNextItem()
        }
    }

    private suspend fun processNextItem() {
        while (true) {
            val nextItem = mutex.withLock {
                _queue.value.firstOrNull { it.status == ProcessingStatus.WAITING }
            } ?: break // No more items to process

            processItem(nextItem)

            // After each item, prune completed history and hint GC
            pruneHistory()
            System.gc()
        }
    }

    /** Keep only the last MAX_HISTORY completed/failed items to prevent unbounded memory growth. */
    private fun pruneHistory() {
        _queue.update { currentQueue ->
            val active = currentQueue.filter { it.status == ProcessingStatus.WAITING || it.status == ProcessingStatus.TRANSCRIBING || it.status == ProcessingStatus.DIARIZING || it.status == ProcessingStatus.SUMMARIZING }
            val finished = currentQueue.filter { it.status == ProcessingStatus.COMPLETE || it.status == ProcessingStatus.FAILED }
            active + finished.takeLast(MAX_HISTORY)
        }
    }

    private suspend fun processItem(item: ProcessingItem) {
        val recording = recordingsRepository.getRecording(item.recordingId)
        if (recording == null) {
            updateStatus(item.recordingId, ProcessingStatus.FAILED, "Recording not found")
            return
        }

        try {
            val settings = settingsRepository.getSettings()
            AppLogger.debug("ProcessingQueue", "Processing ${recording.id}: file=${recording.filePath}, duration=${recording.durationMs}ms, summarizeOnly=${item.summarizeOnly}")

            val transcription: TranscriptionResult

            if (item.summarizeOnly) {
                // Load existing transcription — skip transcription step entirely
                val existing = recordingsRepository.getTranscription(recording.id)
                if (existing == null) {
                    AppLogger.error("ProcessingQueue", "No existing transcription for ${recording.id}, cannot summarize-only")
                    updateStatus(item.recordingId, ProcessingStatus.FAILED, "No transcription to summarize")
                    return
                }
                AppLogger.info("ProcessingQueue", "Summarize-only: using existing transcription for ${recording.id}")
                transcription = existing
            } else {

            // Step 1: Transcribe
            updateStatus(item.recordingId, ProcessingStatus.TRANSCRIBING)
            AppLogger.info("ProcessingQueue", "Transcribing: ${recording.id} (${recording.filePath})")

            // Use streaming transcript if it captured at least 200 chars of content.
            val streamingText = item.streamingTranscript
            val useStreaming = !streamingText.isNullOrBlank() && streamingText.length >= 200

            transcription = if (useStreaming) {
                AppLogger.info("ProcessingQueue", "Using streaming transcript (${streamingText.orEmpty().length} chars)")
                TranscriptionResult(
                    recordingId = recording.id,
                    text = streamingText.orEmpty(),
                    segments = emptyList(),
                    language = "en",
                    durationMs = recording.durationMs
                )
            } else if (settings.transcriptionProvider == TranscriptionProvider.GROQ_CLOUD &&
                settings.groqApiKey.isNotBlank() && groqClient != null) {
                // Use Groq cloud for better accuracy
                AppLogger.info("ProcessingQueue", "Using Groq cloud transcription (${settings.groqModel})")
                try {
                    groqClient.transcribe(
                        audioFilePath = recording.filePath,
                        apiKey = settings.groqApiKey,
                        model = settings.groqModel
                    ).copy(recordingId = recording.id)
                } catch (e: Exception) {
                    // Fall back to local on Groq failure
                    AppLogger.warn("ProcessingQueue", "Groq failed (${e.message}), falling back to local Whisper")
                    whisperClient.transcribe(
                        audioFilePath = recording.filePath,
                        config = settings.transcriptionEndpoint,
                        diarizationEnabled = settings.diarizationEnabled,
                        diarizationMode = settings.diarizationMode
                    ).copy(recordingId = recording.id)
                }
            } else {
                whisperClient.transcribe(
                    audioFilePath = recording.filePath,
                    config = settings.transcriptionEndpoint,
                    diarizationEnabled = settings.diarizationEnabled,
                    diarizationMode = settings.diarizationMode
                ).copy(recordingId = recording.id)
            }

            recordingsRepository.saveTranscription(transcription)
            AppLogger.info("ProcessingQueue", "Transcription complete for ${recording.id}")

            // Release memory pressure between transcription and summarization
            System.gc()

            } // end else (full transcription path)

            // Step 2: Diarize (if pyannote mode is enabled and server is available)
            var diarizedTranscription = transcription
            if (settings.diarizationEnabled &&
                settings.diarizationMode == DiarizationMode.PYANNOTE &&
                diarizationClient != null &&
                transcription.segments.isNotEmpty()
            ) {
                updateStatus(item.recordingId, ProcessingStatus.DIARIZING)
                AppLogger.info("ProcessingQueue", "Diarizing: ${recording.id}")

                try {
                    val speakerSegments = diarizationClient.diarize(
                        audioFilePath = recording.filePath,
                        serverUrl = settings.diarizationServerUrl
                    )

                    if (speakerSegments.isNotEmpty()) {
                        diarizedTranscription = diarizationClient.alignSpeakers(transcription, speakerSegments)
                        // Persist the updated transcription with speaker labels
                        recordingsRepository.saveTranscription(diarizedTranscription)
                        AppLogger.info(
                            "ProcessingQueue",
                            "Diarization complete for ${recording.id}: " +
                                "${speakerSegments.map { it.speaker }.distinct().size} speakers identified"
                        )
                    } else {
                        AppLogger.warn("ProcessingQueue", "Diarization returned no segments for ${recording.id}")
                    }
                } catch (e: Exception) {
                    // Diarization failure is non-fatal — continue with unspeaker'd transcription
                    AppLogger.warn("ProcessingQueue", "Diarization failed for ${recording.id}: ${e.message}")
                }
            }

            // Step 3: Summarize
            updateStatus(item.recordingId, ProcessingStatus.SUMMARIZING)
            AppLogger.info("ProcessingQueue", "Summarizing: ${recording.id}")

            val transcriptionForSummary = if (hasSpeakerLabels(diarizedTranscription.segments)) {
                diarizedTranscription.copy(text = formatWithSpeakers(diarizedTranscription.segments))
            } else {
                diarizedTranscription
            }

            // Resolve effective prompt template for the recording's meeting template
            val effectivePrompt = promptTemplateManager?.getEffectivePrompt(recording.template) ?: ""

            try {
                val summary = ollamaClient.summarize(
                    transcription = transcriptionForSummary,
                    config = settings.summarizationEndpoint,
                    description = recording.description,
                    attachmentCount = recording.attachments.size,
                    promptTemplate = effectivePrompt,
                    meetingTemplate = recording.template,
                    cloudLlmConfig = settings.cloudLlmConfig
                )

                if (summary.summary.isBlank()) {
                    AppLogger.error("ProcessingQueue", "Summarization returned empty result for ${recording.id}")
                    updateStatus(item.recordingId, ProcessingStatus.FAILED, "Summarization returned empty result")
                    return
                }

                recordingsRepository.saveSummary(summary)

                // Merge auto-generated tags back into the recording
                if (summary.tags.isNotEmpty()) {
                    val updatedTags = (recording.tags + summary.tags).distinct()
                    recordingsRepository.updateRecording(recording.copy(tags = updatedTags))
                }

                AppLogger.info("ProcessingQueue", "Summarization complete for ${recording.id}")
            } catch (e: Exception) {
                AppLogger.error("ProcessingQueue", "Summarization failed for ${recording.id}: ${e.message}", e)
                // Summarization failure is non-fatal: transcription is saved, mark as failed but don't lose the transcription
                updateStatus(item.recordingId, ProcessingStatus.FAILED, "Summarization failed: ${e.message}")
                return
            }

            updateStatus(item.recordingId, ProcessingStatus.COMPLETE)

        } catch (e: Exception) {
            val isConnectionError = e.message?.contains("Connection refused") == true ||
                e.message?.contains("ConnectException") == true ||
                e.message?.contains("Timed out") == true ||
                e.cause?.message?.contains("Connection refused") == true

            if (isConnectionError) {
                // Self-healing: retry after a delay instead of permanent failure
                val retryCount = (item.error?.substringAfter("retry:")?.toIntOrNull() ?: 0)
                if (retryCount < 5) {
                    val delaySeconds = (30 * (retryCount + 1)).coerceAtMost(300) // 30s, 60s, 90s, 120s, 150s
                    AppLogger.warn("ProcessingQueue", "Transcription server unreachable for ${recording.id}, retry ${retryCount + 1}/5 in ${delaySeconds}s")
                    updateStatus(item.recordingId, ProcessingStatus.WAITING, "retry:${retryCount + 1}")
                    // Delay before retrying
                    kotlinx.coroutines.delay(delaySeconds * 1000L)
                } else {
                    AppLogger.error("ProcessingQueue", "Failed processing ${recording.id} after 5 retries: ${e.message}", e)
                    updateStatus(item.recordingId, ProcessingStatus.FAILED, "Server unreachable after 5 retries")
                }
            } else {
                AppLogger.error("ProcessingQueue", "Failed processing ${recording.id}: ${e.message}", e)
                updateStatus(item.recordingId, ProcessingStatus.FAILED, e.message)
            }
        }
    }

    private fun updateStatus(recordingId: String, status: ProcessingStatus, error: String? = null) {
        _queue.update { currentQueue ->
            currentQueue.map {
                if (it.recordingId == recordingId) it.copy(status = status, error = error) else it
            }
        }
    }
}
