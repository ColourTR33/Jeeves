import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appState: AppStateManager
    @State private var whisperConnected = false
    @State private var ollamaConnected = false
    @State private var checkingConnection = false

    var body: some View {
        NavigationStack {
            Form {
                // Transcription endpoint
                Section("Transcription (Whisper)") {
                    TextField("Base URL", text: $appState.settings.whisperBaseUrl)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    TextField("Model Name", text: $appState.settings.whisperModel)
                        .autocapitalization(.none)
                    HStack {
                        Circle()
                            .fill(whisperConnected ? .green : .red)
                            .frame(width: 8, height: 8)
                        Text(whisperConnected ? "Connected" : "Not reachable")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }

                // Summarization endpoint
                Section("Summarization (LLM)") {
                    TextField("Base URL", text: $appState.settings.ollamaBaseUrl)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    TextField("Model Name", text: $appState.settings.ollamaModel)
                        .autocapitalization(.none)
                }

                // Streaming transcription
                Section("Streaming Transcription") {
                    Toggle("Enable live transcription", isOn: $appState.settings.streamingEnabled)

                    if appState.settings.streamingEnabled {
                        HStack {
                            Text("Chunk interval")
                            Spacer()
                            Stepper("\(appState.settings.chunkIntervalSeconds)s",
                                    value: $appState.settings.chunkIntervalSeconds,
                                    in: 3...30)
                        }

                        HStack {
                            Text("Overlap window")
                            Spacer()
                            Text(String(format: "%.1fs", appState.settings.overlapWindowSeconds))
                                .foregroundColor(.secondary)
                        }
                        Slider(value: $appState.settings.overlapWindowSeconds, in: 0.5...5.0, step: 0.5)
                    }
                }

                // Speaker diarization
                Section("Speaker Diarization") {
                    Toggle("Enable speaker identification", isOn: $appState.settings.diarizationEnabled)

                    if appState.settings.diarizationEnabled {
                        Text("Speakers will be colour-coded in the transcription view. Tap a speaker label to rename.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                // Connection test
                Section("Connection") {
                    Text("Point the Base URL to your server's IP address and port on your local network.")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Button(action: checkConnections) {
                        HStack {
                            if checkingConnection {
                                ProgressView().scaleEffect(0.8)
                            }
                            Text("Test Connection")
                        }
                    }
                    .disabled(checkingConnection)
                }

                Section {
                    Button("Save Settings") {
                        appState.saveSettings()
                    }
                }
            }
            .navigationTitle("Settings")
            .onAppear { checkConnections() }
        }
    }

    private func checkConnections() {
        checkingConnection = true
        Task {
            let whisperOk = await testConnection(baseUrl: appState.settings.whisperBaseUrl)
            let ollamaOk = await testConnection(baseUrl: appState.settings.ollamaBaseUrl)
            await MainActor.run {
                whisperConnected = whisperOk
                ollamaConnected = ollamaOk
                checkingConnection = false
            }
        }
    }

    private func testConnection(baseUrl: String) async -> Bool {
        guard let url = URL(string: baseUrl) else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                return httpResponse.statusCode < 500
            }
            return false
        } catch {
            return false
        }
    }
}
