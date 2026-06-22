import Foundation
import AVFoundation
import Combine

/// Main application state manager for the iOS app.
/// Bridges the KMP shared module with SwiftUI.
class AppStateManager: ObservableObject {
    @Published var recordingState: RecordingState = .idle
    @Published var elapsedSeconds: Int = 0
    @Published var error: String? = nil
    @Published var progress: String? = nil
    @Published var recordings: [RecordingItem] = []
    @Published var settings: SettingsData = SettingsData()

    // Streaming transcription
    @Published var liveTranscript: String = ""
    @Published var isTranscribing: Bool = false

    // Meeting template for current session
    @Published var selectedTemplate: MeetingTemplate = .general

    let audioPlayer = iOSAudioPlayer()
    let audioRecorder = iOSAudioRecorder()

    private var timer: Timer?
    private var currentRecordingPath: String?
    private var recordingStartTime: Date?
    private var currentBookmarks: [Bookmark] = []
    private var streamingClient: WhisperStreamingClient?

    init() {
        loadRecordings()
        loadSettings()
    }

    // MARK: - Recording Controls

    func toggleRecording() {
        switch recordingState {
        case .idle:
            startRecording()
        case .recording:
            stopRecording()
        case .paused:
            resumeRecording()
        case .processing:
            break
        }
    }

    func startRecording() {
        do {
            let path = generateRecordingPath()
            currentRecordingPath = path
            try audioRecorder.startRecording(to: path)
            recordingState = .recording
            recordingStartTime = Date()
            currentBookmarks = []
            liveTranscript = ""
            startTimer()
            error = nil

            // Start streaming transcription if enabled
            if settings.streamingEnabled {
                streamingClient = WhisperStreamingClient(
                    baseUrl: settings.whisperBaseUrl,
                    model: settings.whisperModel,
                    chunkInterval: settings.chunkIntervalSeconds,
                    overlapWindow: settings.overlapWindowSeconds
                )
                streamingClient?.start(audioFilePath: path) { [weak self] text, transcribing in
                    DispatchQueue.main.async {
                        self?.liveTranscript = text
                        self?.isTranscribing = transcribing
                    }
                }
            }
        } catch {
            self.error = "Failed to start recording: \(error.localizedDescription)"
        }
    }

    func stopRecording() {
        streamingClient?.stop()
        streamingClient = nil
        audioRecorder.stopRecording()
        stopTimer()

        guard let path = currentRecordingPath,
              let startTime = recordingStartTime else { return }

        let duration = Int(Date().timeIntervalSince(startTime) * 1000)
        let id = UUID().uuidString
        let recording = RecordingItem(
            id: id,
            filePath: path,
            durationMs: duration,
            createdAt: Date(),
            title: "Meeting \(recordings.count + 1)",
            meetingTemplate: selectedTemplate,
            bookmarks: currentBookmarks
        )

        recordings.insert(recording, at: 0)
        saveRecordings()

        recordingState = .processing
        processRecording(recording)
    }

    func pauseRecording() {
        audioRecorder.pauseRecording()
        recordingState = .paused
        stopTimer()
    }

    func resumeRecording() {
        audioRecorder.resumeRecording()
        recordingState = .recording
        startTimer()
    }

    // MARK: - Processing

    private func processRecording(_ recording: RecordingItem) {
        progress = "Transcribing audio..."

        Task {
            do {
                // Transcribe via Whisper API
                let transcription = try await WhisperAPIClient.shared.transcribe(
                    audioFilePath: recording.filePath,
                    baseUrl: settings.whisperBaseUrl,
                    model: settings.whisperModel
                )

                await MainActor.run {
                    self.progress = "Summarising transcription..."
                }

                // Save transcription
                saveTranscription(transcription, for: recording.id)

                // Summarise via Ollama/LLM API
                let summary = try await OllamaAPIClient.shared.summarize(
                    transcription: transcription,
                    baseUrl: settings.ollamaBaseUrl,
                    model: settings.ollamaModel
                )

                saveSummary(summary, for: recording.id)

                await MainActor.run {
                    self.progress = nil
                    self.recordingState = .idle
                }
            } catch {
                await MainActor.run {
                    self.error = "Processing failed: \(error.localizedDescription)"
                    self.progress = nil
                    self.recordingState = .idle
                }
            }
        }
    }

    // MARK: - Timer

    private func startTimer() {
        elapsedSeconds = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.elapsedSeconds += 1
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    // MARK: - Persistence

    private func generateRecordingPath() -> String {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let recordingsDir = documentsDir.appendingPathComponent("recordings")
        try? FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        return recordingsDir.appendingPathComponent("recording_\(timestamp).wav").path
    }

    private func loadRecordings() {
        guard let data = UserDefaults.standard.data(forKey: "recordings"),
              let items = try? JSONDecoder().decode([RecordingItem].self, from: data) else { return }
        recordings = items
    }

    private func saveRecordings() {
        guard let data = try? JSONEncoder().encode(recordings) else { return }
        UserDefaults.standard.set(data, forKey: "recordings")
    }

    func updateRecordingTitle(id: String, title: String) {
        if let index = recordings.firstIndex(where: { $0.id == id }) {
            recordings[index].title = title
            saveRecordings()
        }
    }

    // MARK: - Bookmarks

    func addBookmark() {
        let bookmark = Bookmark(
            id: UUID().uuidString,
            timestampMs: elapsedSeconds * 1000,
            label: "Bookmark at \(formatBookmarkTime(elapsedSeconds))"
        )
        currentBookmarks.append(bookmark)
    }

    private func formatBookmarkTime(_ seconds: Int) -> String {
        let m = seconds / 60
        let s = seconds % 60
        return String(format: "%d:%02d", m, s)
    }

    // MARK: - Tags

    func addTag(to recordingId: String, tag: String) {
        guard let index = recordings.firstIndex(where: { $0.id == recordingId }) else { return }
        if !recordings[index].tags.contains(tag) {
            recordings[index].tags.append(tag)
            saveRecordings()
        }
    }

    func removeTag(from recordingId: String, tag: String) {
        guard let index = recordings.firstIndex(where: { $0.id == recordingId }) else { return }
        recordings[index].tags.removeAll { $0 == tag }
        saveRecordings()
    }

    // MARK: - Search

    func searchRecordings(query: String) -> [SearchResult] {
        guard !query.isEmpty else { return [] }
        let lowerQuery = query.lowercased()
        var results: [SearchResult] = []

        for recording in recordings {
            // Search title
            if recording.title.lowercased().contains(lowerQuery) {
                results.append(SearchResult(recording: recording, matchSource: "title", snippet: recording.title))
                continue
            }
            // Search transcription
            if let transcription = getTranscription(for: recording.id),
               transcription.lowercased().contains(lowerQuery) {
                let snippet = extractSnippet(from: transcription, around: lowerQuery)
                results.append(SearchResult(recording: recording, matchSource: "transcription", snippet: snippet))
                continue
            }
            // Search summary
            if let summary = getSummary(for: recording.id),
               summary.summary.lowercased().contains(lowerQuery) {
                let snippet = extractSnippet(from: summary.summary, around: lowerQuery)
                results.append(SearchResult(recording: recording, matchSource: "summary", snippet: snippet))
            }
        }
        return results
    }

    private func extractSnippet(from text: String, around query: String) -> String {
        guard let range = text.lowercased().range(of: query) else { return String(text.prefix(100)) }
        let start = text.index(range.lowerBound, offsetBy: -40, limitedBy: text.startIndex) ?? text.startIndex
        let end = text.index(range.upperBound, offsetBy: 40, limitedBy: text.endIndex) ?? text.endIndex
        return "..." + String(text[start..<end]) + "..."
    }

    // MARK: - Export

    func exportMarkdown(for recording: RecordingItem) -> String {
        let transcription = getTranscription(for: recording.id) ?? "No transcription available"
        let summary = getSummary(for: recording.id)

        var md = "# \(recording.title)\n\n"
        md += "**Date:** \(formatExportDate(recording.createdAt))\n"
        md += "**Duration:** \(formatExportDuration(recording.durationMs))\n"
        if !recording.tags.isEmpty {
            md += "**Tags:** \(recording.tags.map { "#\($0)" }.joined(separator: " "))\n"
        }
        md += "\n"

        if let summary = summary {
            md += "## Summary\n\n\(summary.summary)\n\n"
            if !summary.keyPoints.isEmpty {
                md += "## Key Points\n\n"
                summary.keyPoints.forEach { md += "- \($0)\n" }
                md += "\n"
            }
            if !summary.actionItems.isEmpty {
                md += "## Action Items\n\n"
                summary.actionItems.forEach { md += "- [ ] \($0)\n" }
                md += "\n"
            }
            if !summary.questions.isEmpty {
                md += "## Questions\n\n"
                summary.questions.forEach { md += "- \($0)\n" }
                md += "\n"
            }
        }

        md += "## Transcription\n\n\(transcription)\n"
        return md
    }

    private func formatExportDate(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "dd MMM yyyy, HH:mm"
        return f.string(from: date)
    }

    private func formatExportDuration(_ ms: Int) -> String {
        let seconds = ms / 1000
        let minutes = seconds / 60
        return "\(minutes)m \(seconds % 60)s"
    }

    // MARK: - Speaker Names

    func getSpeakerName(for speakerId: String) -> String {
        UserDefaults.standard.string(forKey: "speaker_name_\(speakerId)") ?? speakerId
    }

    func setSpeakerName(for speakerId: String, name: String) {
        UserDefaults.standard.set(name, forKey: "speaker_name_\(speakerId)")
    }

    func deleteRecording(at offsets: IndexSet) {
        for index in offsets {
            let recording = recordings[index]
            try? FileManager.default.removeItem(atPath: recording.filePath)
        }
        recordings.remove(atOffsets: offsets)
        saveRecordings()
    }

    private func loadSettings() {
        guard let data = UserDefaults(suiteName: "group.com.jeeves.app")?.data(forKey: "settings"),
              let s = try? JSONDecoder().decode(SettingsData.self, from: data) else { return }
        settings = s
    }

    func saveSettings() {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        UserDefaults(suiteName: "group.com.jeeves.app")?.set(data, forKey: "settings")
        UserDefaults.standard.set(data, forKey: "settings")
    }

    private func saveTranscription(_ text: String, for recordingId: String) {
        // Save as TranscriptionData for segment support
        let data = TranscriptionData(text: text)
        if let encoded = try? JSONEncoder().encode(data) {
            UserDefaults.standard.set(encoded, forKey: "transcription_data_\(recordingId)")
        }
        // Also save plain text for backward compat
        UserDefaults.standard.set(text, forKey: "transcription_\(recordingId)")
    }

    func saveTranscriptionData(_ data: TranscriptionData, for recordingId: String) {
        if let encoded = try? JSONEncoder().encode(data) {
            UserDefaults.standard.set(encoded, forKey: "transcription_data_\(recordingId)")
        }
        UserDefaults.standard.set(data.text, forKey: "transcription_\(recordingId)")
    }

    func getTranscriptionData(for recordingId: String) -> TranscriptionData? {
        if let data = UserDefaults.standard.data(forKey: "transcription_data_\(recordingId)") {
            return try? JSONDecoder().decode(TranscriptionData.self, from: data)
        }
        // Fallback: wrap plain text
        if let text = UserDefaults.standard.string(forKey: "transcription_\(recordingId)") {
            return TranscriptionData(text: text)
        }
        return nil
    }

    private func saveSummary(_ summary: SummaryData, for recordingId: String) {
        guard let data = try? JSONEncoder().encode(summary) else { return }
        UserDefaults.standard.set(data, forKey: "summary_\(recordingId)")
    }

    func getTranscription(for recordingId: String) -> String? {
        UserDefaults.standard.string(forKey: "transcription_\(recordingId)")
    }

    func getSummary(for recordingId: String) -> SummaryData? {
        guard let data = UserDefaults.standard.data(forKey: "summary_\(recordingId)") else { return nil }
        return try? JSONDecoder().decode(SummaryData.self, from: data)
    }
}

// MARK: - Models

enum RecordingState {
    case idle, recording, paused, processing
}

enum MeetingTemplate: String, Codable, CaseIterable {
    case general = "General"
    case standup = "Stand-up"
    case oneOnOne = "One-on-One"
    case interview = "Interview"
    case brainstorm = "Brainstorm"
    case retrospective = "Retrospective"
}

struct Bookmark: Codable, Identifiable {
    let id: String
    let timestampMs: Int
    let label: String
}

struct TranscriptionSegment: Codable, Identifiable {
    var id: String { "\(startMs)-\(endMs)" }
    let startMs: Int
    let endMs: Int
    let text: String
    let speaker: String?
}

struct RecordingItem: Identifiable, Codable {
    let id: String
    let filePath: String
    let durationMs: Int
    let createdAt: Date
    var title: String
    var tags: [String] = []
    var folder: String = ""
    var meetingTemplate: MeetingTemplate = .general
    var bookmarks: [Bookmark] = []
}

struct SettingsData: Codable {
    var whisperBaseUrl: String = "http://192.168.1.100:8178"
    var whisperModel: String = "whisper-small"
    var ollamaBaseUrl: String = "http://192.168.1.100:11434"
    var ollamaModel: String = "llama3"
    // Streaming transcription
    var streamingEnabled: Bool = true
    var chunkIntervalSeconds: Int = 10
    var overlapWindowSeconds: Float = 2.0
    // Speaker diarization
    var diarizationEnabled: Bool = false
}

struct SummaryData: Codable {
    let summary: String
    let keyPoints: [String]
    let actionItems: [String]
    let questions: [String]
    let modelUsed: String

    // Allow decoding older data that may not have "questions"
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        summary = try container.decode(String.self, forKey: .summary)
        keyPoints = try container.decode([String].self, forKey: .keyPoints)
        actionItems = try container.decode([String].self, forKey: .actionItems)
        questions = try container.decodeIfPresent([String].self, forKey: .questions) ?? []
        modelUsed = try container.decode(String.self, forKey: .modelUsed)
    }

    init(summary: String, keyPoints: [String], actionItems: [String], questions: [String] = [], modelUsed: String) {
        self.summary = summary
        self.keyPoints = keyPoints
        self.actionItems = actionItems
        self.questions = questions
        self.modelUsed = modelUsed
    }
}

struct TranscriptionData: Codable {
    let text: String
    let segments: [TranscriptionSegment]
    let language: String

    init(text: String, segments: [TranscriptionSegment] = [], language: String = "en") {
        self.text = text
        self.segments = segments
        self.language = language
    }
}

struct SearchResult: Identifiable {
    var id: String { recording.id + matchSource }
    let recording: RecordingItem
    let matchSource: String
    let snippet: String
}
