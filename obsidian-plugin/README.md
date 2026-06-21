# Jeeves Meeting Recorder - Obsidian Plugin

Record meetings directly in Obsidian. Transcribe with local Whisper AI and summarize with Ollama.

## Features
- 🎤 Record audio with one click (ribbon icon or Cmd+P command)
- 📝 Automatic transcription via local whisper server
- 🤖 AI summarization with key points, action items, and questions
- 📋 Creates structured notes with YAML frontmatter
- 🏷️ Auto-tagged with `meeting` tag
- 🔒 Fully local/private - no cloud services

## Setup
1. Ensure whisper-server is running (default: http://127.0.0.1:8178)
2. Ensure Ollama is running (default: http://127.0.0.1:11434)
3. Install this plugin in Obsidian
4. Configure server URLs in plugin settings

## Usage
- Click the microphone icon in the ribbon to start recording
- Click again to stop - transcription and summarization happen automatically
- A new note is created in your configured meetings folder
