import SwiftUI
import EventKit

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
            if olderByMonth[key] == nil { monthOrder.append(key) }
            olderByMonth[key, default: []].append(recording)
        }
    }

    var groups: [RecordingGroup] = []
    if !today.isEmpty { groups.append(RecordingGroup(id: "today", label: "Today", recordings: today)) }
    if !thisWeek.isEmpty { groups.append(RecordingGroup(id: "thisWeek", label: "This Week", recordings: thisWeek)) }
    if !thisMonth.isEmpty { groups.append(RecordingGroup(id: "thisMonth", label: "This Month", recordings: thisMonth)) }
    for key in monthOrder {
        groups.append(RecordingGroup(id: key, label: key, recordings: olderByMonth[key]!))
    }
    return groups
}

// MARK: - Recordings List View

struct RecordingsListView: View {
    @EnvironmentObject var appState: AppStateManager
    @State private var searchQuery = ""
    @State private var searchResults: [SearchResult] = []
    @State private var selectedTag: String? = nil

    private var allTags: [String] {
        appState.recordings.flatMap { $0.tags }.removingDuplicates().sorted()
    }

    private var filteredRecordings: [RecordingItem] {
        if let tag = selectedTag {
            return appState.recordings.filter { $0.tags.contains(tag) }
        }
        return appState.recordings
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search recordings...", text: $searchQuery)
                        .textFieldStyle(.plain)
                    if !searchQuery.isEmpty {
                        Button(action: { searchQuery = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(10)
                .background(Color(.systemGray6))
                .cornerRadius(10)
                .padding(.horizontal)
                .padding(.top, 8)

                // Tag filter chips
                if !allTags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            TagChip(label: "All", isSelected: selectedTag == nil) {
                                selectedTag = nil
                            }
                            ForEach(allTags, id: \.self) { tag in
                                TagChip(label: "#\(tag)", isSelected: selectedTag == tag) {
                                    selectedTag = selectedTag == tag ? nil : tag
                                }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                    }
                }

                // Content
                if appState.recordings.isEmpty {
                    emptyState
                } else if !searchQuery.isEmpty {
                    searchResultsList
                } else {
                    groupedList
                }
            }
            .navigationTitle("Recordings")
            .onChange(of: searchQuery) { newValue in
                searchResults = appState.searchRecordings(query: newValue)
            }
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

    private var searchResultsList: some View {
        List {
            if searchResults.isEmpty {
                Text("No results found")
                    .foregroundColor(.secondary)
            } else {
                ForEach(searchResults) { result in
                    NavigationLink(destination: RecordingDetailView(recording: result.recording)) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(result.recording.title)
                                    .font(.body).fontWeight(.medium)
                                Spacer()
                                Text(result.matchSource)
                                    .font(.caption2)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(result.matchSource == "transcription" ? Color.purple.opacity(0.15) : Color.blue.opacity(0.15))
                                    .cornerRadius(4)
                            }
                            Text(result.snippet)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(2)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var groupedList: some View {
        let groups = groupRecordings(filteredRecordings)

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
                            .font(.subheadline).fontWeight(.bold)
                            .foregroundColor(.accentColor)
                        Spacer()
                        Text("\(group.recordings.count)")
                            .font(.caption2).fontWeight(.medium)
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

// MARK: - Tag Chip

private struct TagChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.caption)
                .fontWeight(isSelected ? .semibold : .regular)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(isSelected ? Color.accentColor.opacity(0.2) : Color(.systemGray5))
                .foregroundColor(isSelected ? .accentColor : .primary)
                .cornerRadius(14)
        }
    }
}

// MARK: - Recording Row

private struct RecordingRow: View {
    let recording: RecordingItem

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.accentColor.opacity(0.3))
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 3) {
                Text(recording.title)
                    .font(.body).fontWeight(.medium).lineLimit(1)

                HStack(spacing: 4) {
                    Text(formatTime(recording.createdAt))
                    Text("·").foregroundColor(.secondary.opacity(0.5))
                    Text(formatDuration(recording.durationMs))
                }
                .font(.caption).foregroundColor(.secondary)

                if !recording.tags.isEmpty {
                    Text(recording.tags.map { "#\($0)" }.joined(separator: " "))
                        .font(.caption2)
                        .foregroundColor(.purple)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func formatTime(_ date: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f.string(from: date)
    }
    private func formatDuration(_ ms: Int) -> String {
        let s = ms / 1000; let m = s / 60; let h = m / 60
        if h > 0 { return "\(h)h \(m % 60)m" }
        if m > 0 { return "\(m)m \(s % 60)s" }
        return "\(s)s"
    }
}

// MARK: - Recording Detail View

struct RecordingDetailView: View {
    @EnvironmentObject var appState: AppStateManager
    let recording: RecordingItem

    @State private var selectedTab = 0
    @State private var isEditingTitle = false
    @State private var editedTitle = ""
    @State private var newTag = ""
    @State private var isAddingTag = false
    @State private var showShareSheet = false
    @State private var shareContent = ""
    @State private var remindersStatus = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Editable title
                if isEditingTitle {
                    HStack {
                        TextField("Title", text: $editedTitle)
                            .textFieldStyle(.roundedBorder).font(.headline)
                        Button(action: {
                            appState.updateRecordingTitle(id: recording.id, title: editedTitle)
                            isEditingTitle = false
                        }) { Image(systemName: "checkmark.circle.fill").foregroundColor(.green) }
                        Button(action: { editedTitle = recording.title; isEditingTitle = false }) {
                            Image(systemName: "xmark.circle.fill").foregroundColor(.red)
                        }
                    }
                    .padding(.horizontal).padding(.top, 8)
                }

                // Tags section
                tagsSection.padding(.horizontal).padding(.top, 8)

                // Audio player
                AudioPlayerView(filePath: recording.filePath)
                    .padding(.horizontal).padding(.top, 12)

                // Export buttons
                exportButtons.padding(.horizontal).padding(.top, 12)

                // Tabs
                Picker("View", selection: $selectedTab) {
                    Text("Summary").tag(0)
                    Text("Transcription").tag(1)
                }
                .pickerStyle(.segmented).padding()

                // Content
                if selectedTab == 0 {
                    summaryView.padding(.horizontal)
                } else {
                    transcriptionView.padding(.horizontal)
                }
            }
        }
        .navigationTitle(recording.title)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { editedTitle = recording.title; isEditingTitle = true }) {
                    Image(systemName: "pencil")
                }
            }
        }
        .onAppear { editedTitle = recording.title }
        .sheet(isPresented: $showShareSheet) {
            ShareSheet(items: [shareContent])
        }
    }

    // MARK: - Tags Section

    private var tagsSection: some View {
        HStack(spacing: 6) {
            ForEach(recording.tags, id: \.self) { tag in
                Button(action: { appState.removeTag(from: recording.id, tag: tag) }) {
                    Text("#\(tag)")
                        .font(.caption2)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Color.purple.opacity(0.15))
                        .foregroundColor(.purple)
                        .cornerRadius(12)
                }
            }

            if isAddingTag {
                TextField("tag", text: $newTag)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 80)
                    .font(.caption)
                    .onSubmit {
                        if !newTag.isEmpty {
                            appState.addTag(to: recording.id, tag: newTag.trimmingCharacters(in: .whitespaces))
                            newTag = ""
                        }
                        isAddingTag = false
                    }
            } else {
                Button(action: { isAddingTag = true }) {
                    Image(systemName: "plus.circle")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    // MARK: - Export Buttons

    private var exportButtons: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Button(action: {
                    shareContent = appState.exportMarkdown(for: recording)
                    showShareSheet = true
                }) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.caption)
                }
                .buttonStyle(.bordered)

                Button(action: addToReminders) {
                    Label(remindersStatus.isEmpty ? "Reminders" : remindersStatus, systemImage: "bell")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .disabled(appState.getSummary(for: recording.id)?.actionItems.isEmpty != false || !remindersStatus.isEmpty)
            }
        }
    }

    private func addToReminders() {
        guard let summary = appState.getSummary(for: recording.id) else { return }
        remindersStatus = "Adding..."

        let store = EKEventStore()
        store.requestFullAccessToReminders { granted, _ in
            guard granted else {
                DispatchQueue.main.async { remindersStatus = "No access" }
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { remindersStatus = "" }
                return
            }
            var count = 0
            for item in summary.actionItems {
                let reminder = EKReminder(eventStore: store)
                reminder.title = item
                reminder.notes = "From: \(recording.title)"
                reminder.calendar = store.defaultCalendarForNewReminders()
                try? store.save(reminder, commit: false)
                count += 1
            }
            try? store.commit()
            DispatchQueue.main.async { remindersStatus = "\(count) added" }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { remindersStatus = "" }
        }
    }

    // MARK: - Summary View

    @ViewBuilder
    private var summaryView: some View {
        if let summary = appState.getSummary(for: recording.id) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Summary").font(.headline)
                Text(summary.summary)

                if !summary.keyPoints.isEmpty {
                    Text("Key Points").font(.headline)
                    ForEach(summary.keyPoints, id: \.self) { point in
                        HStack(alignment: .top) {
                            Text("•").foregroundColor(.accentColor)
                            Text(point)
                        }
                    }
                }

                if !summary.actionItems.isEmpty {
                    Text("Action Items").font(.headline)
                    ForEach(summary.actionItems, id: \.self) { item in
                        HStack(alignment: .top) {
                            Text("☐").foregroundColor(.accentColor)
                            Text(item)
                        }
                    }
                }

                if !summary.questions.isEmpty {
                    Text("Questions").font(.headline)
                    ForEach(summary.questions, id: \.self) { q in
                        HStack(alignment: .top) {
                            Text("❓").foregroundColor(.orange)
                            Text(q)
                        }
                    }
                }

                Text("Model: \(summary.modelUsed)")
                    .font(.caption).foregroundColor(.secondary)
            }
        } else {
            Text("No summary available yet.")
                .foregroundColor(.secondary).padding(.top, 32)
        }
    }

    // MARK: - Transcription View (with speaker diarization)

    @ViewBuilder
    private var transcriptionView: some View {
        if let data = appState.getTranscriptionData(for: recording.id) {
            if data.segments.isEmpty {
                Text(data.text).font(.body)
            } else {
                SpeakerTranscriptionView(segments: data.segments, recording: recording)
            }
        } else if let text = appState.getTranscription(for: recording.id) {
            Text(text).font(.body)
        } else {
            Text("No transcription available yet.")
                .foregroundColor(.secondary).padding(.top, 32)
        }
    }
}

// MARK: - Speaker Transcription View

private struct SpeakerTranscriptionView: View {
    @EnvironmentObject var appState: AppStateManager
    let segments: [TranscriptionSegment]
    let recording: RecordingItem

    @State private var editingSpeaker: String? = nil
    @State private var editingName = ""

    private var groupedSegments: [(speaker: String?, segments: [TranscriptionSegment])] {
        var groups: [(speaker: String?, segments: [TranscriptionSegment])] = []
        for segment in segments {
            if let last = groups.last, last.speaker == segment.speaker {
                groups[groups.count - 1].segments.append(segment)
            } else {
                groups.append((speaker: segment.speaker, segments: [segment]))
            }
        }
        return groups
    }

    private let speakerColors: [Color] = [.blue, .green, .orange, .purple, .pink, .teal, .indigo, .mint]

    private func colorForSpeaker(_ speaker: String) -> Color {
        let speakers = Array(Set(segments.compactMap { $0.speaker })).sorted()
        let index = speakers.firstIndex(of: speaker) ?? 0
        return speakerColors[index % speakerColors.count]
    }

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 8) {
            ForEach(Array(groupedSegments.enumerated()), id: \.offset) { _, group in
                if let speaker = group.speaker {
                    // Speaker label
                    if editingSpeaker == speaker {
                        HStack {
                            TextField("Name", text: $editingName)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 120)
                                .font(.caption)
                            Button("Save") {
                                appState.setSpeakerName(for: speaker, name: editingName)
                                editingSpeaker = nil
                            }.font(.caption)
                        }
                    } else {
                        Text(appState.getSpeakerName(for: speaker))
                            .font(.caption).fontWeight(.bold)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(colorForSpeaker(speaker).opacity(0.2))
                            .cornerRadius(4)
                            .onTapGesture {
                                editingSpeaker = speaker
                                editingName = appState.getSpeakerName(for: speaker)
                            }
                    }
                }

                ForEach(group.segments) { segment in
                    HStack(alignment: .top) {
                        Text(formatTimestamp(segment.startMs))
                            .font(.caption2)
                            .foregroundColor(.accentColor)
                            .frame(width: 45, alignment: .leading)
                            .onTapGesture {
                                appState.audioPlayer.play(filePath: recording.filePath)
                                appState.audioPlayer.seekTo(positionMs: segment.startMs)
                            }
                        Text(segment.text)
                            .font(.body)
                    }
                    .padding(.vertical, 1)
                    .padding(.horizontal, 4)
                    .background(
                        segment.speaker != nil
                            ? colorForSpeaker(segment.speaker!).opacity(0.08)
                            : Color.clear
                    )
                    .cornerRadius(4)
                }
            }
        }
    }

    private func formatTimestamp(_ ms: Int) -> String {
        let s = ms / 1000; let m = s / 60
        return String(format: "%d:%02d", m, s % 60)
    }
}

// MARK: - Share Sheet (UIActivityViewController)

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Array extension

extension Array where Element: Hashable {
    func removingDuplicates() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
