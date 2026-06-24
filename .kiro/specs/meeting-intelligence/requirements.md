# Requirements Document

## Introduction

Meeting Intelligence enhances the Jeeves meeting recorder with advanced AI-powered analysis capabilities. The feature adds customisable summarisation prompts per meeting template, AI-recommended follow-up questions for missed conversation gaps, post-recording annotations for off-mic context, and an AI-generated meeting quality rating with visual indicator. Together these capabilities transform Jeeves from a simple recorder into an intelligent meeting companion that helps users improve meeting effectiveness over time.

## Glossary

- **Prompt_Template**: A user-editable text template containing instructions sent to the LLM to guide summarisation for a specific MeetingTemplate type
- **Meeting_Template**: One of the five meeting types (GENERAL, STANDUP, ONE_ON_ONE, INTERVIEW, BRAINSTORM) that determines the default summarisation style
- **Prompt_Editor**: The UI component within Settings that allows users to view and modify Prompt_Templates
- **Summarisation_Engine**: The component (OllamaClient) responsible for sending transcription text and prompts to the configured LLM endpoint and parsing responses
- **Recommended_Questions**: AI-generated follow-up questions identifying gaps or omissions in the meeting conversation
- **Post_Recording_Note**: A user-authored text annotation attached to a completed recording's summary, capturing context not present in the audio
- **Quality_Rating**: An AI-generated assessment of meeting effectiveness across defined criteria, expressed as a score and visual indicator
- **Rating_Criteria**: The set of dimensions evaluated in a Quality_Rating (pacing, questions, goal-setting, summary/next-steps)
- **Rating_Indicator**: A visual component displaying the Quality_Rating alongside the meeting summary

## Requirements

### Requirement 1: Custom Prompt Templates Per Meeting Type

**User Story:** As a user, I want to customise the summarisation prompt for each meeting template, so that I can tailor AI output to my specific workflow and preferences.

#### Acceptance Criteria

1. THE Prompt_Editor SHALL display the current Prompt_Template for each of the five Meeting_Template types (GENERAL, STANDUP, ONE_ON_ONE, INTERVIEW, BRAINSTORM)
2. WHEN a user modifies a Prompt_Template and saves, THE Settings_Repository SHALL persist the updated Prompt_Template
3. WHEN a user resets a Prompt_Template, THE Prompt_Editor SHALL restore the default Prompt_Template for that Meeting_Template type
4. WHEN a recording is summarised, THE Summarisation_Engine SHALL use the Prompt_Template associated with the recording's Meeting_Template
5. IF a Prompt_Template is empty or contains only whitespace, THEN THE Summarisation_Engine SHALL fall back to the default Prompt_Template for that Meeting_Template type
6. THE Prompt_Editor SHALL provide a multi-line text input field with a minimum height of 6 lines for editing each Prompt_Template

### Requirement 2: AI-Recommended Follow-Up Questions

**User Story:** As a user, I want the AI to recommend questions I may have missed during the meeting, so that I can identify conversation gaps and follow up appropriately.

#### Acceptance Criteria

1. WHEN a meeting transcription is summarised, THE Summarisation_Engine SHALL generate a list of Recommended_Questions identifying topics or gaps the user may have omitted
2. THE Summarisation_Engine SHALL provide between 1 and 5 Recommended_Questions per meeting summary
3. WHEN no meaningful gaps are identified in the conversation, THE Summarisation_Engine SHALL return an empty Recommended_Questions list rather than generating filler questions
4. THE Recording_Detail_View SHALL display Recommended_Questions in a distinct section below the existing summary content
5. WHEN a recording's Meeting_Template is INTERVIEW, THE Summarisation_Engine SHALL focus Recommended_Questions on candidate assessment gaps and unexplored competency areas

### Requirement 3: Post-Recording Notes

**User Story:** As a user, I want to add notes and annotations to a meeting summary after recording has finished, so that I can capture context not detectable from audio alone (body language, off-mic decisions, environmental observations).

#### Acceptance Criteria

1. THE Recording_Detail_View SHALL display an editable text area for Post_Recording_Notes below the summary section
2. WHEN a user enters text into the Post_Recording_Note field and the field loses focus, THE Recordings_Repository SHALL persist the Post_Recording_Note
3. THE Post_Recording_Note field SHALL support multi-line text input with no maximum character limit
4. WHEN a recording has an existing Post_Recording_Note, THE Recording_Detail_View SHALL display the saved note content upon selection
5. WHEN a recording is exported, THE Export_Service SHALL include the Post_Recording_Note in the exported output

### Requirement 4: Meeting Quality Rating

**User Story:** As a user, I want the AI to rate the quality of my meeting based on defined criteria, so that I can track and improve my meeting effectiveness.

#### Acceptance Criteria

1. WHEN a meeting transcription is summarised, THE Summarisation_Engine SHALL generate a Quality_Rating evaluating the meeting across four Rating_Criteria: pacing, appropriate questions, goal-setting, and summary with next steps
2. THE Quality_Rating SHALL express each Rating_Criteria score as an integer from 1 to 5
3. THE Quality_Rating SHALL include an overall score calculated as the arithmetic mean of the four individual Rating_Criteria scores, rounded to one decimal place
4. THE Rating_Indicator SHALL display the overall score visually alongside the meeting summary using a 5-star representation
5. WHEN a user hovers over or expands the Rating_Indicator, THE Recording_Detail_View SHALL display the individual Rating_Criteria scores with labels
6. IF the transcription contains fewer than 50 words, THEN THE Summarisation_Engine SHALL omit the Quality_Rating and the Rating_Indicator SHALL not be displayed

### Requirement 5: Cloud LLM Provider Support

**User Story:** As a user, I want to optionally use a cloud-based LLM (such as OpenAI GPT-4, Anthropic Claude, or Groq) for summarisation instead of local Ollama, so that I can leverage more powerful models when privacy constraints allow.

#### Acceptance Criteria

1. THE Settings_Screen SHALL provide a provider selector allowing the user to choose between Local (Ollama) and Cloud (OpenAI-compatible API) for the summarisation endpoint
2. WHEN Cloud provider is selected, THE Settings_Screen SHALL display input fields for API base URL, API key, and model name
3. THE Settings_Repository SHALL persist the API key securely and SHALL NOT log or display it in full after initial entry
4. WHEN a cloud endpoint is configured, THE Summarisation_Engine SHALL send requests using the OpenAI-compatible /v1/chat/completions format with an Authorization: Bearer header
5. IF the cloud API returns an authentication error (401/403), THEN THE Summarisation_Engine SHALL surface a clear error message indicating the API key is invalid or expired
6. THE user SHALL be able to switch between Local and Cloud providers without losing the configuration for either
