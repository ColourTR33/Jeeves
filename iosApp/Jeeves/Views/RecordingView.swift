import SwiftUI

struct RecordingView: View {
    @EnvironmentObject var appState: AppStateManager

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                // Status text
                Text(statusText)
                    .font(.title)
                    .foregroundColor(statusColor)

                // Timer + level meter
                if appState.recordingState == .recording || appState.recordingState == .paused {
                    Text(formatDuration(appState.elapsedSeconds))
                        .font(.system(size: 56, weight: .light, design: .monospaced))

                    AudioLevelMeterView(level: appState.audioRecorder.audioLevel)
                        .frame(height: 28)
                        .padding(.horizontal, 48)
                }

                // Live transcript (streaming)
                if appState.settings.streamingEnabled &&
                   (appState.recordingState == .recording || appState.recordingState == .paused) {
                    LiveTranscriptView(
                        transcript: appState.liveTranscript,
                        isTranscribing: appState.isTranscribing,
                        isRecording: appState.recordingState == .recording
                    )
                    .padding(.horizontal)
                }

                // Meeting metadata fields (during recording)
                if appState.recordingState == .recording || appState.recordingState == .paused {
                    VStack(spacing: 8) {
                        TextField("Meeting Title", text: $appState.pendingTitle)
                            .textFieldStyle(.roundedBorder)
                            .font(.body)

                        TextField("Description / Agenda (optional)", text: $appState.pendingDescription, axis: .vertical)
                            .textFieldStyle(.roundedBorder)
                            .font(.caption)
                            .lineLimit(2...4)

                        HStack {
                            Button(action: { captureScreenshot() }) {
                                Label("Screenshot", systemImage: "camera.fill")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .tint(.blue)

                            if !appState.pendingAttachments.isEmpty {
                                Text("\(appState.pendingAttachments.count) screenshot(s)")
                                    .font(.caption2)
                                    .foregroundColor(.blue)
                            }
                            Spacer()
                        }
                    }
                    .padding(.horizontal)
                }

                // Processing progress
                if let progress = appState.progress {
                    ProgressView()
                        .padding(.bottom, 4)
                    Text(progress)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                // Controls
                HStack(spacing: 24) {
                    // Record/Stop button
                    Button(action: { appState.toggleRecording() }) {
                        ZStack {
                            Circle()
                                .fill(appState.recordingState == .recording ? Color.red : Color.accentColor)
                                .frame(width: 80, height: 80)
                            Image(systemName: appState.recordingState == .recording ? "stop.fill" : "mic.fill")
                                .font(.system(size: 32))
                                .foregroundColor(.white)
                        }
                    }
                    .disabled(appState.recordingState == .processing)

                    // Pause/Resume button
                    if appState.recordingState == .recording || appState.recordingState == .paused {
                        Button(action: {
                            if appState.recordingState == .paused {
                                appState.resumeRecording()
                            } else {
                                appState.pauseRecording()
                            }
                        }) {
                            ZStack {
                                Circle()
                                    .fill(Color.secondary.opacity(0.2))
                                    .frame(width: 56, height: 56)
                                Image(systemName: appState.recordingState == .paused ? "play.fill" : "pause.fill")
                                    .font(.system(size: 24))
                                    .foregroundColor(.primary)
                            }
                        }
                    }
                }

                // Bookmark button (during recording)
                if appState.recordingState == .recording || appState.recordingState == .paused {
                    Button(action: { appState.addBookmark() }) {
                        Label("Bookmark", systemImage: "star.fill")
                            .font(.subheadline)
                    }
                    .buttonStyle(.bordered)
                    .tint(.orange)
                }

                // Meeting template picker (when idle)
                if appState.recordingState == .idle {
                    Picker("Meeting Type", selection: $appState.selectedTemplate) {
                        ForEach(MeetingTemplate.allCases, id: \.self) { template in
                            Text(template.rawValue).tag(template)
                        }
                    }
                    .pickerStyle(.menu)
                    .padding(.horizontal)
                }

                Spacer()

                // Error display
                if let error = appState.error {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        Text(error).font(.caption)
                        Spacer()
                        Button("Dismiss") { appState.error = nil }.font(.caption)
                    }
                    .padding()
                    .background(Color.red.opacity(0.1))
                    .cornerRadius(8)
                    .padding(.horizontal)
                }
            }
            .navigationTitle("Jeeves")
        }
    }

    private var statusText: String {
        switch appState.recordingState {
        case .idle: return "Ready to Record"
        case .recording: return "Recording..."
        case .paused: return "Paused"
        case .processing: return "Processing..."
        }
    }

    private var statusColor: Color {
        switch appState.recordingState {
        case .recording: return .red
        case .processing: return .blue
        default: return .primary
        }
    }

    private func formatDuration(_ seconds: Int) -> String {
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let secs = seconds % 60
        if hours > 0 { return String(format: "%02d:%02d:%02d", hours, minutes, secs) }
        return String(format: "%02d:%02d", minutes, secs)
    }

    private func captureScreenshot() {
        // On iOS, capture what's on screen using UIGraphicsImageRenderer
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = windowScene.windows.first else { return }

        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { ctx in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }

        // Save to documents
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let screenshotsDir = documentsDir.appendingPathComponent("recordings/screenshots")
        try? FileManager.default.createDirectory(at: screenshotsDir, withIntermediateDirectories: true)

        let id = UUID().uuidString
        let fileName = "screenshot_\(Int(Date().timeIntervalSince1970 * 1000)).png"
        let filePath = screenshotsDir.appendingPathComponent(fileName)

        if let data = image.pngData() {
            try? data.write(to: filePath)
            let timestampMs = appState.elapsedSeconds * 1000
            let attachment = AttachmentItem(id: id, filePath: filePath.path, timestampMs: timestampMs)
            appState.pendingAttachments.append(attachment)
        }
    }
}

// MARK: - Live Transcript View

private struct LiveTranscriptView: View {
    let transcript: String
    let isTranscribing: Bool
    let isRecording: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if transcript.isEmpty && isRecording {
                Text("Listening for speech...")
                    .font(.subheadline)
                    .foregroundColor(.secondary.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .center)
            } else if !transcript.isEmpty {
                ScrollView {
                    Text(transcript)
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 120)
            }

            if isTranscribing {
                HStack(spacing: 4) {
                    ProgressView().scaleEffect(0.6)
                    Text("Transcribing...")
                        .font(.caption2)
                        .foregroundColor(.accentColor)
                }
            }
        }
        .padding(12)
        .background(Color(.systemGray6))
        .cornerRadius(10)
    }
}
