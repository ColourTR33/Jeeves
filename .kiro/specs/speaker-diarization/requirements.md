# Requirements Document

## Introduction

Speaker Diarization adds the ability to identify and label distinct speakers within multi-person meeting recordings in Jeeves. This feature leverages the existing `--diarize` and `--tinydiarize` flags available in the whisper.cpp server to annotate transcription segments with speaker labels. The existing `TranscriptionSegment.speaker` field (currently unused) will be populated, and the UI will display speaker-attributed text to help users understand who said what during a meeting.

## Glossary

- **Whisper_Server**: The local whisper.cpp HTTP server that performs audio transcription at the `/v1/audio/inference` endpoint
- **Diarization_Engine**: The component within the Whisper_Server that identifies and labels distinct speakers in audio, activated via the `--diarize` or `--tinydiarize` server flags
- **Speaker_Label**: A string identifier assigned to a segment of speech (e.g., "Speaker 0", "Speaker 1") produced by the Diarization_Engine
- **Transcription_Pipeline**: The sequence of operations from audio file input through transcription to segment output, managed by the RecordingManager
- **Diarization_Response_Parser**: The component that extracts speaker labels from the Whisper_Server response and maps them to TranscriptionSegment objects
- **Speaker_Display**: The UI component that renders transcription segments grouped or annotated by speaker identity
- **Audio_Recorder**: The platform-specific component that captures microphone input to WAV files (currently 16kHz mono via javax.sound.sampled on desktop)
- **Stereo_Audio**: A two-channel WAV recording where each channel may represent a different audio source, required by the Whisper_Server diarize mode
- **Settings_Repository**: The persistence layer that stores application configuration including diarization preferences

## Requirements

### Requirement 1: Enable Diarization in Transcription Requests

**User Story:** As a meeting participant, I want Jeeves to request speaker identification when transcribing audio, so that the transcription segments are attributed to individual speakers.

#### Acceptance Criteria

1. WHEN diarization is enabled in settings and a transcription request is sent, THE Whisper_Client SHALL include a "diarize" form field with the value "true" in the multipart form request to the Whisper_Server
2. WHEN diarization is disabled in settings and a transcription request is sent, THE Whisper_Client SHALL omit the "diarize" form field from the multipart form request
3. THE Whisper_Client SHALL default the diarization setting to enabled for new installations
4. IF the Whisper_Server returns a response that does not include speaker labels despite diarization being requested, THEN THE Whisper_Client SHALL process the transcription result without speaker attribution and without returning an error

### Requirement 2: Parse Speaker Labels from Whisper Server Response

**User Story:** As a meeting participant, I want speaker labels from the transcription response to be captured, so that each segment knows which speaker produced it.

#### Acceptance Criteria

1. WHEN the Whisper_Server JSON response includes a `speaker` field with a non-empty string value in a segment object, THE Diarization_Response_Parser SHALL populate the `speaker` field of the corresponding TranscriptionSegment with that string value, preserving the original label exactly as returned (e.g., "Speaker 0", "Speaker 1")
2. WHEN the Whisper_Server JSON response omits the `speaker` field from a segment object, or provides it as null or an empty string, THE Diarization_Response_Parser SHALL set the `speaker` field to null for that TranscriptionSegment
3. THE Diarization_Response_Parser SHALL produce TranscriptionResult objects where serializing to JSON and deserializing back yields an object with identical field values for recordingId, text, language, durationMs, and all segment fields (startMs, endMs, text, speaker) compared to the original
4. WHEN the Whisper_Server response contains speaker turn markers embedded in segment text (e.g., "[SPEAKER_TURN]") rather than in a structured `speaker` field, THE Diarization_Response_Parser SHALL extract the speaker identifier from the text, remove the marker from the segment text, and populate the `speaker` field accordingly

### Requirement 3: Display Speaker-Attributed Transcription

**User Story:** As a meeting participant reviewing a recording, I want to see who said what in the transcription view, so that I can follow the conversation by speaker.

#### Acceptance Criteria

1. WHEN a TranscriptionSegment has a non-null speaker field, THE Speaker_Display SHALL render the speaker label in a bold font weight above or beside the segment text, separated by a line break or colon delimiter
2. WHEN two or more consecutive TranscriptionSegments share the same speaker label value (case-sensitive string equality), THE Speaker_Display SHALL render the speaker label only once before the first segment of the group and omit it for subsequent segments in that group
3. WHEN a TranscriptionSegment has a null speaker field, THE Speaker_Display SHALL render the segment text without any speaker label prefix or heading
4. THE Speaker_Display SHALL assign a background colour from a fixed palette of at least 6 colours to each unique speaker label, where the colour index is determined by the order of first appearance of each speaker label in the transcription (first speaker gets colour 0, second gets colour 1, etc.)
5. IF the number of unique speaker labels exceeds the palette size, THEN THE Speaker_Display SHALL cycle the colour assignments from the beginning of the palette

### Requirement 4: Diarization Settings Management

**User Story:** As a user, I want to configure diarization preferences in settings, so that I can enable or disable speaker identification based on my needs.

#### Acceptance Criteria

1. THE Settings_Repository SHALL persist a boolean diarization-enabled preference as part of AppSettings, defaulting to false when not previously set
2. WHEN the user saves settings with a changed diarization-enabled value, THE Settings_Repository SHALL persist the updated boolean preference to storage before confirming success to the caller
3. THE Settings_Repository SHALL persist a diarization mode preference accepting only the values "diarize" or "tinydiarize" as part of AppSettings
4. IF the diarization mode preference is not set or contains a value other than "diarize" or "tinydiarize", THEN THE Settings_Repository SHALL default to "tinydiarize"
5. WHEN the user saves settings with a changed diarization mode, THE Settings_Repository SHALL persist the selected mode value independently of the diarization-enabled preference

### Requirement 5: Include Speaker Context in Summarization

**User Story:** As a meeting participant, I want summaries to reference speakers when attributing statements, so that action items and key points are associated with the person who raised them.

#### Acceptance Criteria

1. WHEN at least one TranscriptionSegment has a non-null speaker field and summarization is requested, THE Transcription_Pipeline SHALL format the transcription text by prepending each segment's speaker label followed by a colon and space (e.g., "Speaker 0: segment text") before sending to the summarization service
2. WHEN the transcription does not contain any segments with a non-null speaker field, THE Transcription_Pipeline SHALL send the plain text to the summarization service without speaker formatting
3. WHEN a TranscriptionSegment has a null speaker field within a transcription that contains other speaker-labelled segments, THE Transcription_Pipeline SHALL include that segment's text without a speaker prefix in the formatted transcription
4. WHEN speaker-attributed text is sent to the summarization service, THE Transcription_Pipeline SHALL include an instruction in the summarization prompt directing the model to attribute key points and action items to the speaker who raised them

### Requirement 6: Stereo Audio Recording Support

**User Story:** As a user, I want the option to record in stereo when using diarization, so that the Whisper_Server can leverage channel separation for improved speaker identification.

#### Acceptance Criteria

1. WHEN diarization is enabled and the stereo recording option is active in settings, THE Audio_Recorder SHALL capture audio in 2-channel (stereo) format at 16kHz sample rate with 16-bit signed PCM encoding
2. WHEN diarization is disabled or the stereo recording option is inactive, THE Audio_Recorder SHALL capture audio in 1-channel (mono) format at 16kHz sample rate with 16-bit signed PCM encoding
3. THE Settings_Repository SHALL persist a stereo recording preference as a boolean value within AppSettings, where true indicates stereo and false indicates mono
4. WHEN the stereo recording preference is not set, THE Settings_Repository SHALL default to mono recording (false)
5. IF the stereo recording option is active but the audio input device does not support 2-channel capture, THEN THE Audio_Recorder SHALL fall back to 1-channel (mono) recording and expose an error message indicating the device does not support stereo
6. WHILE a recording session is in progress, THE Audio_Recorder SHALL use the channel configuration (mono or stereo) that was resolved at the start of that session and SHALL NOT change channel mode mid-recording

### Requirement 7: Handle Diarization Errors Gracefully

**User Story:** As a user, I want transcription to succeed even when diarization fails, so that I still get a usable transcript from my meetings.

#### Acceptance Criteria

1. IF the Whisper_Server returns an HTTP error response when the diarize parameter is included in the request but succeeds without it, THEN THE Transcription_Pipeline SHALL retry the request without the diarize parameter and return the transcription result with all speaker fields set to null
2. IF the Whisper_Server responds to a diarize-enabled request with an HTTP 4xx status code containing an error body that references diarization, THEN THE Whisper_Client SHALL retry the transcription request without the diarize parameter within 5 seconds and log a warning indicating diarization was unavailable
3. IF the Whisper_Client sends a request with the diarize parameter and receives an HTTP 400 response indicating an unrecognized parameter, THEN THE Whisper_Client SHALL retry the request without the diarize parameter and log a warning that the server does not support diarization
4. IF the Diarization_Response_Parser encounters a segment where the speaker field is present but contains a non-string value, an empty string, or does not match the expected label format, THEN THE Diarization_Response_Parser SHALL set the speaker field to null for that segment and log a warning identifying the segment index
5. IF the Transcription_Pipeline falls back to transcription without diarization due to any error in criteria 1-4, THEN THE Transcription_Pipeline SHALL include a flag in the TranscriptionResult indicating that diarization was requested but unavailable
