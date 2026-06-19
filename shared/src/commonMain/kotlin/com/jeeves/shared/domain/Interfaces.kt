package com.jeeves.shared.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific audio recorder.
 */
interface AudioRecorder {
    val isRecording: StateFlow<Boolean>

    suspend fun startRecording(outputPath: String)
    suspend fun stopRecording(): String
    suspend fun pauseRecording()
    suspend fun resumeRecording()
}

/**
 * Transcription service that converts audio to text.
 */
interface TranscriptionService {
    suspend fun transcribe(audioFilePath: String, config: AiEndpointConfig): TranscriptionResult
}

/**
 * Summarization service that creates summaries from transcriptions.
 */
interface SummarizationService {
    suspend fun summarize(transcription: TranscriptionResult, config: AiEndpointConfig): SummaryResult
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
