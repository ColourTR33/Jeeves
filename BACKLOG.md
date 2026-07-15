# Jeeves — Feature Backlog

Prioritised using MoSCoW method. Items within each category are ordered by priority (top = most important).

---

## Must Have

### 1. Stabilise Whisper Server Connectivity
The whisper server crashes intermittently (WinError 64 — client disconnection during long transcriptions). The app retries, but the root cause needs fixing.

**Approach:**
- Add `--timeout-keep-alive 120` to uvicorn config
- Wrap transcription in a try/except that catches broken-pipe errors gracefully
- Add server-side request timeout (10 min max per file)
- Consider chunking large files server-side before transcription

**Impact:** Without this, recordings pile up untranscribed and the user has to manually restart the server.

---

### 2. Fix Summarization Failures on Long Transcripts
Summarization silently fails when transcription text exceeds Ollama's context window (~8K tokens for qwen3:8b). No error is shown — the summary just never appears.

**Approach:**
- Detect transcript length before sending to Ollama
- If > 6000 words, split into chunks, summarise each chunk, then summarise the summaries (map-reduce)
- Show explicit error in Logs if Ollama returns a non-200 or empty response
- Consider switching to a model with larger context (qwen3:14b, or use /api/generate with num_ctx override)

**Impact:** Multi-hour meetings produce no summary at all. This is the most-requested fix.

---

### 3. Settings Corruption — streamingTranscriptionEndpoint Reappearing
The `streamingTranscriptionEndpoint` field with port 8179 keeps reappearing in settings.json despite being removed. Nothing runs on 8179.

**Approach:**
- Audit all code paths that write settings (SettingsManager, AppInitializer, migration logic)
- Add a migration step that strips any endpoint with port 8179
- Add a unit test that serializes/deserializes settings and asserts no 8179 endpoint

**Impact:** Low functional impact but causes confusion and connection errors on streaming startup.

---

### 4. iOS Companion App
The iOS app exists in the project structure but is not functional. It should connect to the desktop services over LAN and provide mobile recording with transcription.

**Approach:**
- Phase 1: Basic recording + upload to desktop whisper server for transcription
- Phase 2: View recordings and summaries synced from desktop
- Phase 3: Offline recording with sync-when-available

**Impact:** Core requirement from original project brief. Currently desktop-only.

---

## Should Have

### 5. Installer / Auto-Update
Currently deployed as a manual jar copy with a .bat launcher. Users must rebuild and redeploy manually.

**Approach:**
- Create a proper Windows MSI installer (jpackage or WiX)
- Bundle JRE so Java doesn't need to be pre-installed
- Add version check on startup with "Update available" notification
- Alternatively: use a self-updating launcher that pulls latest jar from a local/network path

---

### 6. Whisper Server as a Windows Service
The whisper server runs in a console window that can be accidentally closed. Should run as a background service.

**Approach:**
- Use NSSM (Non-Sucking Service Manager) to wrap whisper-server.bat as a Windows service
- Or use pythonw.exe + a proper Windows service wrapper
- Auto-start on boot, restart on failure
- Health endpoint monitoring from the main app

---

### 7. Search Across Recordings
Full-text search across all transcriptions and summaries. Currently no way to find a past meeting except scrolling the list.

**Approach:**
- SQLite FTS5 virtual table indexed on transcription text + summary text + title + tags
- Search bar at the top of Recordings list
- Highlight matching segments with timestamps (click to jump)

---

### 8. Export to Markdown / Obsidian
Export completed recordings (summary + transcription + metadata) as structured Markdown files compatible with Obsidian.

**Approach:**
- "Export" button on recording detail view
- Template: YAML frontmatter (date, attendees, project, tags) + summary + action items + full transcript
- Configurable output folder (default: Obsidian vault path from settings)
- Batch export for multiple recordings

---

### 9. Improved Speaker Labels
Pyannote returns SPEAKER_00, SPEAKER_01 etc. These should be user-assignable names that persist across meetings.

**Approach:**
- After diarization, show a speaker assignment UI (audio snippets + "Who is this?")
- Build a speaker voiceprint database (embeddings from pyannote)
- Auto-match speakers across recordings using cosine similarity
- Allow manual override/correction

---

### 10. Calendar Integration (Outlook/Teams)
Auto-populate meeting title and attendees from calendar events when a recording starts during a scheduled meeting.

**Approach:**
- Microsoft Graph API (requires Azure AD app registration)
- Match current time against calendar events
- Pre-fill title, description, attendees from calendar event
- Or simpler: read .ics files from a synced calendar folder

---

## Could Have

### 11. Liquid Glass UI
Customisable frosted glass / translucent UI with configurable opacity.

**Approach:**
- Phase 1: Semi-transparent surfaces with opacity slider in settings
- Phase 2: Intra-app backdrop blur (panels blur content behind them)
- Phase 3: Full window transparency with custom frame + OS-level backdrop (DwmExtendFrameIntoClientArea)

**Settings:** opacity (0.05–1.0), blur radius (0–30px), tint colour

**Constraints:** GPU-intensive blur on Intel Iris Xe may need a performance mode; full window transparency requires undecorated window with custom title bar.

---

### 12. Groq Cloud Transcription Fallback
When local whisper server is unavailable or for higher accuracy, fall back to Groq's cloud whisper-large-v3 API.

**Approach:**
- Settings already have `TranscriptionProvider.GROQ_CLOUD` and `groqApiKey` fields
- Implement the actual Groq API client (OpenAI-compatible endpoint)
- Add UI toggle: Local → Cloud → Auto (try local first, fall back to cloud)
- Rate limit awareness (Groq has free tier limits)

**Note:** Partially scaffolded in Models.kt. Needs implementation in WhisperClient.

---

### 13. Cloud LLM Summarization (OpenAI/Anthropic)
Use cloud LLMs for summarization when local Ollama is insufficient (long transcripts, higher quality).

**Approach:**
- Settings already have `CloudLlmConfig` with baseUrl, apiKey, modelName
- Implement OpenAI-compatible chat completions client
- UI: toggle between Local Ollama and Cloud LLM
- Streaming response display in UI

**Note:** Scaffolded in Models.kt. Needs implementation.

---

### 14. Multi-Device Sync
Sync recordings, transcriptions, and time tracking data between multiple devices (desktop + mobile).

**Approach:**
- Settings have sync fields (remoteUrl, username, password, enabled)
- SyncEngine exists in codebase but is not fully functional
- Use CouchDB/PouchDB protocol, or simple REST API with conflict resolution
- Audio sync policy: ALWAYS / WIFI_ONLY / ON_DEMAND

---

### 15. Recording Templates & Quick Actions
Pre-configured recording profiles (e.g., "Teams Standup" auto-selects project, template, disables live transcription).

**Approach:**
- Named presets stored in settings
- Quick-start buttons on recording screen
- Template includes: project, meeting template, live transcription toggle, attendees list

---

### 16. Action Item Tracking
Extract action items from summaries into a dedicated tracker with assignees, due dates, and completion status.

**Approach:**
- Parse action items from SummaryResult.actionItems
- Dedicated "Actions" tab with kanban or list view
- Link back to source recording
- Export to task managers (Todoist, Microsoft To Do)

---

### 17. Meeting Analytics Dashboard
Visualise meeting patterns: hours in meetings per week, most common attendees, average meeting length, talk-time ratio.

**Approach:**
- Aggregate data from recordings + time entries
- Charts: meetings/week trend, hours by project, average duration
- Insights: "You spent 12h in meetings this week, 3h more than target"

---

### 18. Keyboard Shortcuts Throughout App
Global and in-app keyboard shortcuts for power users.

**Approach:**
- Tab switching: Ctrl+1/2/3/4
- Recording: Ctrl+Shift+R (already done), Ctrl+Shift+P (pause)
- Time tracking: Ctrl+T (start/stop timer)
- Search: Ctrl+F
- Settings: Ctrl+,

---

## Won't Do (Parked)

### Obsidian Plugin
The existing obsidian-plugin directory is unmaintained. Recording from within Obsidian adds complexity without clear benefit given the standalone desktop app. Markdown export (item #8) covers the Obsidian use case better.

### macOS-Specific Features
BlackHole audio routing, launchd services, Apple Reminders integration — parked until there's a macOS user. The app is Windows-first.

---

## Technical Debt

- **ScreenshotCapture**: Partially uses macOS commands on Windows (fixed to use Java Robot API but needs testing)
- **CallDetector**: Uses `tasklist` on Windows, `/bin/ps` on macOS — brittle process name matching
- **WhisperX server**: Deprecated, should be removed from repo (whisper-server replaces it)
- **Gradle build**: Extremely aggressive caching causes stale jars — consider switching to a simpler build (shadow jar plugin directly)
- **Settings migration**: No versioned migration system — ad-hoc field additions risk corruption
- **Test coverage**: Property tests defined in specs but very few unit tests actually exist
- **Error handling**: Many coroutines swallow exceptions silently — needs structured error propagation
