# Design Document: Meeting Intelligence

## Overview

Meeting Intelligence extends Jeeves with four capabilities that transform recorded meetings into actionable intelligence: custom summarisation prompts per meeting type, AI-recommended follow-up questions, post-recording notes, and AI-generated quality ratings. These features build on the existing `OllamaClient` → `ProcessingQueue` → `FileRecordingsRepository` pipeline, adding new data models, prompt construction logic, response parsing, and UI components while preserving the current architecture patterns.

The design prioritises minimal disruption to the existing pipeline. The core approach is:
1. Extend `AppSettings` with prompt template storage (persisted as JSON alongside existing settings)
2. Expand `SummaryResult` with new fields for follow-up questions, notes, and ratings
3. Modify `OllamaClient.buildSummarizationPrompt()` to use custom templates and request additional structured output
4. Add new UI sections to `RecordingsListScreen` detail view and `SettingsScreen`

## Architecture

```mermaid
graph TD
    subgraph "Settings Layer"
        A[SettingsScreen - Prompt Editor] --> B[FileSettingsRepository]
        B --> C[AppSettings + PromptTemplates]
    end

    subgraph "Processing Pipeline"
        D[ProcessingQueue] --> E[OllamaClient]
        E --> F[Prompt Builder]
        F --> G[Custom Template per MeetingTemplate]
        F --> H[Follow-Up Questions Instruction]
        F --> I[Quality Rating Instruction]
        E --> J[Response Parser]
        J --> K[SummaryResult + QualityRating + FollowUpQuestions]
    end

    subgraph "Data Layer"
        K --> L[FileRecordingsRepository]
        L --> M[summary_{id}.json]
        N[Recording + postRecordingNote] --> L
    end

    subgraph "UI Layer"
        L --> O[RecordingsListScreen Detail View]
        O --> P[Summary Section]
        O --> Q[Quality Rating Indicator]
        O --> R[Follow-Up Questions Section]
        O --> S[Post-Recording Notes Editor]
    end

    C --> F
```

**Key architectural decisions:**
- Prompt templates are stored in `AppSettings` rather than a separate file, keeping the single-file settings pattern consistent
- Quality rating and follow-up questions are generated in a single LLM call alongside the summary to avoid multiple API round-trips
- `postRecordingNote` is added to the `Recording` data class (persisted with recording metadata) since it's user-authored content tied to the recording, not AI-generated output
- The rating threshold (50 words) is checked before constructing the prompt, so short transcriptions skip the rating instruction entirely

## Components and Interfaces

### PromptTemplateManager

Responsible for resolving the effective prompt for a given `MeetingTemplate`. Encapsulates fallback logic.

```kotlin
// shared/.../ai/PromptTemplateManager.kt
class PromptTemplateManager(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Returns the effective prompt for the given template type.
     * Falls back to default if the stored prompt is blank.
     */
    suspend fun getEffectivePrompt(template: MeetingTemplate): String {
        val settings = settingsRepository.getSettings()
        val stored = settings.promptTemplates[template]
        return if (stored.isNullOrBlank()) getDefaultPrompt(template) else stored
    }

    fun getDefaultPrompt(template: MeetingTemplate): String {
        return DEFAULT_PROMPTS[template] ?: DEFAULT_PROMPTS[MeetingTemplate.GENERAL]!!
    }

    companion object {
        val DEFAULT_PROMPTS: Map<MeetingTemplate, String> = mapOf(
            MeetingTemplate.GENERAL to """...""",     // existing general prompt
            MeetingTemplate.STANDUP to """...""",     // yesterday/today/blockers
            MeetingTemplate.ONE_ON_ONE to """...""",  // topics/decisions/feedback
            MeetingTemplate.INTERVIEW to """...""",   // responses/impressions/recommendation
            MeetingTemplate.BRAINSTORM to """..."""   // ideas/themes/next steps
        )
    }
}
```

### Modified OllamaClient

The `SummarizationService` interface gains a new parameter for the custom prompt template. The `OllamaClient` is updated to:
1. Accept and use the custom prompt template instead of the hardcoded prompt
2. Append instructions for follow-up questions and quality rating
3. Parse the extended response sections

```kotlin
interface SummarizationService {
    suspend fun summarize(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String = "",
        attachmentCount: Int = 0,
        promptTemplate: String = "",       // NEW: custom prompt template
        meetingTemplate: MeetingTemplate = MeetingTemplate.GENERAL  // NEW: for template-specific instructions
    ): SummaryResult
}
```

### QualityRatingCalculator

Pure function encapsulating the rating calculation logic, testable independently.

```kotlin
// shared/.../ai/QualityRatingCalculator.kt
object QualityRatingCalculator {
    /**
     * Calculate overall score as arithmetic mean rounded to 1 decimal place.
     */
    fun calculateOverall(pacing: Int, questions: Int, goalSetting: Int, nextSteps: Int): Double {
        val mean = (pacing + questions + goalSetting + nextSteps) / 4.0
        return (mean * 10).roundToInt() / 10.0
    }

    /**
     * Determine whether quality rating should be generated.
     * Returns false if transcript has fewer than 50 words.
     */
    fun shouldRate(transcriptionText: String): Boolean {
        return transcriptionText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size >= 50
    }
}
```

### Updated ProcessingQueue

The queue's `processItem` method is updated to:
1. Resolve the effective prompt template before calling `summarize()`
2. Pass the template and meeting type to `OllamaClient`
3. No structural changes to the queue itself — it remains a sequential processor

### UI Components

**PromptTemplateEditor** (in SettingsScreen):
- Dropdown to select `MeetingTemplate` type
- Multi-line `OutlinedTextField` (minLines = 6) showing the current template
- "Reset to Default" button per template
- Save persists to `AppSettings.promptTemplates`

**QualityRatingIndicator** (in RecordingsListScreen detail):
- Row of 5 star icons (filled/half/empty based on overall score)
- Expandable section showing individual criteria with labels and scores
- Hidden when `qualityRating` is null

**FollowUpQuestionsSection** (in RecordingsListScreen detail):
- Rendered below summary, before action items
- List of questions in a visually distinct card
- Hidden when list is empty

**PostRecordingNotesEditor** (in RecordingsListScreen detail):
- Multi-line `OutlinedTextField` with placeholder text
- Auto-saves on focus loss via `onFocusChanged` modifier
- Persists to `Recording.postRecordingNote`

## Data Models

### Extended AppSettings

```kotlin
@Serializable
data class AppSettings(
    // ... existing fields ...
    val promptTemplates: Map<MeetingTemplate, String> = emptyMap(),
    val cloudLlmConfig: CloudLlmConfig? = null  // null = not configured
)
```

The `promptTemplates` map stores user-customised prompts keyed by template type. An empty map means all templates use defaults. Missing entries for a specific type also fall back to default.

### CloudLlmConfig Data Class

```kotlin
@Serializable
data class CloudLlmConfig(
    val baseUrl: String,          // e.g., "https://api.openai.com" or "https://api.anthropic.com"
    val apiKey: String,           // Bearer token — stored in settings, masked in UI
    val modelName: String,        // e.g., "gpt-4o", "claude-3-sonnet"
    val enabled: Boolean = false  // When true, cloud is used instead of local Ollama
)
```

### LLM Provider Selection

The `Summarisation_Engine` checks `settings.cloudLlmConfig?.enabled == true` at summarisation time:
- If cloud enabled: routes to `callOpenAiCompatibleApi()` with `Authorization: Bearer {apiKey}` header
- If cloud disabled or not configured: routes to `callOllamaApi()` (existing local behaviour)

The existing `callOpenAiCompatibleApi()` method in `OllamaClient` already uses the `/v1/chat/completions` format — it just needs the auth header added. This makes cloud support a configuration change rather than a new code path.

### Extended Recording

```kotlin
@Serializable
data class Recording(
    // ... existing fields ...
    val postRecordingNote: String = ""  // User-authored annotation added after recording
)
```

Default empty string ensures backward compatibility with existing serialised recordings (via `ignoreUnknownKeys = true` and default values).

### QualityRating Data Class

```kotlin
@Serializable
data class QualityRating(
    val pacing: Int,          // 1-5
    val questions: Int,       // 1-5
    val goalSetting: Int,     // 1-5
    val nextSteps: Int,       // 1-5
    val overall: Double       // Arithmetic mean, 1 decimal place
)
```

### Extended SummaryResult

```kotlin
@Serializable
data class SummaryResult(
    val recordingId: String,
    val summary: String,
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val modelUsed: String = "",
    // NEW fields:
    val recommendedQuestions: List<String> = emptyList(),  // AI follow-up questions (max 5)
    val qualityRating: QualityRating? = null               // null when omitted (< 50 words)
)
```

Note: The existing `questions` field stores questions raised _during_ the meeting (extracted from transcript). The new `recommendedQuestions` field stores AI-generated follow-up questions about gaps _not covered_ in the meeting.

### Prompt Response Format Extension

The LLM response format is extended with two new sections appended after TAGS:

```
FOLLOW_UP_QUESTIONS:
- [question 1]
- [question 2]
...

RATING:
Pacing: [1-5]
Questions: [1-5]
Goal-Setting: [1-5]
Summary/Next Steps: [1-5]
```

The parser handles these sections being absent (for short transcriptions or when the LLM doesn't comply).

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Prompt template persistence round-trip

*For any* valid prompt string and any `MeetingTemplate` type, saving the prompt template to settings then loading it back should return the identical string.

**Validates: Requirements 1.2**

### Property 2: Effective prompt selection with whitespace fallback

*For any* `MeetingTemplate` type and any stored prompt string, the effective prompt returned by `PromptTemplateManager` should equal the stored prompt if it contains at least one non-whitespace character, otherwise it should equal the default prompt for that template type.

**Validates: Requirements 1.4, 1.5**

### Property 3: Recommended questions parsing and capping

*For any* well-formed LLM response text containing a FOLLOW_UP_QUESTIONS section with N bullet-point items, the parsed `recommendedQuestions` list should contain exactly `min(N, 5)` items, and each item should be a non-empty string.

**Validates: Requirements 2.1, 2.2**

### Property 4: Post-recording note persistence round-trip

*For any* recording with a non-empty `postRecordingNote` string, updating the recording then retrieving it should return a recording whose `postRecordingNote` is identical to the original.

**Validates: Requirements 3.2**

### Property 5: Post-recording note included in export

*For any* recording with a non-empty `postRecordingNote`, the exported markdown or text output should contain the note content as a substring.

**Validates: Requirements 3.5**

### Property 6: Quality rating parsing produces valid scores

*For any* well-formed LLM response text containing a RATING section with four labelled integer scores, the parsed `QualityRating` should have exactly four criteria scores each in the range [1, 5] inclusive.

**Validates: Requirements 4.1, 4.2**

### Property 7: Overall score is arithmetic mean

*For any* four integers p, q, g, n each in [1, 5], `QualityRatingCalculator.calculateOverall(p, q, g, n)` should equal the arithmetic mean of (p + q + g + n) / 4.0 rounded to one decimal place.

**Validates: Requirements 4.3**

### Property 8: Short transcription omits quality rating

*For any* transcription text containing fewer than 50 whitespace-separated words, `QualityRatingCalculator.shouldRate(text)` should return false, and the resulting `SummaryResult.qualityRating` should be null.

**Validates: Requirements 4.6**

## Error Handling

| Scenario | Handling |
|----------|----------|
| LLM response missing FOLLOW_UP_QUESTIONS section | `recommendedQuestions` defaults to empty list — no error surfaced to user |
| LLM response missing RATING section | `qualityRating` defaults to null — rating indicator hidden |
| Rating scores outside 1-5 range | Clamp to [1, 5] during parsing; log warning |
| LLM returns more than 5 follow-up questions | Truncate to first 5 |
| Corrupted/unparseable rating line | Skip that criteria, produce null rating if any criteria missing |
| Settings file migration (old format without `promptTemplates`) | `ignoreUnknownKeys = true` + default empty map — seamless backward compat |
| Recording file migration (old format without `postRecordingNote`) | Default `""` value — no migration needed |
| Empty/whitespace prompt template | `PromptTemplateManager` falls back to default prompt |
| OllamaClient network failure | Existing error handling in `ProcessingQueue` marks item as FAILED — unchanged |

## Testing Strategy

### Property-Based Tests (using kotlin-test + kotest-property)

Each correctness property is implemented as a property-based test with minimum 100 iterations:

1. **Prompt template round-trip**: Generate arbitrary strings, save/load via in-memory `SettingsRepository` stub
2. **Effective prompt selection**: Generate `(MeetingTemplate, String?)` pairs, verify fallback logic
3. **Questions parsing + capping**: Generate response strings with 0-10 bullet items, verify parse output length ≤ 5
4. **Note persistence round-trip**: Generate arbitrary strings, save/load via in-memory `RecordingsRepository` stub
5. **Note in export**: Generate recordings with random notes, verify export contains note substring
6. **Rating parsing**: Generate response strings with 4 integer scores in [1-5], verify parsed rating
7. **Overall score calculation**: Generate 4 ints in [1-5], verify arithmetic mean formula
8. **Short transcription threshold**: Generate strings with 0-49 words, verify `shouldRate()` returns false

**Library**: [Kotest Property](https://kotest.io/docs/proptest/property-based-testing.html) — standard Kotlin PBT library with `Arb` generators.

**Configuration**: Each test runs with `checkAll(100)` minimum iterations.

**Tag format**: `// Feature: meeting-intelligence, Property {N}: {title}`

### Unit Tests (example-based)

- Prompt editor displays all 5 template types (1.1)
- Reset restores known default for each template (1.3)
- Multi-line field has minLines = 6 (1.6)
- INTERVIEW template includes interview-specific question guidance in prompt (2.5)
- Detail view renders follow-up questions section when present (2.4)
- Detail view hides follow-up questions section when empty (2.3 edge case)
- Post-recording note text area renders in detail view (3.1)
- Note field supports multi-line with no max (3.3)
- Detail view loads existing note on selection (3.4)
- Star rating renders correctly for scores 1.0, 2.5, 3.7, 5.0 (4.4)
- Expanded rating shows all 4 criteria labels (4.5)

### Integration Tests

- Full pipeline test: recording → transcription → summary with custom prompt → verify all new fields populated
- Settings save/load cycle with prompt templates through `FileSettingsRepository`
- Export service produces markdown/text containing all new sections (rating, follow-up questions, notes)
