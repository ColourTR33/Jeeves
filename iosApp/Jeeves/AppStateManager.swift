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

    let audioPlayer = iOSAudioPlayer()
    let audioRecorder = iOSAudioRecorder()

    private var timer: Timer?
    private var currentRecordingPath: String?
    private var recordingStartTime: Date?

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
            try audioRecorder?.startRecording(to: path)
            recordingState = .recording
            recordingStartTime = Date()
            startTimer()
            error = nil
        } catch {
            self.error = "Failed to start recording: \(error.localizedDescription)"
        }
    }

    func stopRecording() {
        audioRecorder?.stopRecording()
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
            title: "Meeting \(recordings.count + 1)"
        )

        recordings.insert(recording, at: 0)
        saveRecordings()

        recordingState = .processing
        processRecording(recording)
    }

    func pauseRecording() {
        audioRecorder?.pauseRecording()
        recordingState = .paused
        stopTimer()
    }

    func resumeRecording() {
        audioRecorder?.resumeRecording()
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
        UserDefaults.standard.set(text, forKey: "transcription_\(recordingId)")
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

struct RecordingItem: Identifiable, Codable {
    let id: String
    let filePath: String
    let durationMs: Int
    let createdAt: Date
    var title: String
}

struct SettingsData: Codable {
    var whisperBaseUrl: String = "http://192.168.1.100:8178"
    var whisperModel: String = "whisper-small"
    var ollamaBaseUrl: String = "http://192.168.1.100:11434"
    var ollamaModel: String = "llama3"
}

struct SummaryData: Codable {
    let summary: String
    let keyPoints: [String]
    let actionItems: [String]
    let modelUsed: String
}
