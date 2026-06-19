import SwiftUI

struct RecordingView: View {
    @EnvironmentObject var appState: AppStateManager

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                // Status text
                Text(statusText)
                    .font(.title)
                    .foregroundColor(statusColor)

                // Timer
                if appState.recordingState == .recording || appState.recordingState == .paused {
                    Text(formatDuration(appState.elapsedSeconds))
                        .font(.system(size: 56, weight: .light, design: .monospaced))
                }

                // Progress
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

                Spacer()

                // Error display
                if let error = appState.error {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        Text(error)
                            .font(.caption)
                        Spacer()
                        Button("Dismiss") {
                            appState.error = nil
                        }
                        .font(.caption)
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
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%02d:%02d", minutes, secs)
    }
}
