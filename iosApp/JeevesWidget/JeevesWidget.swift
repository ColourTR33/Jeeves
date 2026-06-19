import WidgetKit
import SwiftUI
import AppIntents

/// Widget entry with recording state.
struct RecordingEntry: TimelineEntry {
    let date: Date
    let isRecording: Bool
}

/// Timeline provider for the recording widget.
struct RecordingTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecordingEntry {
        RecordingEntry(date: Date(), isRecording: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (RecordingEntry) -> Void) {
        let isRecording = UserDefaults(suiteName: "group.com.jeeves.app")?
            .bool(forKey: "isRecording") ?? false
        completion(RecordingEntry(date: Date(), isRecording: isRecording))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RecordingEntry>) -> Void) {
        let isRecording = UserDefaults(suiteName: "group.com.jeeves.app")?
            .bool(forKey: "isRecording") ?? false
        let entry = RecordingEntry(date: Date(), isRecording: isRecording)
        let timeline = Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(30)))
        completion(timeline)
    }
}
