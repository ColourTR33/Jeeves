import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appState: AppStateManager

    var body: some View {
        NavigationStack {
            Form {
                Section("Transcription (Whisper)") {
                    TextField("Base URL", text: $appState.settings.whisperBaseUrl)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    TextField("Model Name", text: $appState.settings.whisperModel)
                        .autocapitalization(.none)
                }

                Section("Summarization (LLM)") {
                    TextField("Base URL", text: $appState.settings.ollamaBaseUrl)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    TextField("Model Name", text: $appState.settings.ollamaModel)
                        .autocapitalization(.none)
                }

                Section {
                    Button("Save Settings") {
                        appState.saveSettings()
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}
