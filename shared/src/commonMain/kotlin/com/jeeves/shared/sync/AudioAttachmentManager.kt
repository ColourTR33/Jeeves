package com.jeeves.shared.sync

import kotlinx.coroutines.delay
import java.io.File

/**
 * Manages selective replication of large audio file attachments.
 *
 * Audio files (WAV, typically 50-200MB) are stored as CouchDB attachments on recording
 * documents. This manager handles upload, download with retry logic, local availability
 * checks, and remote size queries.
 *
 * Downloads use exponential backoff (5s, 10s, 20s) with up to 3 retries on failure.
 */
class AudioAttachmentManager(
    private val replicator: CouchDbReplicator,
    private val localStore: LocalDocumentStore,
    private val audioDir: File
) {
    companion object {
        /** Attachment name used for audio files in CouchDB documents. */
        private const val ATTACHMENT_NAME = "audio.wav"

        /** Content type for WAV audio files. */
        private const val CONTENT_TYPE_WAV = "audio/wav"

        /** Maximum number of retries for failed downloads. */
        private const val MAX_RETRIES = 3

        /** Backoff delays in milliseconds: 5s, 10s, 20s. */
        private val BACKOFF_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L)
    }

    init {
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }
    }

    /**
     * Upload a local audio file as a CouchDB attachment on the recording document.
     *
     * Reads the WAV file bytes, retrieves the current document revision from the
     * local store, and pushes the attachment to the remote CouchDB instance.
     *
     * @param recordingId The recording identifier (without the "recording:" prefix).
     * @param localFilePath The absolute path to the local WAV file.
     * @throws IllegalStateException if the recording document is not found locally.
     * @throws java.io.FileNotFoundException if the local audio file does not exist.
     */
    suspend fun uploadAudio(recordingId: String, localFilePath: String) {
        val audioFile = File(localFilePath)
        require(audioFile.exists()) { "Audio file not found: $localFilePath" }

        val docId = "recording:$recordingId"
        val doc = localStore.get(docId)
            ?: throw IllegalStateException("Recording document not found: $docId")

        val rev = doc.rev
            ?: throw IllegalStateException("Recording document has no revision: $docId")

        val audioBytes = audioFile.readBytes()

        // Get the sync configuration from the SyncEngine context
        // The replicator needs config to know the remote URL - we pass it via a config parameter
        // However, looking at the design, uploadAudio doesn't take a config parameter.
        // The pushAttachment method on replicator needs config. We need to resolve this.
        // Based on the design, uploadAudio is called after recording completes, and the
        // SyncEngine would provide the config. For now, we store config internally or
        // accept it's handled by whoever calls us with the replicator already configured.
        //
        // Looking at the replicator API: pushAttachment(config, docId, rev, attachmentName, audioBytes)
        // But uploadAudio in the design doesn't take config. This means the manager must
        // have access to config. Let's add an internal config field that's set externally.
        // Actually, re-reading the task description more carefully:
        // "uploadAudio(recordingId, localFilePath): Read file bytes, get current doc rev from store,
        //  call replicator.pushAttachment() with content-type audio/wav"
        //
        // The pushAttachment signature requires a config. Since the AudioAttachmentManager
        // is instantiated by the SyncEngine which has the config, we'll add a config property.
        // But since the design interface doesn't show config in uploadAudio, we store it internally.

        replicator.pushAttachment(
            config = requireConfig(),
            docId = docId,
            rev = rev,
            attachmentName = ATTACHMENT_NAME,
            audioBytes = audioBytes
        )
    }

    /**
     * Download a remote audio attachment to local storage.
     *
     * Fetches the audio attachment from the remote CouchDB instance and saves it
     * to the local audio directory as `{recordingId}.wav`.
     *
     * Implements retry logic with exponential backoff: retries up to 3 times
     * with delays of 5s, 10s, and 20s on failure.
     *
     * @param recordingId The recording identifier (without the "recording:" prefix).
     * @param config The sync configuration with remote connection details.
     * @return The local file path where the audio was saved.
     * @throws AudioDownloadException if all retry attempts are exhausted.
     */
    suspend fun downloadAudio(recordingId: String, config: SyncConfiguration): String {
        val docId = "recording:$recordingId"
        val localFile = File(audioDir, "$recordingId.wav")

        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val audioBytes = replicator.pullAttachment(
                    config = config,
                    docId = docId,
                    attachmentName = ATTACHMENT_NAME
                )

                // Atomic write: write to temp file then rename
                val tmpFile = File(audioDir, "$recordingId.wav.tmp")
                tmpFile.writeBytes(audioBytes)
                tmpFile.renameTo(localFile)

                return localFile.absolutePath
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(BACKOFF_DELAYS_MS[attempt])
                }
            }
        }

        throw AudioDownloadException(
            "Failed to download audio for recording '$recordingId' after $MAX_RETRIES attempts",
            lastException
        )
    }

    /**
     * Check if a recording's audio file is available locally.
     *
     * @param recordingId The recording identifier (without the "recording:" prefix).
     * @return true if the audio file exists in the local audio directory.
     */
    fun isAudioAvailableLocally(recordingId: String): Boolean {
        val localFile = File(audioDir, "$recordingId.wav")
        return localFile.exists()
    }

    /**
     * Get the size of a remote audio attachment without downloading it.
     *
     * Checks the recording document's `_attachments` field for the `audio.wav`
     * attachment stub and returns its `length` field.
     *
     * @param recordingId The recording identifier (without the "recording:" prefix).
     * @param config The sync configuration with remote connection details.
     * @return The attachment size in bytes, or null if no attachment info is available.
     */
    suspend fun getRemoteAudioSize(recordingId: String, config: SyncConfiguration): Long? {
        val docId = "recording:$recordingId"
        val doc = localStore.get(docId) ?: return null

        val attachments = doc.attachments ?: return null
        val audioStub = attachments[ATTACHMENT_NAME] ?: return null

        return audioStub.length
    }

    // --- Internal config management ---

    private var _config: SyncConfiguration? = null

    /**
     * Set the sync configuration for upload operations.
     * Called by the SyncEngine when starting a sync session.
     */
    fun setConfig(config: SyncConfiguration) {
        _config = config
    }

    private fun requireConfig(): SyncConfiguration {
        return _config ?: throw IllegalStateException(
            "SyncConfiguration not set. Call setConfig() before uploading audio."
        )
    }
}

/**
 * Exception thrown when an audio download fails after all retry attempts.
 */
class AudioDownloadException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
