import { Plugin, PluginSettingTab, App, Setting, Notice, TFile } from "obsidian";

interface JeevesSettings {
    whisperBaseUrl: string;
    whisperModel: string;
    ollamaBaseUrl: string;
    ollamaModel: string;
    notesFolder: string;
}

const DEFAULT_SETTINGS: JeevesSettings = {
    whisperBaseUrl: "http://127.0.0.1:8178",
    whisperModel: "whisper-small",
    ollamaBaseUrl: "http://127.0.0.1:11434",
    ollamaModel: "llama3",
    notesFolder: "Meetings"
};

interface SummaryData {
    summary: string;
    keyPoints: string[];
    actionItems: string[];
    questions: string[];
}

export default class JeevesPlugin extends Plugin {
    settings: JeevesSettings;
    mediaRecorder: MediaRecorder | null = null;
    audioChunks: Blob[] = [];
    isRecording = false;
    ribbonIcon: HTMLElement | null = null;

    async onload() {
        await this.loadSettings();

        // Add ribbon icon
        this.ribbonIcon = this.addRibbonIcon("microphone", "Jeeves: Record Meeting", () => {
            this.toggleRecording();
        });

        // Add command
        this.addCommand({
            id: "toggle-recording",
            name: "Toggle meeting recording",
            callback: () => this.toggleRecording()
        });

        this.addCommand({
            id: "transcribe-file",
            name: "Transcribe audio file",
            callback: () => this.transcribeExistingFile()
        });

        this.addSettingTab(new JeevesSettingTab(this.app, this));
    }

    async toggleRecording() {
        if (this.isRecording) {
            this.stopRecording();
        } else {
            await this.startRecording();
        }
    }

    async startRecording() {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            this.mediaRecorder = new MediaRecorder(stream, { mimeType: "audio/webm" });
            this.audioChunks = [];

            this.mediaRecorder.ondataavailable = (event) => {
                if (event.data.size > 0) {
                    this.audioChunks.push(event.data);
                }
            };

            this.mediaRecorder.onstop = async () => {
                stream.getTracks().forEach(track => track.stop());
                const audioBlob = new Blob(this.audioChunks, { type: "audio/webm" });
                await this.processRecording(audioBlob);
            };

            this.mediaRecorder.start(1000); // Collect data every second
            this.isRecording = true;
            this.updateRibbonIcon();
            new Notice("🔴 Recording started");
        } catch (error) {
            new Notice(`Failed to start recording: ${(error as Error).message}`);
        }
    }

    stopRecording() {
        if (this.mediaRecorder && this.isRecording) {
            this.mediaRecorder.stop();
            this.isRecording = false;
            this.updateRibbonIcon();
            new Notice("⏹ Recording stopped. Processing...");
        }
    }

    updateRibbonIcon() {
        if (this.ribbonIcon) {
            this.ribbonIcon.toggleClass("jeeves-recording", this.isRecording);
        }
    }

    async processRecording(audioBlob: Blob) {
        try {
            // Step 1: Transcribe
            new Notice("📝 Transcribing...");
            const transcription = await this.transcribe(audioBlob);

            // Step 2: Summarize
            new Notice("🤖 Summarizing...");
            const summary = await this.summarize(transcription);

            // Step 3: Create note
            await this.createMeetingNote(transcription, summary);
            new Notice("✅ Meeting note created!");
        } catch (error) {
            new Notice(`❌ Processing failed: ${(error as Error).message}`);
            console.error("Jeeves processing error:", error);
        }
    }

    async transcribe(audioBlob: Blob): Promise<string> {
        const endpoints = [
            `${this.settings.whisperBaseUrl}/v1/audio/inference`,
            `${this.settings.whisperBaseUrl}/inference`,
            `${this.settings.whisperBaseUrl}/v1/audio/transcriptions`
        ];

        const formData = new FormData();
        formData.append("file", audioBlob, "recording.webm");
        formData.append("model", this.settings.whisperModel);
        formData.append("response_format", "verbose_json");
        formData.append("language", "en");

        let lastError: Error | null = null;
        for (const endpoint of endpoints) {
            try {
                const response = await fetch(endpoint, {
                    method: "POST",
                    body: formData
                });

                if (response.status === 404) continue;
                if (!response.ok) {
                    lastError = new Error(`HTTP ${response.status}`);
                    continue;
                }

                const data = await response.json();
                return data.text || "";
            } catch (e) {
                lastError = e as Error;
            }
        }

        throw lastError || new Error("All transcription endpoints failed");
    }

    async summarize(transcription: string): Promise<SummaryData> {
        const prompt = `Please summarise the following meeting transcription. Provide:
1. A concise summary (2-3 paragraphs)
2. Key points discussed (bullet points)
3. Action items identified (bullet points)
4. Questions raised (bullet points)

Format your response as:
SUMMARY:
[your summary here]

KEY POINTS:
- [point 1]

ACTION ITEMS:
- [action 1]

QUESTIONS:
- [question 1]

TRANSCRIPTION:
${transcription}`;

        try {
            // Try Ollama API
            const response = await fetch(`${this.settings.ollamaBaseUrl}/api/generate`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ model: this.settings.ollamaModel, prompt, stream: false })
            });
            const data = await response.json();
            return this.parseSummary(data.response || "");
        } catch {
            return { summary: transcription, keyPoints: [], actionItems: [], questions: [] };
        }
    }

    parseSummary(response: string): SummaryData {
        const extractSection = (text: string, start: string, end: string | null): string => {
            const startIdx = text.indexOf(start);
            if (startIdx === -1) return "";
            const contentStart = startIdx + start.length;
            const contentEnd = end ? text.indexOf(end, contentStart) : text.length;
            return text.substring(contentStart, contentEnd === -1 ? text.length : contentEnd).trim();
        };

        const parseBullets = (text: string): string[] =>
            text.split("\n")
                .map(l => l.trim())
                .filter(l => l.startsWith("-") || l.startsWith("•"))
                .map(l => l.replace(/^[-•*]\s*/, "").trim())
                .filter(l => l.length > 0);

        return {
            summary: extractSection(response, "SUMMARY:", "KEY POINTS:") || response,
            keyPoints: parseBullets(extractSection(response, "KEY POINTS:", "ACTION ITEMS:")),
            actionItems: parseBullets(extractSection(response, "ACTION ITEMS:", "QUESTIONS:")),
            questions: parseBullets(extractSection(response, "QUESTIONS:", null))
        };
    }

    async createMeetingNote(transcription: string, summary: SummaryData) {
        const now = new Date();
        const dateStr = now.toISOString().split("T")[0];
        const timeStr = now.toTimeString().split(" ")[0].substring(0, 5);
        const title = `Meeting ${dateStr} ${timeStr}`;
        const fileName = `${title}.md`;

        const folder = this.settings.notesFolder;
        const folderPath = this.app.vault.getAbstractFileByPath(folder);
        if (!folderPath) {
            await this.app.vault.createFolder(folder);
        }

        const content = `---
title: "${title}"
date: ${dateStr} ${timeStr}
type: meeting
tags:
  - meeting
---

# ${title}

## Summary

${summary.summary}

${summary.keyPoints.length > 0 ? `## Key Points\n\n${summary.keyPoints.map(p => `- ${p}`).join("\n")}\n` : ""}
${summary.actionItems.length > 0 ? `## Action Items\n\n${summary.actionItems.map(a => `- [ ] ${a}`).join("\n")}\n` : ""}
${summary.questions.length > 0 ? `## Questions\n\n${summary.questions.map(q => `- ❓ ${q}`).join("\n")}\n` : ""}
## Transcription

${transcription}

---
*Recorded and transcribed by Jeeves*
`;

        const filePath = `${folder}/${fileName}`;
        await this.app.vault.create(filePath, content);

        // Open the note
        const file = this.app.vault.getAbstractFileByPath(filePath);
        if (file instanceof TFile) {
            await this.app.workspace.getLeaf().openFile(file);
        }
    }

    async transcribeExistingFile() {
        // Placeholder for file picker functionality
        new Notice("Use the ribbon icon to record, or drag an audio file into your vault.");
    }

    async loadSettings() {
        this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
    }

    async saveSettings() {
        await this.saveData(this.settings);
    }
}

class JeevesSettingTab extends PluginSettingTab {
    plugin: JeevesPlugin;

    constructor(app: App, plugin: JeevesPlugin) {
        super(app, plugin);
        this.plugin = plugin;
    }

    display(): void {
        const { containerEl } = this;
        containerEl.empty();

        containerEl.createEl("h2", { text: "Jeeves Meeting Recorder" });

        new Setting(containerEl)
            .setName("Whisper Base URL")
            .setDesc("URL of your local whisper server (whisper.cpp or WhisperX)")
            .addText(text => text
                .setPlaceholder("http://127.0.0.1:8178")
                .setValue(this.plugin.settings.whisperBaseUrl)
                .onChange(async (value) => {
                    this.plugin.settings.whisperBaseUrl = value;
                    await this.plugin.saveSettings();
                }));

        new Setting(containerEl)
            .setName("Whisper Model")
            .setDesc("Model name for transcription")
            .addText(text => text
                .setPlaceholder("whisper-small")
                .setValue(this.plugin.settings.whisperModel)
                .onChange(async (value) => {
                    this.plugin.settings.whisperModel = value;
                    await this.plugin.saveSettings();
                }));

        new Setting(containerEl)
            .setName("Ollama Base URL")
            .setDesc("URL of your local Ollama server for summarization")
            .addText(text => text
                .setPlaceholder("http://127.0.0.1:11434")
                .setValue(this.plugin.settings.ollamaBaseUrl)
                .onChange(async (value) => {
                    this.plugin.settings.ollamaBaseUrl = value;
                    await this.plugin.saveSettings();
                }));

        new Setting(containerEl)
            .setName("Ollama Model")
            .setDesc("Model name for summarization")
            .addText(text => text
                .setPlaceholder("llama3")
                .setValue(this.plugin.settings.ollamaModel)
                .onChange(async (value) => {
                    this.plugin.settings.ollamaModel = value;
                    await this.plugin.saveSettings();
                }));

        new Setting(containerEl)
            .setName("Notes Folder")
            .setDesc("Folder in your vault where meeting notes will be saved")
            .addText(text => text
                .setPlaceholder("Meetings")
                .setValue(this.plugin.settings.notesFolder)
                .onChange(async (value) => {
                    this.plugin.settings.notesFolder = value;
                    await this.plugin.saveSettings();
                }));
    }
}
