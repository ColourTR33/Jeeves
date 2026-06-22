import Foundation
import Combine
import UserNotifications

// MARK: - Models

struct TimeProject: Codable, Identifiable {
    let id: String
    var name: String
    var isBillable: Bool = true
    var isDistributed: Bool = false
    var color: String = "#4A90D9"
}

struct TimeEntry: Codable, Identifiable {
    let id: String
    let projectId: String
    var taskDescription: String = ""
    let startTime: Date
    var endTime: Date? = nil
    var durationMs: Int? = nil
    let date: String // "2026-06-19"
    var isRunning: Bool = false
    var linkedRecordingId: String? = nil

    func effectiveDurationMs(now: Date = Date()) -> Int {
        if isRunning { return Int(now.timeIntervalSince(startTime) * 1000) }
        return durationMs ?? Int((endTime ?? startTime).timeIntervalSince(startTime) * 1000)
    }

    func effectiveHours(now: Date = Date()) -> Double {
        return Double(effectiveDurationMs(now: now)) / 3_600_000.0
    }
}

struct TimesheetRow: Identifiable {
    var id: String { project.id }
    let project: TimeProject
    let hoursPerDay: [String: Double]
    let totalHours: Double
}

struct WeeklyTimesheet {
    let weekStartDate: String
    let rows: [TimesheetRow]
    let dailyTotals: [String: Double]
    let grandTotal: Double
    let targetHours: Double
    let dates: [String]
}

struct DistributionAllocation {
    let project: TimeProject
    let originalHours: Double
    let additionalHours: Double
    let newTotalHours: Double
}

struct DistributionPreview: Identifiable {
    var id: String { distributedProjectName }
    let distributedProjectName: String
    let totalDistributedHours: Double
    let allocations: [DistributionAllocation]
}

struct TimeReminderSettings: Codable {
    var idleReminderMinutes: Int = 60
    var longRunningReminderMinutes: Int = 60
    var quietHoursStart: Int = 18
    var quietHoursEnd: Int = 8
    var enabled: Bool = true
    var targetWeeklyHours: Double = 37.5
}

// MARK: - Time Tracking Manager

class TimeTrackingManager: ObservableObject {
    @Published var currentEntry: TimeEntry? = nil
    @Published var projects: [TimeProject] = []
    @Published var todayEntries: [TimeEntry] = []
    @Published var reminderSettings = TimeReminderSettings()
    @Published var pendingReminder: String? = nil

    private var reminderTimer: Timer?
    private var lastIdleNotify: Date = .distantPast
    private var lastLongRunningNotify: Date = .distantPast

    init() {
        loadProjects()
        loadReminderSettings()
        currentEntry = getRunningEntry()
        refreshToday()
        startReminderMonitor()
        requestNotificationPermission()
    }

    // MARK: - Timer Controls

    func startTimer(projectId: String, taskDescription: String = "") {
        stopTimer()
        let now = Date()
        let entry = TimeEntry(
            id: UUID().uuidString, projectId: projectId,
            taskDescription: taskDescription, startTime: now,
            date: dateString(from: now), isRunning: true
        )
        saveEntry(entry)
        currentEntry = entry
        refreshToday()
    }

    func stopTimer() {
        guard var running = currentEntry, running.isRunning else { return }
        let now = Date()
        running.endTime = now
        running.durationMs = Int(now.timeIntervalSince(running.startTime) * 1000)
        running.isRunning = false
        updateEntry(running)
        currentEntry = nil
        refreshToday()
    }

    func deleteEntry(_ id: String) {
        let today = dateString(from: Date())
        var entries = getEntriesForDate(today)
        entries.removeAll { $0.id == id }
        saveEntriesForDate(today, entries: entries)
        if currentEntry?.id == id { currentEntry = nil }
        refreshToday()
    }

    func logMeetingTime(projectId: String, recordingId: String, title: String, durationMs: Int) {
        let now = Date()
        let entry = TimeEntry(
            id: UUID().uuidString, projectId: projectId, taskDescription: title,
            startTime: now, endTime: now.addingTimeInterval(Double(durationMs) / 1000),
            durationMs: durationMs, date: dateString(from: now),
            isRunning: false, linkedRecordingId: recordingId
        )
        saveEntry(entry)
        refreshToday()
    }

    // MARK: - Projects

    func addProject(name: String, isBillable: Bool = true, isDistributed: Bool = false) {
        let project = TimeProject(id: UUID().uuidString, name: name, isBillable: isBillable, isDistributed: isDistributed)
        projects.append(project)
        saveProjects()
    }

    func deleteProject(_ id: String) {
        projects.removeAll { $0.id == id }
        saveProjects()
    }

    // MARK: - Weekly Timesheet

    func getWeeklyTimesheet() -> WeeklyTimesheet {
        let today = dateString(from: Date())
        let (monday, _) = getWeekBounds(today)
        let dates = generateWeekDates(from: monday)
        let allEntries = dates.flatMap { getEntriesForDate($0) }
        let now = Date()

        let rows: [TimesheetRow] = Dictionary(grouping: allEntries, by: { $0.projectId }).compactMap { (pid, entries) in
            guard let project = projects.first(where: { $0.id == pid }) else { return nil }
            var hpd: [String: Double] = [:]
            entries.forEach { e in hpd[e.date, default: 0] += e.effectiveHours(now: now) }
            return TimesheetRow(project: project, hoursPerDay: hpd, totalHours: hpd.values.reduce(0, +))
        }.sorted { $0.totalHours > $1.totalHours }

        let dailyTotals = Dictionary(uniqueKeysWithValues: dates.map { d in (d, rows.reduce(0.0) { $0 + ($1.hoursPerDay[d] ?? 0) }) })
        return WeeklyTimesheet(weekStartDate: monday, rows: rows, dailyTotals: dailyTotals,
                               grandTotal: rows.reduce(0) { $0 + $1.totalHours }, targetHours: reminderSettings.targetWeeklyHours, dates: dates)
    }

    func previewDistribution() -> [DistributionPreview] {
        let ts = getWeeklyTimesheet()
        let billableRows = ts.rows.filter { $0.project.isBillable && !$0.project.isDistributed }
        let totalBillable = billableRows.reduce(0.0) { $0 + $1.totalHours }
        guard totalBillable > 0 else { return [] }

        return ts.rows.filter { $0.project.isDistributed && $0.totalHours > 0 }.map { distRow in
            let allocs = billableRows.map { bp in
                let proportion = bp.totalHours / totalBillable
                let add = distRow.totalHours * proportion
                return DistributionAllocation(project: bp.project, originalHours: bp.totalHours, additionalHours: add, newTotalHours: bp.totalHours + add)
            }
            return DistributionPreview(distributedProjectName: distRow.project.name, totalDistributedHours: distRow.totalHours, allocations: allocs)
        }
    }

    func exportCSV() -> String {
        let ts = getWeeklyTimesheet()
        let days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        var csv = "Project,\(days.joined(separator: ",")),Total\n"
        for row in ts.rows {
            let hrs = ts.dates.map { String(format: "%.2f", row.hoursPerDay[$0] ?? 0) }
            csv += "\(row.project.name),\(hrs.joined(separator: ",")),\(String(format: "%.2f", row.totalHours))\n"
        }
        let totals = ts.dates.map { String(format: "%.2f", ts.dailyTotals[$0] ?? 0) }
        csv += "TOTAL,\(totals.joined(separator: ",")),\(String(format: "%.2f", ts.grandTotal))\n"
        return csv
    }

    // MARK: - Reminders

    func dismissReminder() { pendingReminder = nil }

    func saveReminderSettings(_ settings: TimeReminderSettings) {
        reminderSettings = settings
        if let data = try? JSONEncoder().encode(settings) {
            UserDefaults.standard.set(data, forKey: "time_reminder_settings")
        }
    }

    private func startReminderMonitor() {
        reminderTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.checkReminders()
        }
    }

    private func checkReminders() {
        guard reminderSettings.enabled else { return }
        let hour = Calendar.current.component(.hour, from: Date())
        if reminderSettings.quietHoursStart > reminderSettings.quietHoursEnd {
            if hour >= reminderSettings.quietHoursStart || hour < reminderSettings.quietHoursEnd { return }
        } else {
            if hour >= reminderSettings.quietHoursStart || hour < reminderSettings.quietHoursEnd { return }
        }

        let now = Date()
        if let entry = currentEntry, entry.isRunning {
            let runningMin = Int(now.timeIntervalSince(entry.startTime) / 60)
            if runningMin >= reminderSettings.longRunningReminderMinutes && now.timeIntervalSince(lastLongRunningNotify) > Double(reminderSettings.longRunningReminderMinutes * 60) {
                lastLongRunningNotify = now
                let desc = entry.taskDescription.isEmpty ? "current task" : entry.taskDescription
                let msg = "Timer for '\(desc)' has been running for \(runningMin) minutes. Still working on this?"
                DispatchQueue.main.async { self.pendingReminder = msg }
                sendNotification(title: "Timer still running", body: msg)
            }
        } else {
            let lastEnd = todayEntries.filter { !$0.isRunning }.compactMap { $0.endTime }.max() ?? now.addingTimeInterval(-3600)
            let idleMin = Int(now.timeIntervalSince(lastEnd) / 60)
            if idleMin >= reminderSettings.idleReminderMinutes && now.timeIntervalSince(lastIdleNotify) > Double(reminderSettings.idleReminderMinutes * 60) {
                lastIdleNotify = now
                let msg = "You haven't logged time for \(idleMin) minutes. What were you working on?"
                DispatchQueue.main.async { self.pendingReminder = msg }
                sendNotification(title: "Time not logged", body: msg)
            }
        }
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func sendNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - Persistence

    private func getEntriesForDate(_ date: String) -> [TimeEntry] {
        guard let data = UserDefaults.standard.data(forKey: "time_entries_\(date)") else { return [] }
        return (try? JSONDecoder().decode([TimeEntry].self, from: data)) ?? []
    }

    private func saveEntriesForDate(_ date: String, entries: [TimeEntry]) {
        if let data = try? JSONEncoder().encode(entries) {
            UserDefaults.standard.set(data, forKey: "time_entries_\(date)")
        }
    }

    private func saveEntry(_ entry: TimeEntry) {
        var entries = getEntriesForDate(entry.date)
        entries.append(entry)
        saveEntriesForDate(entry.date, entries: entries)
    }

    private func updateEntry(_ entry: TimeEntry) {
        var entries = getEntriesForDate(entry.date)
        if let i = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[i] = entry
            saveEntriesForDate(entry.date, entries: entries)
        }
    }

    private func getRunningEntry() -> TimeEntry? {
        let today = dateString(from: Date())
        return getEntriesForDate(today).first { $0.isRunning }
    }

    private func refreshToday() {
        todayEntries = getEntriesForDate(dateString(from: Date()))
    }

    private func loadProjects() {
        guard let data = UserDefaults.standard.data(forKey: "time_projects"),
              let p = try? JSONDecoder().decode([TimeProject].self, from: data) else { return }
        projects = p
    }

    private func saveProjects() {
        if let data = try? JSONEncoder().encode(projects) {
            UserDefaults.standard.set(data, forKey: "time_projects")
        }
    }

    private func loadReminderSettings() {
        guard let data = UserDefaults.standard.data(forKey: "time_reminder_settings"),
              let s = try? JSONDecoder().decode(TimeReminderSettings.self, from: data) else { return }
        reminderSettings = s
    }

    // MARK: - Date Helpers

    private func dateString(from date: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: date)
    }

    private func getWeekBounds(_ dateStr: String) -> (String, String) {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        guard let date = f.date(from: dateStr) else { return (dateStr, dateStr) }
        let cal = Calendar.current
        let weekday = cal.component(.weekday, from: date) // 1=Sun, 2=Mon...
        let mondayOffset = weekday == 1 ? -6 : -(weekday - 2)
        let monday = cal.date(byAdding: .day, value: mondayOffset, to: date)!
        let sunday = cal.date(byAdding: .day, value: 6, to: monday)!
        return (f.string(from: monday), f.string(from: sunday))
    }

    private func generateWeekDates(from monday: String) -> [String] {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        guard let start = f.date(from: monday) else { return [] }
        return (0..<7).map { f.string(from: Calendar.current.date(byAdding: .day, value: $0, to: start)!) }
    }
}
