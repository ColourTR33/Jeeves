import SwiftUI

struct RecordingsListView: View {
    @EnvironmentObject var appState: AppStateManager

    var body: some View {
        NavigationStack {
            List {
                if appState.recordings.isEmpty {
                    Text("No recordings yet.\nStart a recording to get started.")
                        .foregroundColor(.secondary)
                        .padding()
                } else {
                    ForEach(appState.recordings) { recording in
                        NavigationLink(destination: RecordingDetailView(recording: recording)) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(recording.title)
                                    .font(.headline)
                                HStack {
                                    Text(formatDate(recording.createdAt))
                                    Text("·")
                                    Text(formatDuration(recording.durationMs))
                                }
                                .font(.caption)
                                .foregroundColor(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .onDelete(perform: appState.deleteRecording)
                }
            }
            .navigationTitle("Recordings")
        }
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd MMM yyyy, HH:mm"
        return formatter.string(from: date)
    }

    private func formatDuration(_ ms: Int) -> String {
        let seconds = ms / 1000
        let minutes = seconds / 60
        let hours = minutes / 60
        if hours > 0 { return "\(hours)h \(minutes % 60)m" }
        if minutes > 0 { return "\(minutes)m \(seconds % 60)s" }
        return "\(seconds)s"
    }
}

struct RecordingDetailView: View {
    @EnvironmentObject var appState: AppStateManager
    let recording: RecordingItem

    @State private var selectedTab = 0
    @State private var isEditingTitle = false
    @State private var editedTitle = ""

    var body: some View {
        VStack {
            // Editable title
            if isEditingTitle {
                HStack {
                    TextField("Title", text: $editedTitle)
                        .textFieldStyle(.roundedBorder)
                    Button("Save") {
                        appState.updateRecordingTitle(id: recording.id, title: editedTitle)
                        isEditingTitle = false
                    }
                    Button("Cancel") {
                        editedTitle = recording.title
                        isEditingTitle = false
                    }
                }
                .padding(.horizontal)
            }

            // Audio playback controls
            AudioPlayerView(filePath: recording.filePath)
                .padding(.horizontal)

            Picker("View", selection: $selectedTab) {
                Text("Summary").tag(0)
                Text("Transcription").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            ScrollView {
                if selectedTab == 0 {
                    summaryView
                } else {
                    transcriptionView
                }
            }
            .padding(.horizontal)
        }
        .navigationTitle(recording.title)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: {
                    editedTitle = recording.title
                    isEditingTitle = true
                }) {
                    Image(systemName: "pencil")
                }
            }
        }
        .onAppear {
            editedTitle = recording.title
        }
    }

    @ViewBuilder
    private var summaryView: some View {
        if let summary = appState.getSummary(for: recording.id) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Summary")
                    .font(.headline)
                Text(summary.summary)

                if !summary.keyPoints.isEmpty {
                    Text("Key Points")
                        .font(.headline)
                    ForEach(summary.keyPoints, id: \.self) { point in
                        HStack(alignment: .top) {
                            Text("•")
                            Text(point)
                        }
                    }
                }

                if !summary.actionItems.isEmpty {
                    Text("Action Items")
                        .font(.headline)
                    ForEach(summary.actionItems, id: \.self) { item in
                        HStack(alignment: .top) {
                            Text("☐")
                            Text(item)
                        }
                    }
                }

                Text("Model: \(summary.modelUsed)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        } else {
            Text("No summary available yet.")
                .foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private var transcriptionView: some View {
        if let transcription = appState.getTranscription(for: recording.id) {
            Text(transcription)
        } else {
            Text("No transcription available yet.")
                .foregroundColor(.secondary)
        }
    }
}
