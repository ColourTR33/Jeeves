import SwiftUI

// MARK: - Grouping logic

private struct RecordingGroup: Identifiable {
    let id: String
    let label: String
    let recordings: [RecordingItem]
}

private func groupRecordings(_ recordings: [RecordingItem]) -> [RecordingGroup] {
    guard !recordings.isEmpty else { return [] }

    let calendar = Calendar.current
    let now = Date()
    let startOfToday = calendar.startOfDay(for: now)
    let startOfWeek = calendar.date(from: calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: now))!
    let startOfMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!

    var today: [RecordingItem] = []
    var thisWeek: [RecordingItem] = []
    var thisMonth: [RecordingItem] = []
    var olderByMonth: [String: [RecordingItem]] = [:]
    var monthOrder: [String] = []

    let monthYearFormatter = DateFormatter()
    monthYearFormatter.dateFormat = "MMMM yyyy"

    let monthOnlyFormatter = DateFormatter()
    monthOnlyFormatter.dateFormat = "MMMM"

    let currentYear = calendar.component(.year, from: now)

    for recording in recordings {
        let date = recording.createdAt
        if date >= startOfToday {
            today.append(recording)
        } else if date >= startOfWeek {
            thisWeek.append(recording)
        } else if date >= startOfMonth {
            thisMonth.append(recording)
        } else {
            let year = calendar.component(.year, from: date)
            let key = year == currentYear
                ? monthOnlyFormatter.string(from: date)
                : monthYearFormatter.string(from: date)
            if olderByMonth[key] == nil {
                monthOrder.append(key)
            }
            olderByMonth[key, default: []].append(recording)
        }
    }

    var groups: [RecordingGroup] = []

    if !today.isEmpty {
        groups.append(RecordingGroup(id: "today", label: "Today", recordings: today))
    }
    if !thisWeek.isEmpty {
        groups.append(RecordingGroup(id: "thisWeek", label: "This Week", recordings: thisWeek))
    }
    if !thisMonth.isEmpty {
        groups.append(RecordingGroup(id: "thisMonth", label: "This Month", recordings: thisMonth))
    }
    for key in monthOrder {
        groups.append(RecordingGroup(id: key, label: key, recordings: olderByMonth[key]!))
    }

    return groups
}

// MARK: - Recordings List View

struct RecordingsListView: View {
    @EnvironmentObject var appState: AppStateManager

    var body: some View {
        NavigationStack {
            Group {
                if appState.recordings.isEmpty {
                    emptyState
                } else {
                    groupedList
                }
            }
            .navigationTitle("Recordings")
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "mic.slash")
                .font(.system(size: 48))
                .foregroundColor(.secondary.opacity(0.4))
            Text("No recordings yet")
                .font(.title3)
                .foregroundColor(.secondary)
            Text("Use the widget or Record tab to start")
                .font(.caption)
                .foregroundColor(.secondary.opacity(0.6))
            Spacer()
        }
    }

    private var groupedList: some View {
        let groups = groupRecordings(appState.recordings)

        return List {
            ForEach(groups) { group in
                Section {
                    ForEach(group.recordings) { recording in
                        NavigationLink(destination: RecordingDetailView(recording: recording)) {
                            RecordingRow(recording: recording)
                        }
                    }
                    .onDelete { offsets in
                        deleteRecordings(in: group, at: offsets)
                    }
                } header: {
                    HStack {
                        Text(group.label)
                            .font(.subheadline)
                            .fontWeight(.bold)
                            .foregroundColor(.accentColor)
                        Spacer()
                        Text("\(group.recordings.count)")
                            .font(.caption2)
                            .fontWeight(.medium)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color.accentColor.opacity(0.15))
                            .clipShape(Capsule())
                            .foregroundColor(.accentColor)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func deleteRecordings(in group: RecordingGroup, at offsets: IndexSet) {
        for offset in offsets {
            let recording = group.recordings[offset]
            if let index = appState.recordings.firstIndex(where: { $0.id == recording.id }) {
                try? FileManager.default.removeItem(atPath: recording.filePath)
                appState.recordings.remove(at: index)
            }
        }
    }
}

// MARK: - Recording Row

private struct RecordingRow: View {
    let recording: RecordingItem

    var body: some View {
        HStack(spacing: 12) {
            // Time indicator
            Circle()
                .fill(Color.accentColor.opacity(0.3))
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 3) {
                Text(recording.title)
                    .font(.body)
                    .fontWeight(.medium)
                    .lineLimit(1)

                HStack(spacing: 4) {
                    Text(formatTime(recording.createdAt))
                    Text("·")
                        .foregroundColor(.secondary.opacity(0.5))
                    Text(formatDuration(recording.durationMs))
                }
                .font(.caption)
                .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
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

// MARK: - Recording Detail View

struct RecordingDetailView: View {
    @EnvironmentObject var appState: AppStateManager
    let recording: RecordingItem

    @State private var selectedTab = 0
    @State private var isEditingTitle = false
    @State private var editedTitle = ""

    var body: some View {
        VStack(spacing: 0) {
            // Editable title area
            if isEditingTitle {
                HStack {
                    TextField("Title", text: $editedTitle)
                        .textFieldStyle(.roundedBorder)
                        .font(.headline)
                    Button(action: {
                        appState.updateRecordingTitle(id: recording.id, title: editedTitle)
                        isEditingTitle = false
                    }) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                    }
                    Button(action: {
                        editedTitle = recording.title
                        isEditingTitle = false
                    }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.red)
                    }
                }
                .padding(.horizontal)
                .padding(.top, 8)
            }

            // Audio playback controls
            AudioPlayerView(filePath: recording.filePath)
                .padding(.horizontal)
                .padding(.top, 12)

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
                                .foregroundColor(.accentColor)
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
                                .foregroundColor(.accentColor)
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
                .padding(.top, 32)
        }
    }

    @ViewBuilder
    private var transcriptionView: some View {
        if let transcription = appState.getTranscription(for: recording.id) {
            Text(transcription)
                .font(.body)
        } else {
            Text("No transcription available yet.")
                .foregroundColor(.secondary)
                .padding(.top, 32)
        }
    }
}
