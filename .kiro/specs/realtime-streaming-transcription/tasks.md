# Implementation Plan: Real-Time Streaming Transcription

## Overview

Implements live transcription feedback during recording sessions. A `StreamingTranscriber` component periodically extracts audio chunks from the recording buffer, sends them to the local whisper.cpp server, and displays progressive transcript text on the RecordingScreen. The existing final transcription pipeline remains unchanged as the authoritative version.

## Tasks

- [x] 1. Extend AppSettings and Settings UI
  - [x] 1.1 Add streaming fields to AppSettings data class
    - Add `streamingEnabled: Boolean = true`, `chunkIntervalSeconds: Int = 5`, `overlapWindowSeconds: Float = 2.0f` fields to the `AppSettings` data class in `shared/src/commonMain/kotlin/com/jeeves/shared/domain/Models.kt`
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 1.2 Add streaming settings validation functions
    - Create a validation utility in `shared/src/commonMain/kotlin/com/jeeves/shared/domain/StreamingSettingsValidation.kt`
    - Implement `validateChunkInterval(value: Int): Boolean` (valid range 3–30)
    - Implement `validateOverlapWindow(value: Float): Boolean` (valid range 0.5–5.0)
    - Implement `validateOverlapLessThanInterval(overlap: Float, interval: Int): Boolean`
    - _Requirements: 6.2, 6.3, 6.7_

  - [x] 1.3 Add Streaming Transcription card to SettingsScreen
    - Add a new "Streaming Transcription" card in `desktopApp/.../ui/screens/SettingsScreen.kt`
    - Include toggle switch for `streamingEnabled`
    - Include number field for `chunkIntervalSeconds` (3–30) with validation error display
    - Include number field for `overlapWindowSeconds` (0.5–5.0, step 0.1) with validation error display
    - Add cross-field validation: overlap must be < chunkInterval; show inline error when violated
    - Disable Save button when any streaming validation fails
    - _Requirements: 6.6, 6.7_

  - [x] 1.4 Write property tests for settings validation (Properties 5 and 6)
    - **Property 5: Settings Range Validation** — For any integer, `validateChunkInterval` returns true iff value is in [3, 30]; for any float, `validateOverlapWindow` returns true iff value is in [0.5, 5.0]
    - **Property 6: Overlap Must Be Less Than Interval** — For any pair `(overlap, interval)`, cross-field validation returns valid iff `overlap < interval`
    - Place tests in `shared/src/commonTest/kotlin/com/jeeves/shared/domain/StreamingSettingsValidationPropertyTest.kt`
    - Use `io.kotest.property.checkAll` with Arb.int() and Arb.float()
    - **Validates: Requirements 6.2, 6.3, 6.7**

- [x] 2. Implement StreamingTranscriber core logic
  - [x] 2.1 Create StreamingTranscriber class with WAV header generation
    - Create `desktopApp/src/desktopMain/kotlin/com/jeeves/desktop/audio/StreamingTranscriber.kt`
    - Implement `buildWavPayload(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray` that prepends a 44-byte WAV header to raw PCM
    - Implement `StreamingSession` internal data class to track `lastReadPosition`, `previousChunkTail`, and computed `overlapBytes`
    - Expose `liveTranscript: StateFlow<String>` and `isTranscribing: StateFlow<Boolean>`
    - _Requirements: 1.2, 3.3_

  - [ ] 2.2 Write property test for WAV header correctness (Property 1)
    - **Property 1: WAV Header Correctness** — For any valid PCM byte array and channel count (1 or 2), `buildWavPayload` produces correct RIFF/WAVE markers, correct ChunkSize, matching NumChannels, SampleRate = 16000, correct Subchunk2Size, and total length = input + 44
    - Place test in `desktopApp/src/desktopTest/kotlin/com/jeeves/desktop/audio/StreamingTranscriberPropertyTest.kt`
    - Add kotest-property test dependency to desktopApp build if not present
    - Custom `Arb.pcmByteArray()` generator (even-length byte arrays)
    - **Validates: Requirements 1.2**

  - [x] 2.3 Implement overlap prepend logic
    - Implement `applyOverlap(currentChunk: ByteArray, previousTail: ByteArray): ByteArray` that concatenates previousTail + currentChunk
    - Handle edge case: if previous chunk is shorter than overlap window, use all available bytes
    - _Requirements: 1.3_

  - [ ] 2.4 Write property test for overlap prepend concatenation (Property 2)
    - **Property 2: Overlap Prepend Concatenation** — For any previousTail and currentChunk byte arrays, result has length = sum of both, starts with previousTail bytes, ends with currentChunk bytes
    - Place test alongside Property 1 test file
    - **Validates: Requirements 1.3**

  - [x] 2.5 Implement suffix-prefix deduplication
    - Implement `deduplicateAndAppend(existing: String, newText: String): String`
    - Split existing tail into last 20 words, find longest suffix-prefix match (minimum 3 words)
    - If match found: append only non-overlapping portion; if no match: simple space-separated concatenation
    - Handle empty/whitespace-only newText by returning existing unchanged
    - _Requirements: 3.1, 3.2, 3.6_

  - [ ] 2.6 Write property tests for deduplication and whitespace rejection (Properties 3 and 4)
    - **Property 3: Suffix-Prefix Deduplication** — For two word sequences with K≥3 overlap, result word count = |A| + |B| - K; for no overlap ≥3, result = A + " " + B
    - **Property 4: Whitespace-Only Text Rejection** — For any whitespace/empty string, transcript remains unchanged
    - Custom generators: `Arb.wordSequence()`, `Arb.overlappingWordPair()`, `Arb.whitespaceString()`
    - Place test alongside Property 1 and 2 test file
    - **Validates: Requirements 3.1, 3.2, 3.6**

  - [x] 2.7 Implement chunk extraction and HTTP transport
    - Implement `extractChunk(audioRecorder: DesktopAudioRecorder, session: StreamingSession): ByteArray` using synchronized read of new bytes since `lastReadPosition` via `audioRecorder.getBufferSnapshot()`
    - Implement `sendChunkForTranscription(wavData: ByteArray, config: AiEndpointConfig): String?` — multipart POST to /inference with 30s timeout, parse verbose_json response (tries both `/v1/audio/inference` and `/inference` endpoints)
    - Implement `checkServerConnectivity(baseUrl: String): Boolean` — GET /health with 3s timeout, accepts 200 or 404 (server up, no health route)
    - _Requirements: 1.1, 2.1, 2.2, 2.3, 2.5, 7.4, 8.1, 8.2, 8.3_

  - [x] 2.8 Implement streaming lifecycle (startStreaming / stopStreaming)
    - Implement `startStreaming(audioRecorder: DesktopAudioRecorder, settings: AppSettings, parentScope: CoroutineScope)` — connectivity check, then launch periodic loop
    - Implement `stopStreaming()` — cancel child scope, clear previousChunkTail, release references
    - Handle pause/resume: suspend chunk extraction when recording paused, wait one full interval after resume
    - Process chunks sequentially (next chunk only after previous request completes or times out)
    - Reset liveTranscript to empty on startStreaming
    - _Requirements: 1.1, 1.4, 1.5, 2.4, 3.4, 3.5, 6.4, 7.1, 7.2, 7.3, 7.5_

  - [ ] 2.9 Write unit tests for StreamingTranscriber
    - Test: parse verbose_json response extracts text correctly
    - Test: HTTP error returns null without throwing
    - Test: timeout returns null after 30s
    - Test: liveTranscript retains value after stopStreaming
    - Test: liveTranscript resets to empty on startStreaming
    - Test: streamingEnabled=false prevents chunk extraction
    - Test: server unreachable disables streaming for session
    - Test: stopStreaming clears previousChunkTail
    - _Requirements: 2.2, 2.3, 2.5, 3.4, 3.5, 6.4, 7.2, 7.4_

- [x] 3. Expose audio buffer for chunk extraction
  - [x] 3.1 Add read-only buffer access to DesktopAudioRecorder
    - Add a `fun getBufferSnapshot(fromOffset: Int): ByteArray` method to `DesktopAudioRecorder` that returns bytes from the given offset to current buffer size, using `synchronized(audioBuffer)` for thread safety
    - Add a `fun getBufferSize(): Int` property/method to check current buffer length (also synchronized)
    - Ensure the copy is performed under the existing `audioBuffer` lock and the lock is held for the minimum time necessary (copy bytes only, no I/O under lock)
    - _Requirements: 8.1, 8.2, 8.3_

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Integrate StreamingTranscriber with RecordingManager
  - [x] 5.1 Wire StreamingTranscriber into RecordingManager via StreamingCallback
    - Define `StreamingCallback` interface in shared module with `onPreRecordingSetup`, `onRecordingStarted`, `onRecordingStopping`, `getStreamingTranscript`
    - `RecordingManager` accepts optional `StreamingCallback` parameter and calls it at appropriate lifecycle points
    - `onRecordingStarted(settings)` called after `audioRecorder.startRecording()` succeeds — triggers `streamingTranscriber.startStreaming()`
    - `onRecordingStopping()` called at beginning of `stopRecording()` before stopping recorder — triggers `streamingTranscriber.stopStreaming()`
    - Streaming only starts if `settings.streamingEnabled` is true (enforced inside `startStreaming`)
    - _Requirements: 1.1, 5.1, 6.4, 7.1, 7.5_

  - [x] 5.2 Update RecordingManager instantiation in AppState/DI
    - Create `DesktopStreamingCallback` in `AppInitializer.kt` that implements `StreamingCallback` and bridges to `StreamingTranscriber`
    - Instantiate `StreamingTranscriber(httpClient)` in AppInitializer
    - Pass `DesktopStreamingCallback` to `RecordingManager` constructor as `streamingCallback`
    - Inject `streamingTranscriber` into `AppState` for UI access to `liveTranscript` and `isTranscribing` StateFlows
    - _Requirements: 7.3_

  - [ ] 5.3 Write integration tests for RecordingManager + StreamingTranscriber
    - Test: full chunk extraction + transcription cycle with mock server (Reqs 1.1, 2.1, 2.4)
    - Test: pause suspends extraction, resume restarts timer (Reqs 1.4, 1.5)
    - Test: scope cancel stops all work (Req 7.3)
    - Test: sequential processing — second chunk waits for first (Req 2.4)
    - Test: stop recording triggers final transcription unchanged (Reqs 5.1, 5.2)
    - _Requirements: 1.1, 1.4, 1.5, 2.1, 2.4, 5.1, 5.2, 7.3_

- [x] 6. Implement RecordingScreen live transcript UI
  - [x] 6.1 Add live transcript container to RecordingScreen
    - Add a scrollable `Column` or `SelectionContainer` below the audio level meter, constrained to `maxHeight = 200.dp`
    - Collect `streamingTranscriber.liveTranscript` as state and display in `Text` composable
    - Implement auto-scroll via `LaunchedEffect` on transcript changes (only when user is at bottom)
    - Respect manual scroll: if user scrolled up, do not auto-scroll until they return to bottom
    - Hide the entire live transcript section when `streamingEnabled = false`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.5_

  - [x] 6.2 Add in-flight indicator and placeholder
    - Display a pulsing ellipsis or "Transcribing..." label when `isTranscribing` is true
    - Display placeholder text ("Listening for speech…") when recording is active but liveTranscript is empty
    - _Requirements: 4.6, 4.7_

  - [x] 6.3 Handle final transcription replacement
    - When recording state transitions to PROCESSING, keep displaying the live transcript
    - When the final transcription becomes available, replace the live transcript display with the final transcription text
    - _Requirements: 4.5, 5.3_

- [x] 7. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The `StreamingTranscriber` class lives entirely in the desktopApp module since it depends on `DesktopAudioRecorder`'s JVM audio buffer
- Settings validation lives in the shared module since `AppSettings` is shared
- The existing `kotest-property:5.8.0` dependency in `commonTest` covers shared-module property tests; desktopApp tests will need the same dependency added

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "1.4", "2.1", "3.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.5"] },
    { "id": 3, "tasks": ["2.4", "2.6", "2.7"] },
    { "id": 4, "tasks": ["2.8"] },
    { "id": 5, "tasks": ["2.9", "5.1"] },
    { "id": 6, "tasks": ["5.2"] },
    { "id": 7, "tasks": ["5.3", "6.1"] },
    { "id": 8, "tasks": ["6.2", "6.3"] }
  ]
}
```
