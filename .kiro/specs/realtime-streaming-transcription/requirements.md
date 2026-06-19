# Requirements Document

## Introduction

Real-time streaming transcription for Jeeves. During a recording session, the application periodically sends accumulated audio chunks to the local whisper.cpp server and displays intermediate transcription text on the RecordingScreen as the user speaks. The final post-recording transcription remains the authoritative version saved for accuracy, while the live preview gives immediate feedback during the meeting.

## Glossary

- **Streaming_Transcriber**: The component responsible for periodically sending audio chunks to the Whisper_Server during an active recording session and collecting intermediate transcription results.
- **Whisper_Server**: The local whisper.cpp server running at the configured transcription endpoint (e.g., http://127.0.0.1:8178), which accepts audio data via its /inference endpoint and returns transcription text.
- **Audio_Chunk**: A segment of PCM audio data extracted from the recording buffer at a configured interval, formatted as a valid WAV payload suitable for the Whisper_Server.
- **Chunk_Interval**: The configurable time period (in seconds) between successive audio chunk submissions to the Whisper_Server during streaming transcription.
- **Overlap_Window**: A configurable duration of audio (in seconds) prepended from the end of the previous chunk to the beginning of the current chunk to prevent word-boundary truncation.
- **Live_Transcript**: The accumulated intermediate transcription text displayed on the RecordingScreen during an active recording session.
- **Final_Transcription**: The authoritative transcription produced by processing the complete audio file after recording stops, which is saved to the recordings repository.
- **Recording_Screen**: The Compose Desktop UI screen that displays recording status, timer, audio level meter, and (with this feature) the Live_Transcript.
- **Audio_Buffer**: The ByteArrayOutputStream in DesktopAudioRecorder that accumulates raw PCM audio data during a recording session.

## Requirements

### Requirement 1: Periodic Audio Chunk Extraction

**User Story:** As a user, I want the application to periodically extract audio data from the recording buffer during a session, so that intermediate transcriptions can be produced without waiting for the recording to end.

#### Acceptance Criteria

1. WHILE the recording state is RECORDING, THE Streaming_Transcriber SHALL extract an Audio_Chunk from the Audio_Buffer every Chunk_Interval seconds, with a tolerance of no more than 1 second beyond the configured interval.
2. THE Streaming_Transcriber SHALL format each extracted Audio_Chunk as a valid WAV payload with the correct header (16kHz, 16-bit PCM, matching the session channel count).
3. THE Streaming_Transcriber SHALL prepend Overlap_Window seconds of audio from the end of the previous chunk to the beginning of each new Audio_Chunk (except the first chunk). IF the available audio from the previous chunk is shorter than Overlap_Window, THEN THE Streaming_Transcriber SHALL prepend all available audio from the previous chunk.
4. WHILE the recording state is PAUSED, THE Streaming_Transcriber SHALL suspend chunk extraction until recording resumes.
5. WHEN recording resumes after a pause, THE Streaming_Transcriber SHALL wait one full Chunk_Interval before extracting the next Audio_Chunk, starting the timer from the moment recording resumes, and SHALL extract only audio recorded after the pause without re-sending previously transcribed audio.

### Requirement 2: Intermediate Transcription Requests

**User Story:** As a user, I want each audio chunk to be sent to the whisper server for transcription, so that I receive progressive text results during the recording.

#### Acceptance Criteria

1. WHEN an Audio_Chunk is extracted, THE Streaming_Transcriber SHALL send the chunk to the Whisper_Server /inference endpoint as a multipart form upload with the audio file, model name, and response_format set to "verbose_json".
2. WHEN the Whisper_Server returns a successful response, THE Streaming_Transcriber SHALL parse the transcription text from the response.
3. IF the Whisper_Server returns an HTTP error or the request times out, THEN THE Streaming_Transcriber SHALL log the error and skip the failed chunk without interrupting the recording session.
4. THE Streaming_Transcriber SHALL process chunk requests sequentially, sending the next chunk only after the previous request completes or times out.
5. THE Streaming_Transcriber SHALL apply a per-request timeout of 30 seconds to each chunk transcription request.

### Requirement 3: Live Transcript Assembly

**User Story:** As a user, I want intermediate transcription results to be assembled into a coherent running transcript, so that I can read a continuous flow of text as I speak.

#### Acceptance Criteria

1. WHEN a successful transcription response containing non-empty text is received for a chunk, THE Streaming_Transcriber SHALL append the new transcription text to the Live_Transcript, separated from the previous text by a single space.
2. THE Streaming_Transcriber SHALL deduplicate overlapping text between consecutive chunk responses caused by the Overlap_Window by performing a longest suffix-prefix match (minimum 3 words) between the end of the existing Live_Transcript and the beginning of the new chunk text, and removing the matched prefix from the new chunk before appending.
3. THE Streaming_Transcriber SHALL expose the Live_Transcript as a Kotlin StateFlow so that the UI can reactively observe changes.
4. WHEN the recording stops, THE Streaming_Transcriber SHALL retain the Live_Transcript until the Final_Transcription replaces it.
5. WHEN a new recording session starts, THE Streaming_Transcriber SHALL initialize the Live_Transcript to an empty string.
6. IF a transcription response contains only empty or whitespace-only text, THEN THE Streaming_Transcriber SHALL discard that response without modifying the Live_Transcript.

### Requirement 4: Live Transcript Display on Recording Screen

**User Story:** As a user, I want to see the transcription text appearing live on the recording screen while I speak, so that I get immediate visual feedback that my speech is being captured.

#### Acceptance Criteria

1. WHILE the recording state is RECORDING, THE Recording_Screen SHALL display the Live_Transcript text in a scrollable container positioned below the audio level meter.
2. WHEN new transcript content arrives and the user has not manually scrolled away from the bottom, THE Recording_Screen SHALL auto-scroll to show the most recently added text.
3. IF the user has manually scrolled the Live_Transcript container upward, THEN THE Recording_Screen SHALL not auto-scroll until the user scrolls back to the bottom.
4. THE Recording_Screen SHALL constrain the Live_Transcript scrollable container to a maximum height of 200dp, allowing the user to scroll back through earlier text.
5. WHEN the recording state transitions to PROCESSING, THE Recording_Screen SHALL continue displaying the Live_Transcript until the Final_Transcription is available.
6. WHILE a chunk transcription request is in flight, THE Recording_Screen SHALL display a visual indicator (such as a pulsing ellipsis or label) to signal that more text may arrive.
7. WHILE the recording state is RECORDING and the Live_Transcript is empty, THE Recording_Screen SHALL display a placeholder message indicating that transcript text will appear once speech is detected.

### Requirement 5: Final Transcription Precedence

**User Story:** As a user, I want the final full-file transcription to be the authoritative saved version, so that I get maximum accuracy in my meeting records regardless of any streaming approximations.

#### Acceptance Criteria

1. WHEN the recording stops, THE Recording_Manager SHALL send the complete audio file to the Whisper_Server for a full transcription, following the existing post-recording pipeline.
2. THE Recording_Manager SHALL save the Final_Transcription (not the Live_Transcript) to the recordings repository.
3. WHEN the Final_Transcription is available, THE Recording_Screen SHALL replace the Live_Transcript display with the Final_Transcription text.

### Requirement 6: Streaming Configuration

**User Story:** As a user, I want to configure the streaming transcription parameters, so that I can tune the balance between responsiveness and server load.

#### Acceptance Criteria

1. THE AppSettings SHALL include a streamingEnabled boolean field that defaults to true.
2. THE AppSettings SHALL include a chunkIntervalSeconds integer field with a default value of 5 and a valid range of 3 to 30 seconds.
3. THE AppSettings SHALL include an overlapWindowSeconds floating-point field with a default value of 2.0 and a valid range of 0.5 to 5.0 seconds, adjustable in 0.1-second increments.
4. IF streamingEnabled is false, THEN THE Streaming_Transcriber SHALL not extract or send any Audio_Chunks during recording.
5. IF streamingEnabled is false, THEN THE Recording_Screen SHALL not display a Live_Transcript area.
6. THE Settings_Screen SHALL expose controls for streamingEnabled, chunkIntervalSeconds, and overlapWindowSeconds, and SHALL prevent saving when chunkIntervalSeconds or overlapWindowSeconds values are outside their valid ranges by disabling the save action and displaying an error indication next to the invalid field.
7. THE AppSettings SHALL enforce that overlapWindowSeconds is less than chunkIntervalSeconds; IF the user sets an overlapWindowSeconds value greater than or equal to chunkIntervalSeconds, THEN THE Settings_Screen SHALL reject the value and display an error indication.

### Requirement 7: Resource Management and Lifecycle

**User Story:** As a user, I want streaming transcription to clean up resources properly, so that it does not leak memory or leave orphaned network requests.

#### Acceptance Criteria

1. WHEN the recording stops or is cancelled, THE Streaming_Transcriber SHALL cancel any in-flight chunk transcription request within 1 second.
2. WHEN the recording stops or is cancelled, THE Streaming_Transcriber SHALL release references to accumulated audio data used for overlap so that no chunk byte arrays from the ended session remain reachable by the Streaming_Transcriber.
3. THE Streaming_Transcriber SHALL use structured concurrency (coroutine scope tied to the recording session) so that all streaming work is cancelled if the recording session ends.
4. IF the Whisper_Server does not respond to a connection attempt within 3 seconds or returns a connection-refused error at the start of a recording, THEN THE Streaming_Transcriber SHALL log a warning and disable streaming for that session without preventing the recording from starting.
5. WHEN a recording session ends, THE Streaming_Transcriber SHALL complete all resource cleanup (request cancellation and memory release) before the system allows a new recording session to start streaming.

### Requirement 8: Thread Safety for Audio Buffer Access

**User Story:** As a developer, I want audio buffer reads for chunk extraction to be thread-safe, so that the streaming transcriber and the audio recorder do not corrupt shared data.

#### Acceptance Criteria

1. THE Streaming_Transcriber SHALL read audio data from the Audio_Buffer without blocking the recording thread for more than 1 millisecond.
2. THE Streaming_Transcriber SHALL synchronize access to the Audio_Buffer using the same lock mechanism already used by DesktopAudioRecorder (synchronized block on audioBuffer).
3. THE Streaming_Transcriber SHALL copy the required byte range out of the Audio_Buffer under the lock and perform all WAV formatting and network I/O outside the lock.
