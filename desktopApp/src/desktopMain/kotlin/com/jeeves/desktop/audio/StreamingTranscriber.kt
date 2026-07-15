package com.jeeves.desktop.audio

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.ai.WhisperApiResponse
import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.AppSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Periodically extracts audio chunks from the recording buffer, wraps them
 * in WAV format, and sends them to the local whisper.cpp server for live
 * transcription feedback. The assembled transcript is exposed via [liveTranscript].
 *
 * The streaming transcript is purely a real-time preview — the existing
 * post-recording full-file transcription pipeline remains the authoritative version.
 */
class StreamingTranscriber(
    private val httpClient: HttpClient
) {
    /** Live transcript text, observed by RecordingScreen. */
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    /** Whether a chunk request is currently in flight. */
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    /**
     * Short status message displayed in the transcription panel.
     * Null = no special message (show normal transcript/placeholder).
     * Non-null = a status note (e.g. "Whisper server busy, retrying…").
     */
    private val _serverStatus = MutableStateFlow<String?>(null)
    val serverStatus: StateFlow<String?> = _serverStatus.asStateFlow()

    /** Coroutine job managing the streaming loop. */
    private var streamingJob: Job? = null

    /** Current streaming session state. */
    private var currentSession: StreamingSession? = null

    /**
     * Tracks streaming state within a recording session.
     */
    internal data class StreamingSession(
        val sampleRate: Int = 16000,
        val channels: Int,
        val bitsPerSample: Int = 16,
        val chunkIntervalSeconds: Int,
        val overlapWindowSeconds: Float
    ) {
        /** Byte offset into audioBuffer marking where the last extraction ended. */
        var lastReadPosition: Int = 0

        /** Tail bytes from the previous chunk, used for overlap prepend. */
        var previousChunkTail: ByteArray = ByteArray(0)

        /** Computed overlap size in bytes. */
        val overlapBytes: Int
            get() = (overlapWindowSeconds * sampleRate * channels * (bitsPerSample / 8)).toInt()
    }

    /**
     * Prepends a 44-byte WAV header to raw PCM data, producing a valid WAV file payload.
     *
     * WAV format: RIFF container with a single "data" chunk containing uncompressed PCM.
     * All multi-byte fields are little-endian.
     */
    fun buildWavPayload(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val dataSize = pcmData.size
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // ChunkID: "RIFF"
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())

            // ChunkSize: 36 + dataSize
            putInt(36 + dataSize)

            // Format: "WAVE"
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())

            // Subchunk1ID: "fmt "
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())

            // Subchunk1Size: 16 (PCM)
            putInt(16)

            // AudioFormat: 1 (PCM)
            putShort(1)

            // NumChannels
            putShort(channels.toShort())

            // SampleRate
            putInt(sampleRate)

            // ByteRate
            putInt(byteRate)

            // BlockAlign
            putShort(blockAlign.toShort())

            // BitsPerSample
            putShort(bitsPerSample.toShort())

            // Subchunk2ID: "data"
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())

            // Subchunk2Size
            putInt(dataSize)
        }

        return header.array() + pcmData
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Concatenates the overlap tail from the previous chunk with the current chunk.
     * If previousTail is shorter than the configured overlap, uses all available bytes.
     */
    fun applyOverlap(currentChunk: ByteArray, previousTail: ByteArray): ByteArray {
        return previousTail + currentChunk
    }

    /**
     * Deduplicates overlapping text between the existing transcript and new chunk text.
     * Finds the longest suffix of existing (last 20 words) that matches a prefix of newText (min 3 words).
     * If match found: appends only non-overlapping portion.
     * If no match ≥3 words: simple space-separated concatenation.
     * If newText is blank/empty: returns existing unchanged.
     */
    fun deduplicateAndAppend(existing: String, newText: String): String {
        if (newText.isBlank()) return existing
        if (existing.isEmpty()) return newText.trim()

        val existingWords = existing.split("\\s+".toRegex())
        val newWords = newText.trim().split("\\s+".toRegex())

        // Look at last 20 words of existing
        val tailWords = existingWords.takeLast(20)

        // Find longest suffix of tailWords that matches a prefix of newWords (min 3)
        var longestMatch = 0
        for (matchLen in minOf(tailWords.size, newWords.size) downTo 3) {
            val suffix = tailWords.takeLast(matchLen)
            val prefix = newWords.take(matchLen)
            if (suffix == prefix) {
                longestMatch = matchLen
                break
            }
        }

        val result = if (longestMatch >= 3) {
            // Append only the non-overlapping portion
            val nonOverlapping = newWords.drop(longestMatch).joinToString(" ")
            if (nonOverlapping.isEmpty()) existing
            else "$existing $nonOverlapping"
        } else {
            "$existing ${newText.trim()}"
        }

        // Cap live transcript at 50,000 characters (~8000 words) to limit memory usage.
        // For the post-recording full transcription, the full file is re-processed by Whisper anyway.
        return if (result.length > 50_000) {
            result.takeLast(50_000)
        } else {
            result
        }
    }

    /**
     * Extracts new audio bytes from the recorder's buffer since the last read position.
     * Thread-safe: uses the recorder's synchronized getBufferSnapshot method.
     */
    internal fun extractChunk(audioRecorder: DesktopAudioRecorder, session: StreamingSession): ByteArray {
        val newBytes = audioRecorder.getBufferSnapshot(session.lastReadPosition)
        session.lastReadPosition += newBytes.size
        return newBytes
    }

    /**
     * Sends a WAV audio chunk to the whisper server for transcription.
     * Returns the transcribed text on success, or null on failure/timeout.
     * Uses an 8-second timeout — if the server is busy we skip the chunk rather
     * than block the streaming loop for half a minute.
     */
    suspend fun sendChunkForTranscription(wavData: ByteArray, config: AiEndpointConfig): String? {
        return try {
            _isTranscribing.value = true

            val result = withTimeoutOrNull(8_000L) {
                val endpoints = listOf(
                    "${config.baseUrl}/v1/audio/inference",
                    "${config.baseUrl}/inference"
                )

                for (endpoint in endpoints) {
                    try {
                        val response = httpClient.submitFormWithBinaryData(
                            url = endpoint,
                            formData = formData {
                                append("file", wavData, Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"chunk.wav\"")
                                    append(HttpHeaders.ContentType, "audio/wav")
                                })
                                append("model", config.modelName)
                                append("response_format", "verbose_json")
                                append("language", "en")
                            }
                        )

                        val responseBody: String = response.body()

                        if (response.status.isSuccess()) {
                            val whisperResponse = json.decodeFromString<WhisperApiResponse>(responseBody)
                            return@withTimeoutOrNull whisperResponse.text
                        }
                    } catch (e: Exception) {
                        AppLogger.error("StreamingTranscriber", "Chunk request failed on $endpoint: ${e.message}")
                    }
                }
                null
            }

            result
        } catch (e: Exception) {
            AppLogger.error("StreamingTranscriber", "Chunk transcription error: ${e.message}")
            null
        } finally {
            _isTranscribing.value = false
        }
    }

    /**
     * Checks if the whisper server is reachable.
     * Returns true if the server responds within 3 seconds.
     */
    suspend fun checkServerConnectivity(baseUrl: String): Boolean {
        return try {
            withTimeoutOrNull(3000L) {
                val response = httpClient.get("${baseUrl}/health")
                response.status.isSuccess() || response.status.value == 404 // 404 means server is up, just no /health route
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Begin streaming transcription for a recording session.
     * Performs a best-effort connectivity check (failure does NOT abort streaming —
     * we try to send chunks anyway and skip those that time out while the server is busy).
     * Returns immediately; work happens in a child coroutine scope.
     */
    fun startStreaming(
        audioRecorder: DesktopAudioRecorder,
        settings: AppSettings,
        parentScope: CoroutineScope
    ) {
        // Reset live transcript and status for the new session
        _liveTranscript.value = ""
        _serverStatus.value = null

        // No-op if streaming is disabled
        if (!settings.streamingEnabled) return

        // Create a streaming session with the current settings
        val channels = audioRecorder.sessionFormat?.channels ?: 1
        val session = StreamingSession(
            channels = channels,
            chunkIntervalSeconds = settings.chunkIntervalSeconds,
            overlapWindowSeconds = settings.overlapWindowSeconds
        )
        currentSession = session

        // Choose the right endpoint: dedicated streaming endpoint if configured, else fall back to main
        val streamingEndpoint = settings.streamingTranscriptionEndpoint ?: settings.transcriptionEndpoint

        // Launch the streaming loop as a child job
        streamingJob = parentScope.launch {
            // Best-effort connectivity check — just warn, don't abort
            val serverReachable = checkServerConnectivity(streamingEndpoint.baseUrl)
            if (!serverReachable) {
                AppLogger.warn(
                    "StreamingTranscriber",
                    "Whisper server health check failed at ${streamingEndpoint.baseUrl} — will try chunks anyway"
                )
                _serverStatus.value = "Connecting to transcription server…"
            }

            AppLogger.info("StreamingTranscriber", "Streaming transcription started (endpoint: ${streamingEndpoint.baseUrl})")

            var consecutiveFailures = 0

            while (isActive) {
                delay(session.chunkIntervalSeconds * 1000L)

                // If recorder stopped (not just paused), the loop will be cancelled externally
                if (!audioRecorder.isRecording.value) {
                    continue
                }

                // Extract new audio bytes since last read
                val chunk = extractChunk(audioRecorder, session)
                if (chunk.isEmpty()) continue

                // Apply overlap: prepend previous chunk tail
                val overlappedChunk = applyOverlap(chunk, session.previousChunkTail)

                // Store the tail of the current chunk for next overlap
                val tailSize = minOf(session.overlapBytes, chunk.size)
                session.previousChunkTail = if (tailSize > 0) {
                    chunk.copyOfRange(chunk.size - tailSize, chunk.size)
                } else {
                    ByteArray(0)
                }

                // Build WAV payload from the overlapped chunk
                val wavPayload = buildWavPayload(
                    pcmData = overlappedChunk,
                    sampleRate = session.sampleRate,
                    channels = session.channels,
                    bitsPerSample = session.bitsPerSample
                )

                // Send for transcription — if server is busy the 8s timeout fires and we skip
                val result = sendChunkForTranscription(wavPayload, streamingEndpoint)

                if (!result.isNullOrBlank()) {
                    consecutiveFailures = 0
                    _serverStatus.value = null  // Clear any "busy" message once we get a result
                    _liveTranscript.value = deduplicateAndAppend(_liveTranscript.value, result)
                } else {
                    consecutiveFailures++
                    // After 2 failures show a note, after 5 escalate the message
                    _serverStatus.value = when {
                        consecutiveFailures >= 5 -> "Transcription server busy — audio buffered, will catch up…"
                        consecutiveFailures >= 2 -> "Connecting to transcription server…"
                        else -> _serverStatus.value // keep existing message
                    }
                    AppLogger.warn("StreamingTranscriber", "Chunk $consecutiveFailures missed (server busy or timeout)")
                }
            }
        }
    }

    /**
     * Stop streaming and release all resources.
     * Cancels in-flight requests and clears overlap buffer.
     * Does NOT clear liveTranscript — it is retained until final transcription replaces it.
     */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        currentSession?.previousChunkTail = ByteArray(0)
        currentSession = null
        _isTranscribing.value = false
        _serverStatus.value = null
    }
}
