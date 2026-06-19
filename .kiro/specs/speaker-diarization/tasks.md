# Implementation Plan: Speaker Diarization

## Overview

This plan implements speaker diarization support for Jeeves, enabling transcription segments to be attributed to individual speakers. The implementation proceeds from data model changes through parsing logic, client integration, UI display, summarization enhancements, stereo audio support, and error handling — with property-based and unit tests validating correctness at each layer.

## Tasks

- [x] 1. Data model extensions and project setup
  - [x] 1.1 Add DiarizationMode enum and extend AppSettings
    - Add `DiarizationMode` enum (`DIARIZE`, `TINYDIARIZE`) to `com.jeeves.shared.domain` in `Models.kt`
    - Add `diarizationEnabled: Boolean = false`, `diarizationMode: DiarizationMode = DiarizationMode.TINYDIARIZE`, and `stereoRecording: Boolean = false` fields to `AppSettings`
    - _Requirements: 4.1, 4.3, 4.4, 6.3, 6.4_

  - [x] 1.2 Add `speaker` field to WhisperSegment and `diarizationUnavailable` flag to TranscriptionResult
    - Add `val speaker: String? = null` to `WhisperSegment` in `WhisperClient.kt`
    - Add `val diarizationUnavailable: Boolean = false` to `TranscriptionResult` in `Models.kt`
    - _Requirements: 2.1, 7.5_

  - [x] 1.3 Add Kotest property-based testing dependencies to `shared/build.gradle.kts`
    - Add `io.kotest:kotest-property:<version>` and `io.kotest:kotest-assertions-core:<version>` to `commonTest` dependencies
    - _Requirements: Testing infrastructure_

- [x] 2. Implement DiarizationResponseParser
  - [x] 2.1 Create `DiarizationResponseParser` class in `com.jeeves.shared.ai`
    - Implement `parseSegments(segments: List<WhisperSegment>, mode: DiarizationMode): List<TranscriptionSegment>`
    - Implement `parseDiarizeMode`: map non-empty speaker strings to `"Speaker {value}"`, null/empty/"?" to null
    - Implement `parseTinyDiarizeMode`: parse `[SPEAKER_TURN]` markers, assign incremental `"Speaker 0"`, `"Speaker 1"`, etc., remove markers from text
    - Handle edge cases: null speaker, empty string, non-string/invalid values → set speaker to null
    - _Requirements: 2.1, 2.2, 2.4, 7.4_

  - [ ]* 2.2 Write property test for speaker field mapping (Property 1)
    - **Property 1: Speaker Field Mapping Preserves Valid Labels and Normalizes Invalid Ones**
    - Generate random WhisperSegment lists with varied speaker values (valid strings, null, empty, "?")
    - Assert: non-empty strings produce `"Speaker {value}"`, null/empty produce null speaker
    - **Validates: Requirements 1.4, 2.1, 2.2, 7.4**

  - [ ]* 2.3 Write property test for speaker turn marker extraction (Property 3)
    - **Property 3: Speaker Turn Marker Extraction**
    - Generate random text strings with `[SPEAKER_TURN]` markers at random positions
    - Assert: no output text contains `[SPEAKER_TURN]`, speaker labels increment correctly per turn
    - **Validates: Requirements 2.4**

  - [ ]* 2.4 Write property test for TranscriptionResult serialization round-trip (Property 2)
    - **Property 2: TranscriptionResult Serialization Round-Trip**
    - Generate random TranscriptionResult objects with varying segments (including speaker labels, null speakers, diarizationUnavailable flag)
    - Assert: serialize to JSON then deserialize produces identical field values
    - **Validates: Requirements 2.3**

- [x] 3. Integrate diarization parsing into WhisperClient
  - [x] 3.1 Update WhisperClient to use DiarizationResponseParser
    - Accept `AppSettings` (or diarization fields) in the `transcribe` method signature
    - When `diarizationEnabled` is true, pass `WhisperSegment` list through `DiarizationResponseParser.parseSegments` using the configured mode
    - When disabled, map segments as before (speaker = null)
    - Include `diarize=true` form field when diarizationEnabled is true
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 3.2 Implement retry/fallback logic in WhisperClient
    - If HTTP 4xx with diarization-related error body: log warning, retry without `diarize` param within 5 seconds, set `diarizationUnavailable = true`
    - If HTTP 400 "unrecognized parameter": log warning, retry without param, set flag
    - If retry succeeds: return transcription with null speakers and flag set
    - _Requirements: 7.1, 7.2, 7.3, 7.5_

  - [ ]* 3.3 Write unit tests for WhisperClient diarization integration
    - Test: `diarize=true` included when setting enabled (Req 1.1)
    - Test: `diarize` field omitted when setting disabled (Req 1.2)
    - Test: response without speaker labels processed without error (Req 1.4)
    - Test: retry on 4xx sets `diarizationUnavailable` flag (Req 7.5)
    - _Requirements: 1.1, 1.2, 1.4, 7.1, 7.2, 7.3, 7.5_

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Update RecordingManager for diarization pipeline
  - [x] 5.1 Pass diarization settings through RecordingManager to WhisperClient
    - Update `processRecording` to pass `AppSettings` diarization fields to `whisperClient.transcribe`
    - Log diarization mode and enabled state at transcription start
    - _Requirements: 1.1, 1.2_

  - [x] 5.2 Implement speaker-attributed text formatting for summarization
    - Create `formatWithSpeakers(segments: List<TranscriptionSegment>): String` — prepend `"{speaker}: "` to segments with non-null speaker, plain text for null
    - Update `processRecording` to pass formatted text to OllamaClient when speakers present
    - _Requirements: 5.1, 5.2, 5.3_

  - [ ]* 5.3 Write property test for speaker-attributed text formatting (Property 6)
    - **Property 6: Speaker-Attributed Text Formatting**
    - Generate random segment lists with mixed null/non-null speaker fields
    - Assert: segments with speaker get `"{speaker}: "` prefix, null-speaker segments have no prefix, order preserved
    - **Validates: Requirements 5.1, 5.3**

- [x] 6. Update OllamaClient for speaker context in summarization
  - [x] 6.1 Modify OllamaClient to accept speaker-formatted transcription text
    - Update `summarize` to accept the full `TranscriptionResult` (or segments + text)
    - When segments contain speaker labels: use `formatWithSpeakers` output and add attribution instruction to prompt
    - When no speakers: use plain text without attribution instruction
    - _Requirements: 5.1, 5.2, 5.4_

  - [ ]* 6.2 Write unit tests for summarization prompt construction
    - Test: prompt includes attribution instruction when speakers present (Req 5.4)
    - Test: prompt omits attribution instruction when no speakers (Req 5.2)
    - Test: null-speaker segments included without prefix (Req 5.3)
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 7. Settings UI for diarization
  - [x] 7.1 Add diarization settings section to SettingsScreen
    - Add a `Card` section titled "Speaker Diarization" with:
      - Toggle switch for `diarizationEnabled`
      - Radio buttons or dropdown for `diarizationMode` (Diarize / TinyDiarize) — only enabled when diarization is on
      - Toggle switch for `stereoRecording` — only shown/enabled when mode is DIARIZE
    - Wire state changes through existing `settings` state and save flow
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.3_

  - [ ]* 7.2 Write unit tests for settings persistence round-trip
    - Test: new diarization fields persist and reload correctly through `FileSettingsRepository`
    - Test: default values correct when fields not present in stored JSON (backward compatibility)
    - Test: invalid diarization mode defaults to TINYDIARIZE (Req 4.4)
    - _Requirements: 4.1, 4.2, 4.4, 4.5, 6.3, 6.4_

- [x] 8. Speaker-attributed transcription display UI
  - [x] 8.1 Implement `groupBySpeaker` utility function
    - Create function in shared or desktop module that groups consecutive segments with the same speaker label
    - Returns `List<SpeakerGroup>` where `SpeakerGroup` contains speaker label and segment list
    - _Requirements: 3.2_

  - [ ]* 8.2 Write property test for consecutive same-speaker grouping (Property 4)
    - **Property 4: Consecutive Same-Speaker Grouping**
    - Generate random segment lists with repeating/varying speaker patterns
    - Assert: no two adjacent groups share the same speaker, concatenation equals original, group speaker matches all segment speakers within
    - **Validates: Requirements 3.2**

  - [x] 8.3 Implement `buildSpeakerColorMap` function
    - Assign palette colours by first-appearance order, cycling when speakers exceed palette size (6 colours)
    - _Requirements: 3.4, 3.5_

  - [ ]* 8.4 Write property test for speaker colour assignment (Property 5)
    - **Property 5: Speaker Color Assignment by First-Appearance Order**
    - Generate random segment lists with 1-20 unique speakers
    - Assert: first speaker gets index 0, Nth speaker gets (N-1) mod palette_size, same speaker always same colour
    - **Validates: Requirements 3.4, 3.5**

  - [x] 8.5 Create `SpeakerSegmentDisplay` composable and update `TranscriptionView`
    - Render grouped segments with speaker labels in bold above/beside text
    - Show speaker label only once per consecutive group (omit for subsequent segments in same group)
    - Apply background colour from speaker palette
    - Render segments with null speaker without label or colour
    - Update existing `TranscriptionView` in `RecordingsListScreen.kt` to use `SpeakerSegmentDisplay` when segments have speaker data
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 9. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Stereo audio recording support
  - [x] 10.1 Update `DesktopAudioRecorder` for stereo recording
    - Accept a `stereo: Boolean` parameter in `startRecording` (or resolve from settings)
    - Build `AudioFormat` with `channels = 2` when stereo, `channels = 1` when mono
    - Validate that the audio line supports the requested channel count; fall back to mono if not supported
    - Expose an error message via a state flow when stereo fallback occurs
    - Ensure channel config does not change mid-recording session
    - _Requirements: 6.1, 6.2, 6.5, 6.6_

  - [x] 10.2 Update `RecordingManager.startRecording` to pass stereo setting
    - Read `stereoRecording` and `diarizationEnabled` from `AppSettings`
    - Pass `stereo = diarizationEnabled && stereoRecording` to `audioRecorder.startRecording`
    - _Requirements: 6.1, 6.2_

  - [ ]* 10.3 Write unit tests for stereo recording logic
    - Test: stereo format used when diarization + stereo enabled (Req 6.1)
    - Test: mono format when diarization disabled (Req 6.2)
    - Test: mono format when stereo disabled (Req 6.2)
    - Test: fallback to mono when device doesn't support stereo (Req 6.5)
    - Test: channel config locked at session start (Req 6.6)
    - _Requirements: 6.1, 6.2, 6.5, 6.6_

- [x] 11. Error handling and diarization unavailable banner
  - [x] 11.1 Display diarization unavailable indicator in UI
    - When `TranscriptionResult.diarizationUnavailable` is true, show an info banner in the transcription detail view indicating "Speaker identification was requested but unavailable for this recording"
    - _Requirements: 7.5_

  - [ ]* 11.2 Write integration tests for error/retry flows
    - Test: retry without diarize param on 4xx error (Req 7.1, 7.2)
    - Test: retry on "unrecognized parameter" 400 (Req 7.3)
    - Test: malformed speaker field normalized to null with warning logged (Req 7.4)
    - Test: `diarizationUnavailable` flag propagated through pipeline (Req 7.5)
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The project uses Kotlin Multiplatform with Kotest for property-based testing
- Existing `ignoreUnknownKeys = true` in FileSettingsRepository ensures backward compatibility with new AppSettings fields
- The `TranscriptionSegment.speaker` field already exists in the domain model but is currently unused

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["2.1"] },
    { "id": 3, "tasks": ["2.2", "2.3", "2.4"] },
    { "id": 4, "tasks": ["3.1", "8.1", "8.3"] },
    { "id": 5, "tasks": ["3.2", "3.3", "8.2", "8.4"] },
    { "id": 6, "tasks": ["5.1", "5.2", "7.1"] },
    { "id": 7, "tasks": ["5.3", "6.1", "7.2"] },
    { "id": 8, "tasks": ["6.2", "8.5"] },
    { "id": 9, "tasks": ["10.1"] },
    { "id": 10, "tasks": ["10.2", "10.3"] },
    { "id": 11, "tasks": ["11.1", "11.2"] }
  ]
}
```
