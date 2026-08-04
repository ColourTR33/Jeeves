# Jeeves — Feature Backlog

Prioritised using MoSCoW method. Items within each category are ordered by priority (top = most important).

---

## Must Have

### 1. ~~Stabilise Whisper Server Connectivity~~ ✅ DONE
The whisper server has been hardened with:
- BrokenPipeMiddleware to catch WinError 64 and client disconnections gracefully
- `timeout_keep_alive=120` to prevent premature connection drops
- Concurrency limiting (max 3 connections, 1 active transcription at a time)
- Memory management with idle model unloading and request limits
- Proper temp file cleanup in finally blocks

---

### 2. ~~Fix Summarization Failures on Long Transcripts~~ ✅ DONE
Map-reduce chunked summarization is implemented in OllamaClient:
- Transcripts >2000 words are split into ~1500-word chunks
- Each chunk is summarized independently (Phase 1: Map)
- If combined summaries still exceed 2000 words, a reduce pass condenses them
- Final synthesis pass produces the structured output
- Empty responses are logged and handled gracefully
- Explicit error logging when Ollama returns non-200 or empty responses

---

### 3. ~~Settings Corruption — streamingTranscriptionEndpoint Reappearing~~ ✅ DONE
The `streamingTranscriptionEndpoint` field with port 8179 keeps reappearing in settings.json despite being removed. Nothing runs on 8179.

**Fixed with:**
- Migration in FileSettingsRepository that strips any endpoint with port 8179
- Also migrates transcriptionEndpoint if it has 8179 (fixes to 8178)
- Migration runs on both load and save
- Unit tests verify 8179 is never persisted

---

### 4. ~~iOS Companion App~~ ✅ LARGELY COMPLETE
The iOS app (`iosApp/`) is functional with:
- Audio recording to WAV at 16kHz mono (optimal for Whisper)
- Real-time audio level meter
- Live streaming transcription during recording
- Async processing queue (transcribe → summarize)
- Local Whisper server integration (configurable IP)
- Groq cloud transcription fallback
- Ollama summarization integration
- Recordings list with search
- Markdown export
- Screenshot capture during meetings
- Meeting templates (General, Standup, 1:1, Interview, etc.)
- Bookmark timestamps during recording
- Speaker name assignment
- Settings view for server configuration

**Remaining work for Phase 2:**
- Sync recordings/transcriptions with desktop via CouchDB (currently local-only)
- View recordings synced from desktop
- Offline recording with sync-when-available

---

## Should Have

### 5. ~~Platform Installers & Distribution~~ ✅ DONE

Build scripts created in `packaging/` directory:
- `build-windows-installer.bat` - Creates MSI/EXE via jpackage
- `build-macos-installer.sh` - Creates DMG with optional code signing
- `packaging/README.md` - Full documentation

Auto-update mechanism implemented:
- UpdateChecker service polls GitHub releases API on startup
- UpdateBanner UI component shows when update available
- One-click download to release page

---

### 6. ~~Whisper Server as a Windows Service~~ ✅ DONE
Scripts in `whisper-server/`:
- `install-service.bat` - Installs as Windows Service via NSSM
- `uninstall-service.bat` - Clean removal
- Auto-start on boot, auto-restart on failure
- Log rotation at 10MB

---

### 7. ~~Search Across Recordings~~ ✅ DONE
SQLite FTS5 full-text search implemented in SearchService:
- Indexes titles, descriptions, transcriptions, summaries, key points, action items, tags
- BM25 ranking for relevance-sorted results
- Porter stemming + unicode tokenization
- Snippet highlighting with match context
- Incremental index updates

---

### 8. ~~Export to Markdown / Obsidian~~ ✅ DONE
ObsidianExportService with:
- YAML frontmatter (date, type, template, tags, duration)
- Summary, key points, action items (as checkboxes)
- Full transcription with timestamps and speakers
- "Save to Obsidian" button in recording detail view
- Configurable vault path in settings
- Export feedback (Exporting... / Saved! / Error)

---

### 9. ~~Improved Speaker Labels~~ ✅ DONE
SpeakerNameService with:
- Persists speaker label → human name mappings in `~/Jeeves/speaker-names.json`
- Names persist across all recordings
- Inline editing: click speaker name to rename
- Color-coded speaker groups in transcription view
- Edit icon hint for discoverability

---

### 10. ~~Calendar Integration (Outlook/Teams)~~ ✅ DONE
CalendarService with:
- Windows: Queries Outlook via PowerShell COM object
- macOS: Queries Calendar.app via AppleScript
- Cross-platform: ICS file parsing (configurable path in settings)
- Auto-fills meeting title when recording starts
- Detects ongoing meetings and upcoming meetings (within 30 min)

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
Removed. The standalone desktop app with Markdown export (item #8) covers the Obsidian use case better than an in-editor plugin.

### macOS Support
The desktop app is Kotlin Multiplatform and should work on macOS, but needs testing and platform-specific polish:
- BlackHole audio routing for system audio capture
- launchd services for whisper-server auto-start
- Apple Reminders integration
- Proper .app bundle with code signing

---

## Technical Debt

- **ScreenshotCapture**: Partially uses macOS commands on Windows (fixed to use Java Robot API but needs testing)
- **CallDetector**: Uses `tasklist` on Windows, `/bin/ps` on macOS — brittle process name matching
- **WhisperX server**: Removed (whisper-server replaces it)
- **Gradle build**: Extremely aggressive caching causes stale jars — consider switching to a simpler build (shadow jar plugin directly)
- **Settings migration**: No versioned migration system — ad-hoc field additions risk corruption
- **Test coverage**: Property tests defined in specs but very few unit tests actually exist
- **Error handling**: Many coroutines swallow exceptions silently — needs structured error propagation
