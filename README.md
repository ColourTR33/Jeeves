# Jeeves - Meeting Recorder, Transcriber & Summariser

A cross-platform app for recording meetings, transcribing them with a local Whisper model, and generating summaries with a local LLM. Runs on **Windows desktop** and **iOS**.

## Features

- **Record meetings** from your microphone (phone or laptop)
- **Global hotkey** (Ctrl+Shift+R) to start/stop recording on desktop
- **iOS home screen widget** for one-tap recording
- **Local AI transcription** via any Whisper-compatible API
- **Local AI summarisation** via Ollama or any OpenAI-compatible LLM endpoint
- **Configurable endpoints** — choose your models and servers
- Key points and action items extracted automatically

## Architecture

```
Jeeves/
├── shared/              # Kotlin Multiplatform shared module
│   ├── commonMain/      # Domain models, interfaces, AI clients, recording manager
│   ├── desktopMain/     # JVM platform implementations
│   └── iosMain/         # iOS/Native platform implementations
├── desktopApp/          # Compose Desktop (Windows) application
└── iosApp/              # SwiftUI iOS application + WidgetKit widget
```

## Prerequisites

### Desktop (Windows)
- **JDK 17+** (for building and running)
- **Gradle 8.5+** (wrapper included)

### iOS
- **Xcode 15+**
- **macOS** (required for iOS builds)
- **iOS 17+** device or simulator

### Local AI Services
- **Whisper server** — any OpenAI-compatible transcription API, such as:
  - [faster-whisper-server](https://github.com/fedirz/faster-whisper-server)
  - [whisper.cpp server](https://github.com/ggerganov/whisper.cpp)
  - [LocalAI](https://localai.io/)
- **LLM server** — any Ollama or OpenAI-compatible chat API:
  - [Ollama](https://ollama.ai/) (recommended, easiest setup)
  - [LM Studio](https://lmstudio.ai/)
  - [LocalAI](https://localai.io/)

## Quick Start

### 1. Start your local AI services

**Ollama (summarisation):**
```bash
ollama pull llama3
ollama serve
```

**Whisper (transcription) — using faster-whisper-server:**
```bash
pip install faster-whisper-server
faster-whisper-server --model large-v3 --host 0.0.0.0 --port 8080
```

### 2. Run the desktop app

```bash
./gradlew :desktopApp:run
```

Or build a distributable:
```bash
./gradlew :desktopApp:packageMsi
```

The MSI installer will be in `desktopApp/build/compose/binaries/main/msi/`.

### 3. Build the iOS app

See [iosApp/Jeeves.xcodeproj/project-setup.md](iosApp/Jeeves.xcodeproj/project-setup.md) for Xcode project creation steps.

Once the Xcode project is set up:
1. Open the project in Xcode
2. Select your team for signing
3. Build and run on your device

## Configuration

### Desktop
Settings are stored in `~/Jeeves/settings.json`. You can also configure via the Settings screen in the app:

| Setting | Default | Description |
|---------|---------|-------------|
| Whisper URL | `http://localhost:8080` | Base URL for Whisper-compatible API |
| Whisper Model | `whisper-large-v3` | Model name to pass to the API |
| Ollama URL | `http://localhost:11434` | Base URL for LLM API |
| Ollama Model | `llama3` | Model name for summarisation |
| Hotkey | `Ctrl+Shift+R` | Global toggle for recording |
| Audio Format | `wav` | Recording format |
| Sample Rate | `16000` | Sample rate in Hz (16kHz optimal for Whisper) |

### iOS
Configure the same endpoints in the Settings tab. The iOS app connects to your AI servers over your local network — ensure your phone can reach the server IP.

## Data Storage

- **Desktop:** `~/Jeeves/` — settings, recordings, transcriptions, summaries
- **iOS:** App Documents directory + UserDefaults

## Hotkeys

| Platform | Action | Shortcut |
|----------|--------|----------|
| Windows | Toggle recording | `Ctrl+Shift+R` |
| iOS | Toggle recording | Home screen widget tap |

## Project Structure - Key Files

### Shared Module (Kotlin)
- `domain/Models.kt` — Data classes (Recording, TranscriptionResult, SummaryResult, etc.)
- `domain/Interfaces.kt` — Platform contracts (AudioRecorder, TranscriptionService, etc.)
- `ai/WhisperClient.kt` — Whisper API client (multipart upload)
- `ai/OllamaClient.kt` — LLM client (Ollama native + OpenAI-compatible fallback)
- `recording/RecordingManager.kt` — Orchestrates record → transcribe → summarise pipeline

### Desktop App (Kotlin/Compose)
- `Main.kt` — Application entry point
- `audio/DesktopAudioRecorder.kt` — Microphone capture via javax.sound.sampled
- `hotkey/HotkeyManager.kt` — Global hotkey via JNativeHook
- `ui/screens/` — Recording, RecordingsList, Settings screens

### iOS App (Swift/SwiftUI)
- `JeevesApp.swift` — App entry point
- `AppStateManager.swift` — Central state management
- `Audio/iOSAudioRecorder.swift` — AVAudioRecorder wrapper
- `Network/` — WhisperAPIClient, OllamaAPIClient
- `Views/` — Recording, RecordingsList, Settings views
- `JeevesWidget/` — WidgetKit home screen widget

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Shared logic | Kotlin Multiplatform |
| Desktop UI | Compose Desktop (Material 3) |
| iOS UI | SwiftUI |
| iOS widget | WidgetKit + AppIntents |
| HTTP client | Ktor (shared), URLSession (iOS) |
| Audio (desktop) | javax.sound.sampled |
| Audio (iOS) | AVAudioRecorder |
| Global hotkey | JNativeHook |
| Serialization | kotlinx.serialization / Codable |

## License

Private project.
