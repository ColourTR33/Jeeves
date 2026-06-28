# Implementation Plan: Meeting Intelligence

## Overview

This plan implements five capabilities on the existing Jeeves pipeline: custom prompt templates per meeting type, AI-recommended follow-up questions, post-recording notes, meeting quality ratings, and cloud LLM provider support. Tasks are ordered to build foundational data models first, then processing logic, then UI, finishing with integration wiring.

## Tasks

- [x] 1. Extend data models and settings
  - [x] 1.1 Add new data classes and extend existing models in Models.kt
    - Add `QualityRating` data class with `pacing: Int`, `questions: Int`, `goalSetting: Int`, `nextSteps: Int`, `overall: Double`
    - Add `CloudLlmConfig` data class with `baseUrl`, `apiKey`, `modelName`, `enabled` fields
    - Add `postRecordingNote: String = ""` field to `Recording`
    - Add `recommendedQuestions: List<String> = emptyList()` and `qualityRating: QualityRating? = null` to `SummaryResult`
    - Add `promptTemplates: Map<MeetingTemplate, String> = emptyMap()` and `cloudLlmConfig: CloudLlmConfig? = null` to `AppSettings`
    - _Requirements: 1.2, 2.1, 3.2, 4.1, 5.2_

  - [x] 1.2 Write unit tests for new data model serialization
    - Test `QualityRating` serialization round-trip
    - Test backward compatibility: deserializing old `Recording` JSON (missing `postRecordingNote`) produces default empty string
    - Test backward compatibility: deserializing old `SummaryResult` JSON (missing new fields) produces defaults
    - Test `CloudLlmConfig` serialization
    - _Requirements: 3.2, 4.1, 5.2_

- [x] 2. Implement PromptTemplateManager and QualityRatingCalculator
  - [x] 2.1 Create PromptTemplateManager in shared/.../ai/
    - Implement `getEffectivePrompt(template: MeetingTemplate)` that loads from settings, falls back to default if blank
    - Implement `getDefaultPrompt(template: MeetingTemplate)` with hardcoded defaults for all 5 template types
    - Define `DEFAULT_PROMPTS` companion object map with template-specific instructions (standup: yesterday/today/blockers, interview: responses/impressions/recommendation, etc.)
    - _Requirements: 1.4, 1.5_

  - [x] 2.2 Create QualityRatingCalculator in shared/.../ai/
    - Implement `calculateOverall(pacing, questions, goalSetting, nextSteps): Double` as arithmetic mean rounded to 1 decimal
    - Implement `shouldRate(transcriptionText: String): Boolean` returning false if fewer than 50 whitespace-separated words
    - _Requirements: 4.3, 4.6_

  - [x] 2.3 Write property test for prompt template effective selection (Property 2)
    - **Property 2: Effective prompt selection with whitespace fallback**
    - For any MeetingTemplate and stored string, verify effective prompt equals stored if non-blank, else default
    - **Validates: Requirements 1.4, 1.5**

  - [x] 2.4 Write property test for overall score calculation (Property 7)
    - **Property 7: Overall score is arithmetic mean**
    - For any four ints in [1,5], verify `calculateOverall` equals `(p+q+g+n)/4.0` rounded to 1 decimal
    - **Validates: Requirements 4.3**

  - [x] 2.5 Write property test for short transcription threshold (Property 8)
    - **Property 8: Short transcription omits quality rating**
    - For any text with fewer than 50 words, verify `shouldRate()` returns false
    - **Validates: Requirements 4.6**

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Extend OllamaClient with new prompt construction and response parsing
  - [x] 4.1 Update SummarizationService interface and OllamaClient.summarize() signature
    - Add `promptTemplate: String = ""` and `meetingTemplate: MeetingTemplate = MeetingTemplate.GENERAL` parameters to `SummarizationService.summarize()`
    - Update `OllamaClient.summarize()` to accept and pass through these new parameters
    - _Requirements: 1.4, 2.1, 4.1_

  - [x] 4.2 Modify buildSummarizationPrompt() to use custom template and append new sections
    - Accept `promptTemplate` parameter; if non-empty, use it as the base instruction instead of the hardcoded prompt
    - Append FOLLOW_UP_QUESTIONS instruction requesting 1-5 questions about conversation gaps
    - Conditionally append RATING instruction (only if `shouldRate()` returns true for the transcription text)
    - For INTERVIEW template, add guidance to focus follow-up questions on candidate assessment gaps
    - _Requirements: 1.4, 2.1, 2.2, 2.5, 4.1, 4.6_

  - [x] 4.3 Extend parseSummaryResponse() to extract new sections
    - Parse `FOLLOW_UP_QUESTIONS:` section into list of strings, capped at 5 items
    - Parse `RATING:` section extracting Pacing, Questions, Goal-Setting, Summary/Next Steps as integers
    - Clamp rating values to [1,5], produce null QualityRating if any criteria missing or unparseable
    - Calculate overall score via `QualityRatingCalculator.calculateOverall()`
    - Populate new `recommendedQuestions` and `qualityRating` fields on `SummaryResult`
    - _Requirements: 2.1, 2.2, 4.1, 4.2, 4.3_

  - [x] 4.4 Write property test for recommended questions parsing (Property 3)
    - **Property 3: Recommended questions parsing and capping**
    - Generate response strings with 0-10 bullet items in FOLLOW_UP_QUESTIONS section, verify parsed list length ≤ 5 and items non-empty
    - **Validates: Requirements 2.1, 2.2**

  - [x] 4.5 Write property test for quality rating parsing (Property 6)
    - **Property 6: Quality rating parsing produces valid scores**
    - Generate response strings with RATING section containing 4 labelled integers, verify parsed QualityRating has all scores in [1,5]
    - **Validates: Requirements 4.1, 4.2**

- [x] 5. Add cloud LLM provider support
  - [x] 5.1 Add Authorization header to callOpenAiCompatibleApi()
    - Accept optional `apiKey: String?` parameter
    - When apiKey is non-null and non-blank, add `Authorization: Bearer $apiKey` header to the request
    - _Requirements: 5.4_

  - [x] 5.2 Implement provider routing in OllamaClient.summarize()
    - Accept `cloudLlmConfig: CloudLlmConfig?` parameter
    - When `cloudLlmConfig?.enabled == true`, route to `callOpenAiCompatibleApi()` using cloud config's baseUrl, modelName, and apiKey
    - When cloud disabled or null, use existing local Ollama flow
    - Handle 401/403 responses with clear error message about invalid/expired API key
    - _Requirements: 5.4, 5.5, 5.6_

  - [x] 5.3 Write unit tests for cloud provider routing
    - Test that enabled cloud config routes to OpenAI-compatible endpoint
    - Test that disabled cloud config falls back to local Ollama
    - Test 401 response produces meaningful error message
    - _Requirements: 5.4, 5.5_

- [x] 6. Update ProcessingQueue to use new summarization features
  - [x] 6.1 Inject PromptTemplateManager into ProcessingQueue and wire into processItem()
    - Resolve effective prompt template for the recording's MeetingTemplate before calling summarize()
    - Pass `promptTemplate`, `meetingTemplate`, and `cloudLlmConfig` to `ollamaClient.summarize()`
    - _Requirements: 1.4, 5.4_

  - [x] 6.2 Write property test for prompt template persistence round-trip (Property 1)
    - **Property 1: Prompt template persistence round-trip**
    - For any valid string and MeetingTemplate, save template to settings, load it back, verify identical
    - **Validates: Requirements 1.2**

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement Post-Recording Notes persistence
  - [x] 8.1 Add updateRecordingNote() to RecordingsRepository and FileRecordingsRepository
    - Add method to update just the `postRecordingNote` field on a Recording
    - Implement in `FileRecordingsRepository` (load recording, update note field, save)
    - _Requirements: 3.2_

  - [x] 8.2 Include Post-Recording Note in export output
    - Update `ExportService` to include `postRecordingNote` in markdown/text export when non-empty
    - Add "Notes" section header and note content to exported output
    - _Requirements: 3.5_

  - [x] 8.3 Write property test for post-recording note persistence round-trip (Property 4)
    - **Property 4: Post-recording note persistence round-trip**
    - For any recording with non-empty note, update then retrieve, verify note identical
    - **Validates: Requirements 3.2**

  - [x] 8.4 Write property test for post-recording note in export (Property 5)
    - **Property 5: Post-recording note included in export**
    - For any recording with non-empty note, verify exported output contains note as substring
    - **Validates: Requirements 3.5**

- [x] 9. Build Settings UI for prompt templates and cloud provider
  - [x] 9.1 Add Prompt Template Editor section to SettingsScreen
    - Add dropdown to select MeetingTemplate type
    - Add multi-line `OutlinedTextField` (minLines = 6) showing current template for selected type
    - Add "Reset to Default" button that restores default prompt via PromptTemplateManager
    - Save modified template to AppSettings.promptTemplates via settingsRepository on save action
    - _Requirements: 1.1, 1.2, 1.3, 1.6_

  - [x] 9.2 Add Cloud LLM Provider section to SettingsScreen
    - Add provider selector toggle between "Local (Ollama)" and "Cloud (OpenAI-compatible)"
    - When Cloud selected, show input fields for API base URL, API key (masked), and model name
    - Persist CloudLlmConfig to AppSettings; do not log or display full API key after entry
    - Switching between providers preserves both configurations
    - _Requirements: 5.1, 5.2, 5.3, 5.6_

- [x] 10. Build Recording Detail UI components
  - [x] 10.1 Add QualityRatingIndicator composable to RecordingsListScreen detail view
    - Render row of 5 star icons (filled/half/empty) based on `qualityRating.overall`
    - Add expandable section showing individual criteria with labels and numeric scores
    - Hide entire component when `qualityRating` is null
    - _Requirements: 4.4, 4.5, 4.6_

  - [x] 10.2 Add FollowUpQuestionsSection composable to RecordingsListScreen detail view
    - Render below summary, before action items in a visually distinct card
    - Display list of recommended questions as bullet points
    - Hide section when `recommendedQuestions` is empty
    - _Requirements: 2.3, 2.4_

  - [x] 10.3 Add PostRecordingNotesEditor composable to RecordingsListScreen detail view
    - Add multi-line `OutlinedTextField` with placeholder text below summary section
    - Auto-save on focus loss via `onFocusChanged` modifier, calling `updateRecordingNote()`
    - Load existing note content when recording is selected
    - No maximum character limit
    - _Requirements: 3.1, 3.3, 3.4_

- [x] 11. Wire AppInitializer and integration
  - [x] 11.1 Create and inject PromptTemplateManager in AppInitializer
    - Instantiate `PromptTemplateManager(settingsRepository)` in `JeevesApp()`
    - Pass to `ProcessingQueue` (or make available through `RecordingManager`)
    - Ensure cloud config is read from settings and passed through the processing pipeline
    - _Requirements: 1.4, 5.4_

- [x] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The project uses Kotlin Multiplatform with kotlinx.serialization — all new data classes need `@Serializable` annotation
- Existing `ignoreUnknownKeys = true` JSON config ensures backward compatibility with old persisted data

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "2.2"] },
    { "id": 2, "tasks": ["2.3", "2.4", "2.5", "4.1"] },
    { "id": 3, "tasks": ["4.2", "4.3", "5.1"] },
    { "id": 4, "tasks": ["4.4", "4.5", "5.2"] },
    { "id": 5, "tasks": ["5.3", "6.1"] },
    { "id": 6, "tasks": ["6.2", "8.1", "8.2"] },
    { "id": 7, "tasks": ["8.3", "8.4", "9.1", "9.2"] },
    { "id": 8, "tasks": ["10.1", "10.2", "10.3"] },
    { "id": 9, "tasks": ["11.1"] }
  ]
}
```
