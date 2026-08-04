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

### 3. Settings Corruption — streamingTranscriptionEndpoint Reappearing
The `streamingTranscriptionEndpoint` field with port 8179 keeps reappearing in settings.json despite being removed. Nothing runs on 8179.

**Approach:**
- Audit all code paths that write settings (SettingsManager, AppInitializer, migration logic)
- Add a migration step that strips any endpoint with port 8179
- Add a unit test that serializes/deserializes settings and asserts no 8179 endpoint

**Impact:** Low functional impact but causes confusion and connection errors on streaming startup.

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

### 5. Platform Installers & Distribution

Currently the desktop app is deployed as a manual jar copy. Each platform needs a proper installer for end-user distribution.

#### 5a. Windows Installer (MSI/EXE)
**Approach:**
- Use `jpackage` (bundled with JDK 17+) to create Windows MSI/EXE installer
- Bundle JRE so Java doesn't need to be pre-installed
- Include whisper-server Python environment or document prerequisite
- Register as Windows Service option during install
- Start menu shortcuts, uninstaller, file associations (.jeeves files)
- Code signing certificate for SmartScreen approval (optional but recommended)

**Command:** `jpackage --type msi --input build/jars --main-jar Jeeves.jar --name Jeeves --app-version 1.2.0 --win-menu --win-shortcut`

#### 5b. macOS Installer (DMG/PKG)
**Approach:**
- Use `jpackage` to create .app bundle inside a DMG
- Bundle JRE for self-contained distribution
- Code sign with Apple Developer certificate for Gatekeeper approval
- Notarize with Apple for "identified developer" status
- Include whisper-server setup instructions or brew formula
- launchd plist for whisper-server auto-start (optional)

**Command:** `jpackage --type dmg --input build/jars --main-jar Jeeves.jar --name Jeeves --app-version 1.2.0 --mac-sign --mac-signing-key-user-name "Developer ID"`

#### 5c. iOS App Store Distribution
**Approach:**
- Xcode Archive → App Store Connect upload
- Apple Developer Program membership required ($99/year)
- App Store review compliance (privacy manifest, data handling)
- TestFlight for beta distribution to testers
- Alternatively: Ad-hoc distribution for personal use (limited to 100 devices)

**Requirements:**
- App icons at all required sizes (1024x1024 for App Store)
- Privacy policy URL
- Screenshots for App Store listing
- App review information (demo account if needed)

#### 5d. Auto-Update Mechanism
**Approach:**
- Version check on startup against a version.json on GitHub releases
- "Update available" notification with changelog
- One-click download of new installer (or auto-download + prompt to install)
- For macOS: Sparkle framework integration
- For Windows: custom updater or WinSparkle

**Impact:** Without installers, adoption is limited to technical users who can build from source.

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
