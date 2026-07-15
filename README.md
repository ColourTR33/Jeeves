# Jeeves - Meeting Recorder, Transcriber & Summariser

A fully local, privacy-first meeting recording application with AI-powered transcription, speaker diarization, and summarization. Built with Kotlin Multiplatform, currently deployed on Windows desktop with an iOS companion app planned.

**No cloud services required. No API keys. No subscriptions.** Everything runs on your hardware.

---

## Features

### Recording
- Global hotkey recording (Ctrl+Shift+R) — start/stop from anywhere
- Microphone + system audio capture (Stereo Mix) for recording both sides of calls
- Automatic call detection (Microsoft Teams, Zoom, etc.)
- Live streaming transcription (text appears as you speak, toggleable per session)
- Screenshot capture during recording
- Meeting metadata: title, description, project, attendees, private notes, reminders

### Transcription & AI
- Local Whisper transcription via multi-backend server (CPU INT8, CUDA, MLX, whisper.cpp)
- Speaker diarization via local pyannote-audio 3.1 server
- AI summarization with Ollama (key points, action items, questions, quality rating)
- Meeting templates: General, Standup, One-on-One, Interview, Brainstorm
- Self-healing: auto-retries on connection failures (up to 5 attempts, exponential backoff)
- Re-transcribe and re-summarize buttons on completed recordings

### Time Tracking
- Per-project time logging with start/stop timer
- Weekly hour targets per project with burndown chart
- Per-project backlog with priority and time estimates
- Sprint planning: accept items from backlog, countdown timer
- Week navigation (past/future up to 4 weeks)
- Auto-distribute admin hours to under-target projects
- Forward planning overview table
- Meeting-to-timesheet integration (+10 min handoff automatically added)
- Manual time entry and edit (today and past days)
- 15-minute rounding on timesheet display
- 2-hour auto-stop failsafe on running timers
- Weekly export generator with allocation vs burned hours (10% variance flags)
- Project CRUD with full metadata (TDM, contact, company, software versions, dates)

### Operations
- In-app log viewer (last 500 entries, filter by level, copy to clipboard)
- Recording archival (audio archived after 7 days, deleted after 30 — transcriptions/summaries kept forever)
- Log rotation on new day
- Processing status indicators (green icons for transcribed/summarized)

---

## Architecture

```
Jeeves/
├── shared/                  # Kotlin Multiplatform shared code
│   └── src/commonMain/      # Domain models, AI clients, recording manager,
│                            # processing queue, time tracking, archival
├── desktopApp/              # Compose Desktop app (Windows primary)
│   └── src/desktopMain/     # Audio capture, streaming, UI screens, hotkeys
├── iosApp/                  # SwiftUI iOS app (planned)
├── whisper-server/          # Python FastAPI — multi-backend Whisper transcription
├── diarization-server/      # Python FastAPI — pyannote speaker diarization
├── whisperx-server/         # Legacy WhisperX server (deprecated)
└── obsidian-plugin/         # Obsidian community plugin (legacy, unmaintained)
```

---

## Quick Start (Windows)

### Prerequisites

- **Windows 10/11** (Intel or AMD, no GPU required)
- **Java 17+** — [Eclipse Adoptium JDK 17](https://adoptium.net/)
- **Python 3.10+** — for whisper-server
- **Ollama** — for AI summarization: https://ollama.ai

### 1. Install Ollama and pull a model

```powershell
# After installing Ollama from https://ollama.ai
ollama pull qwen3:8b
```

Ollama runs at http://localhost:11434 by default.

### 2. Install and start the Whisper server

```powershell
cd whisper-server
pip install -r requirements.txt
python server.py
```

The server auto-detects your platform:
- **Windows CPU (Intel)**: Uses faster-whisper with INT8 quantization (no GPU needed)
- **Windows + NVIDIA GPU**: Uses faster-whisper with CUDA FP16
- **macOS Apple Silicon**: Uses MLX Whisper

Server runs at http://localhost:8178.

### 3. (Optional) Install the Diarization server

For speaker identification ("who said what"):

```powershell
cd diarization-server
pip install -r requirements.txt

# You need a HuggingFace token (accept model terms first):
#   https://huggingface.co/pyannote/speaker-diarization-3.1
#   https://huggingface.co/pyannote/segmentation-3.0
set HF_TOKEN=hf_your_token_here
python server.py
```

Server runs at http://localhost:8180.

### 4. Build and deploy the desktop app

```powershell
cd desktopApp
Remove-Item build -Recurse -ErrorAction SilentlyContinue
..\gradlew :desktopApp:packageUberJarForCurrentOS --no-daemon --no-build-cache

# Copy to deployment location
taskkill /f /im javaw.exe 2>nul
copy build\compose\jars\Jeeves-win64-1.2.0.jar C:\Jeeves\Jeeves.jar
```

### 5. Launch

Double-click `C:\Jeeves\Jeeves.bat` or run:

```powershell
C:\Jeeves\Jeeves.bat
```

This starts the whisper server (with auto-restart on crash) and launches the Jeeves app.

---

## Deployment Layout

```
C:\Jeeves\
├── Jeeves.jar              # UberJar — the app
├── Jeeves.bat              # Launcher (starts whisper-server + app)
└── whisper-server.bat      # Whisper server with crash recovery

C:\Users\<user>\Jeeves\
├── settings.json           # App settings
├── recordings/             # Audio files (WAV)
├── transcriptions/         # Transcription JSON files
├── summaries/              # Summary JSON files
├── archive/                # Archived audio (after 7 days)
└── time-tracking/          # Time entries, projects, plans
```

---

## Configuration

Open Settings from within the app. Key settings:

| Setting | Default | Description |
|---------|---------|-------------|
| Transcription URL | http://localhost:8178 | Whisper server endpoint |
| Transcription Model | whisper-small | Model size (tiny/base/small/medium/large-v3) |
| Summarization URL | http://localhost:11434 | Ollama endpoint |
| Summarization Model | qwen3:8b | LLM model for summaries |
| Recording Hotkey | Ctrl+Shift+R | Global start/stop hotkey |
| Diarization Mode | PYANNOTE | Speaker identification method |
| Diarization URL | http://localhost:8180 | Pyannote server endpoint |
| Archive After | 7 days | Move audio to archive |
| Delete Audio After | 30 days | Remove archived audio files |

---

## Services & Ports

| Service | Port | Purpose |
|---------|------|---------|
| Whisper Server | 8178 | Audio transcription (faster-whisper CPU INT8) |
| Diarization Server | 8180 | Speaker identification (pyannote 3.1) |
| Ollama | 11434 | LLM summarization (qwen3:8b) |

---

## System Audio Capture (Record Both Sides of Calls)

To record meeting participants (not just your microphone):

1. Enable **Stereo Mix** in Windows Sound settings:
   - Right-click speaker icon → Sound Settings → More sound settings
   - Recording tab → Right-click → Show Disabled Devices
   - Enable "Stereo Mix (Realtek Audio)" or equivalent
2. In Jeeves Settings, enable **Capture System Audio**
3. The app will mix microphone + system audio automatically

---

## Available Whisper Models

| Model | Size | Speed (CPU) | Accuracy | Best For |
|-------|------|-------------|----------|----------|
| tiny | 75MB | Fastest | Lower | Quick tests |
| base | 142MB | Fast | Decent | Short meetings |
| **small** | 465MB | Good | **Recommended** | Daily meetings |
| medium | 1.5GB | Slower | Better | Important recordings |
| large-v3 | 3GB | Slowest | Best | Critical accuracy |

Set via environment variable: `set WHISPER_MODEL=small`

---

## Tech Stack

- **Desktop App**: Kotlin Multiplatform + Jetpack Compose Desktop
- **Transcription**: faster-whisper (CTranslate2) with INT8 quantization
- **Diarization**: pyannote-audio 3.1 (speaker-diarization-3.1)
- **Summarization**: Ollama + qwen3:8b (local LLM)
- **Audio**: javax.sound.sampled (microphone + Stereo Mix)
- **Hotkeys**: JNativeHook (global keyboard hooks)
- **Database**: SQLite (via sqlite-jdbc)
- **Networking**: Ktor HTTP client
- **Serialization**: kotlinx-serialization-json

---

## Troubleshooting

### Whisper server won't start
- Check Python is on PATH: `python --version`
- Check port isn't in use: `netstat -ano | findstr :8178`
- Try running manually: `python whisper-server/server.py`

### Transcription fails with "Connection refused"
- Whisper server may still be loading the model (first start takes 30-60s)
- The app auto-retries up to 5 times with exponential backoff
- Check Logs tab in the app for detailed error info

### App won't launch / "Failed to launch JVM"
- Ensure Java 17 is installed: `java -version`
- The .bat file uses a hardcoded Java path — update if your JDK is elsewhere

### Stale jar after rebuild
- Gradle caching is aggressive. Always use:
  ```powershell
  Remove-Item build -Recurse -ErrorAction SilentlyContinue
  ..\gradlew :desktopApp:packageUberJarForCurrentOS --no-daemon --no-build-cache
  ```
- Kill running app first: `taskkill /f /im javaw.exe`

---

## License

MIT
