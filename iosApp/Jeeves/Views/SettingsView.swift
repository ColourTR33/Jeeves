import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appState: AppStateManager
    @State private var whisperConnected = false
    @State private var ollamaConnected = false
    @State private var checkingConnection = false

    var body: some View {
        NavigationStack {
            Form {
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
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Section("Summarization (LLM)") {
                    TextField("Base URL", text: $appState.settings.ollamaBaseUrl)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    TextField("Model Name", text: $appState.settings.ollamaModel)
                        .autocapitalization(.none)
                }

                Section("Connection") {
                    Text("Point the Base URL to your Mac's IP address and port (e.g., http://192.168.1.100:8178 for whisper-server or http://192.168.1.100:8179 for WhisperX)")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Button(action: checkConnections) {
                        HStack {
                            if checkingConnection {
                                ProgressView()
                                    .scaleEffect(0.8)
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
            .onAppear {
                checkConnections()
            }
        }
    }

    private func checkConnections() {
        checkingConnection = true
        Task {
            let whisperOk = await WhisperAPIClient.shared.checkConnectivity(baseUrl: appState.settings.whisperBaseUrl)
            await MainActor.run {
                whisperConnected = whisperOk
                checkingConnection = false
            }
        }
    }
}
