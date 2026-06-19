import WidgetKit
import SwiftUI
import AppIntents

/// App Intent for toggling recording from the widget.
struct ToggleRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle Recording"
    static var description = IntentDescription("Start or stop a meeting recording")

    func perform() async throws -> some IntentResult {
        let defaults = UserDefaults(suiteName: "group.com.jeeves.app")
        let isRecording = defaults?.bool(forKey: "isRecording") ?? false
        defaults?.set(!isRecording, forKey: "isRecording")

        // Signal the main app via notification
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let name = "com.jeeves.app.toggleRecording" as CFString
        CFNotificationCenterPostNotification(center, CFNotificationName(name), nil, nil, true)

        // Reload widget timeline
        WidgetCenter.shared.reloadAllTimelines()

        return .result()
    }
}

/// The recording widget view.
struct JeevesRecordingWidget: Widget {
    let kind = "JeevesRecordingWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: RecordingTimelineProvider()) { entry in
            RecordingWidgetView(entry: entry)
        }
        .configurationDisplayName("Jeeves Recorder")
        .description("Start and stop meeting recordings.")
        .supportedFamilies([.systemSmall])
    }
}

struct RecordingWidgetView: View {
    let entry: RecordingEntry

    var body: some View {
        Button(intent: ToggleRecordingIntent()) {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(entry.isRecording ? Color.red : Color.blue)
                        .frame(width: 56, height: 56)
                    Image(systemName: entry.isRecording ? "stop.fill" : "mic.fill")
                        .font(.system(size: 24))
                        .foregroundColor(.white)
                }

                Text(entry.isRecording ? "Recording" : "Tap to Record")
                    .font(.caption)
                    .foregroundColor(entry.isRecording ? .red : .primary)
            }
        }
        .buttonStyle(.plain)
        .containerBackground(.fill, for: .widget)
    }
}
