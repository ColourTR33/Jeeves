package com.jeeves.shared.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific audio recorder.
 */
interface AudioRecorder {
    val isRecording: StateFlow<Boolean>

    /**
     * Start recording audio to the specified output path.
     * @param outputPath path to the output WAV file
     * @param stereo if true, attempt to record in 2-channel stereo; falls back to mono if unsupported
     */
    suspend fun startRecording(outputPath: String, stereo: Boolean = false)
    suspend fun stopRecording(): String
    suspend fun pauseRecording()
    suspend fun resumeRecording()
}

/**
 * Transcription service that converts audio to text.
 */
interface TranscriptionService {
    suspend fun transcribe(
        audioFilePath: String,
        config: AiEndpointConfig,
        diarizationEnabled: Boolean = false,
        diarizationMode: DiarizationMode = DiarizationMode.TINYDIARIZE
    ): TranscriptionResult
}

/**
 * Summarization service that creates summaries from transcriptions.
 */
interface SummarizationService {
    suspend fun summarize(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String = "",
        attachmentCount: Int = 0
    ): SummaryResult
}

/**
 * Settings persistence.
 */
interface SettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun saveSettings(settings: AppSettings)
}

/**
 * Recordings persistence.
 */
interface RecordingsRepository {
    suspend fun saveRecording(recording: Recording)
    suspend fun getRecordings(): List<Recording>
    suspend fun getRecording(id: String): Recording?
    suspend fun updateRecording(recording: Recording)
    suspend fun deleteRecording(id: String)
    suspend fun saveTranscription(result: TranscriptionResult)
    suspend fun getTranscription(recordingId: String): TranscriptionResult?
    suspend fun saveSummary(result: SummaryResult)
    suspend fun getSummary(recordingId: String): SummaryResult?
}
