# Jeeves - Meeting Recorder, Transcriber & Summariser

A fully local, privacy-first meeting recording application with AI transcription and summarization. Runs on macOS (desktop), iOS, and as an Obsidian plugin.

**No cloud services. No API keys. No subscriptions.** Everything runs on your hardware.

## Features

- 🎤 Record meetings with hotkey (Ctrl+Shift+R)
- 📝 Live streaming transcription (text appears as you speak)
- 🤖 AI summarization with key points, action items, and questions
- 🗣️ Speaker diarization (identify who said what)
- 🔍 Full-text search across all recordings
- 📋 Export to Markdown, plain text, email, Apple Reminders, and Obsidian
- 🏷️ Tags, folders, and meeting templates
- 📅 macOS Calendar integration (shows upcoming meetings)
- 🔊 System audio capture (record Zoom/Teams calls via BlackHole)
- 📱 iOS app with local server connection
- 🧩 Obsidian plugin for recording directly in your vault

---

## Quick Start

### Prerequisites

- **macOS** (Apple Silicon or Intel)
- **Java 17+** (`java -version`)
- **Ollama** running at http://localhost:11434 with a model loaded (`ollama run llama3`)

### 1. Install whisper.cpp server

```bash
brew install whisper-cpp

# Download a model
mkdir -p ~/.local/share/whisper-models
curl -L -o ~/.local/share/whisper-models/ggml-small.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin

# Start the server
whisper-server \
  --model ~/.local/share/whisper-models/ggml-small.bin \
  --host 127.0.0.1 --port 8178 \
  --request-path /v1/audio
```

### 2. Run the desktop app

```bash
cd Jeeves
./gradlew :desktopApp:run
```

Or build a macOS .dmg:
```bash
./gradlew :desktopApp:packageDmg
# Output: desktopApp/build/compose/binaries/main/dmg/Jeeves-1.0.0.dmg
```

### 3. Configure settings

Open Settings (⌘,) and set:
- **Transcription Base URL**: `http://127.0.0.1:8178`
- **Transcription Model**: `whisper-small`
- **Summarization Base URL**: `http://127.0.0.1:11434`
- **Summarization Model**: `llama3`

---

## Auto-Start whisper-server on Login

Create a launchd service:

```bash
cat > ~/Library/LaunchAgents/com.jeeves.whisper-server.plist << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.jeeves.whisper-server</string>
    <key>ProgramArguments</key>
    <array>
        <string>/opt/homebrew/bin/whisper-server</string>
        <string>--model</string>
        <string>/Users/YOUR_USERNAME/.local/share/whisper-models/ggml-small.bin</string>
        <string>--host</string>
        <string>127.0.0.1</string>
        <string>--port</string>
        <string>8178</string>
        <string>--request-path</string>
        <string>/v1/audio</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
EOF

launchctl load ~/Library/LaunchAgents/com.jeeves.whisper-server.plist
```

---

## WhisperX (Better Transcription)

For improved accuracy, use WhisperX instead of whisper.cpp:

```bash
conda create -n whisperx python=3.11 -y
conda activate whisperx
pip install whisperx flask

cd whisperx-server
python server.py  # Runs on port 8179
```

Then set Base URL to `http://127.0.0.1:8179` in settings.

---

## Obsidian Plugin

Record meetings directly in Obsidian with automatic note creation.

### Install

```bash
cd obsidian-plugin
npm install
npm run build

# Copy to your vault
cp main.js manifest.json styles.css \
  /path/to/your/vault/.obsidian/plugins/jeeves-meeting-recorder/
```

Then enable "Jeeves Meeting Recorder" in Obsidian Settings → Community Plugins.

### Usage

1. Click 🎤 in the ribbon to start recording
2. Click again to stop
3. A structured note appears in your `Meetings/` folder with summary, key points, actions, and transcription

---

## iOS App

The iOS app connects to your Mac's whisper server over LAN.

### Setup

1. Expose whisper-server to LAN:
   ```bash
   # Change 127.0.0.1 to 0.0.0.0 in the launchd plist
   sed -i '' 's/127.0.0.1/0.0.0.0/' ~/Library/LaunchAgents/com.jeeves.whisper-server.plist
   launchctl unload ~/Library/LaunchAgents/com.jeeves.whisper-server.plist
   launchctl load ~/Library/LaunchAgents/com.jeeves.whisper-server.plist
   ```

2. Find your Mac's IP:
   ```bash
   ipconfig getifaddr en0
   ```

3. In the iOS app Settings, set Base URL to `http://<your-mac-ip>:8178`

4. Build with Xcode (requires Apple Developer account for device deployment)

---

## System Audio Capture (Record Zoom/Teams)

To record system audio (not just microphone):

1. Install BlackHole: `brew install blackhole-2ch`
2. Open **Audio MIDI Setup** → Create **Multi-Output Device** (Speakers + BlackHole)
3. Set the Multi-Output as your system audio output
4. In Jeeves Settings → Audio Input Device → Select "BlackHole 2ch"

---

## Project Structure

```
Jeeves/
├── shared/                 # Kotlin Multiplatform shared code
│   └── src/commonMain/     # Domain models, AI clients, recording manager
├── desktopApp/             # Compose Desktop app (macOS/Windows)
│   └── src/desktopMain/    # Audio recorder, streaming, UI screens
├── iosApp/                 # SwiftUI iOS app
│   └── Jeeves/             # Views, audio, network clients
├── obsidian-plugin/        # Obsidian community plugin
│   └── main.ts             # Plugin source
├── whisperx-server/        # WhisperX Python API server
│   └── server.py           # Flask server wrapping WhisperX
└── gradle/                 # Gradle wrapper
```

---

## Available Models

| Model | Size | Speed | Accuracy | Use Case |
|-------|------|-------|----------|----------|
| ggml-tiny | 75MB | Fastest | Lower | Quick notes |
| ggml-base | 142MB | Fast | Decent | Daily use |
| **ggml-small** | 465MB | Good | **Recommended** | Meetings |
| ggml-medium | 1.5GB | Slower | Better | Important recordings |
| ggml-large-v3 | 3GB | Slowest | Best | Critical accuracy |

---

## Tech Stack

- **Desktop**: Kotlin Multiplatform + Compose Desktop
- **iOS**: SwiftUI
- **Obsidian Plugin**: TypeScript
- **Transcription**: whisper.cpp / WhisperX (local)
- **Summarization**: Ollama (local LLM)
- **Audio**: javax.sound.sampled (desktop), AVFoundation (iOS), Web Audio API (Obsidian)

---

## License

MIT
