package com.jeeves.shared.time

import com.jeeves.shared.domain.*
import com.jeeves.shared.recording.currentTimeMillis
import com.jeeves.shared.recording.generateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TimeTrackingManager(
    private val repository: TimeTrackingRepository,
    private val scope: CoroutineScope
) {
    private val _currentEntry = MutableStateFlow<TimeEntry?>(null)
    val currentEntry: StateFlow<TimeEntry?> = _currentEntry.asStateFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _todayEntries = MutableStateFlow<List<TimeEntry>>(emptyList())
    val todayEntries: StateFlow<List<TimeEntry>> = _todayEntries.asStateFlow()

    private val _reminderSettings = MutableStateFlow(TimeReminderSettings())
    val reminderSettings: StateFlow<TimeReminderSettings> = _reminderSettings.asStateFlow()

    fun initialize() {
        scope.launch {
            _projects.value = repository.getProjects()
            _currentEntry.value = repository.getRunningEntry()
            _reminderSettings.value = repository.getReminderSettings()
            refreshToday()
        }
    }

    fun startTimer(projectId: String, taskDescription: String = "") {
        scope.launch {
            stopTimerInternal()
            val now = currentTimeMillis()
            val entry = TimeEntry(
                id = generateId(), projectId = projectId,
                taskDescription = taskDescription, startTime = now,
                date = epochToDateString(now), isRunning = true
            )
            repository.saveTimeEntry(entry)
            _currentEntry.value = entry
            refreshToday()
        }
    }

    fun stopTimer() { scope.launch { stopTimerInternal(); refreshToday() } }

    private suspend fun stopTimerInternal() {
        val running = _currentEntry.value ?: return
        val now = currentTimeMillis()
        val stopped = running.copy(endTime = now, durationMs = now - running.startTime, isRunning = false)
        repository.updateTimeEntry(stopped)
        _currentEntry.value = null
    }

    fun addManualEntry(projectId: String, taskDescription: String, date: String, durationMs: Long) {
        scope.launch {
            val start = dateStringToEpoch(date)
            val entry = TimeEntry(
                id = generateId(), projectId = projectId, taskDescription = taskDescription,
                startTime = start, endTime = start + durationMs, durationMs = durationMs,
                date = date, isRunning = false
            )
            repository.saveTimeEntry(entry)
            refreshToday()
        }
    }

    fun logMeetingTime(projectId: String, recordingId: String, title: String, durationMs: Long, date: String) {
        scope.launch {
            val start = dateStringToEpoch(date)
            val entry = TimeEntry(
                id = generateId(), projectId = projectId, taskDescription = title,
                startTime = start, endTime = start + durationMs, durationMs = durationMs,
                date = date, isRunning = false, linkedRecordingId = recordingId
            )
            repository.saveTimeEntry(entry)
            refreshToday()
        }
    }

    fun deleteEntry(id: String) {
        scope.launch {
            repository.deleteTimeEntry(id)
            if (_currentEntry.value?.id == id) _currentEntry.value = null
            refreshToday()
        }
    }

    fun addProject(name: String, isBillable: Boolean = true, isDistributed: Boolean = false, color: String = "#4A90D9") {
        scope.launch {
            repository.saveProject(Project(id = generateId(), name = name, isBillable = isBillable, isDistributed = isDistributed, color = color))
            _projects.value = repository.getProjects()
        }
    }

    fun deleteProject(id: String) {
        scope.launch { repository.deleteProject(id); _projects.value = repository.getProjects() }
    }

    suspend fun getWeeklyTimesheet(dateInWeek: String): WeeklyTimesheet {
        val (monday, sunday) = getWeekBounds(dateInWeek)
        val entries = repository.getTimeEntries(monday, sunday)
        val projects = repository.getProjects()
        val projectMap = projects.associateBy { it.id }
        val now = currentTimeMillis()
        val dates = generateDateRange(monday, sunday)

        val rows = entries.groupBy { it.projectId }.mapNotNull { (pid, projEntries) ->
            val project = projectMap[pid] ?: return@mapNotNull null
            val hpd = mutableMapOf<String, Double>()
            projEntries.forEach { e -> hpd[e.date] = (hpd[e.date] ?: 0.0) + e.effectiveHours(now) }
            TimesheetRow(project, hpd, hpd.values.sum())
        }.sortedByDescending { it.totalHours }

        val dailyTotals = dates.associateWith { d -> rows.sumOf { it.hoursPerDay[d] ?: 0.0 } }
        return WeeklyTimesheet(monday, sunday, rows, dailyTotals, rows.sumOf { it.totalHours }, _reminderSettings.value.targetWeeklyHours)
    }

    suspend fun previewDistribution(dateInWeek: String): List<DistributionPreview> {
        val (monday, sunday) = getWeekBounds(dateInWeek)
        val entries = repository.getTimeEntries(monday, sunday)
        val projects = repository.getProjects()
        val projectMap = projects.associateBy { it.id }
        val now = currentTimeMillis()

        val billableHours = mutableMapOf<String, Double>()
        entries.forEach { e ->
            val p = projectMap[e.projectId] ?: return@forEach
            if (p.isBillable && !p.isDistributed) billableHours[p.id] = (billableHours[p.id] ?: 0.0) + e.effectiveHours(now)
        }
        val totalBillable = billableHours.values.sum()
        if (totalBillable == 0.0) return emptyList()

        return projects.filter { it.isDistributed }.mapNotNull { distProj ->
            val distHours = entries.filter { it.projectId == distProj.id }.sumOf { it.effectiveHours(now) }
            if (distHours == 0.0) return@mapNotNull null
            val allocs = projects.filter { it.isBillable && !it.isDistributed }.mapNotNull { bp ->
                val h = billableHours[bp.id] ?: return@mapNotNull null
                val add = distHours * (h / totalBillable)
                DistributionAllocation(bp, h, add, h + add)
            }
            DistributionPreview(distProj.name, distHours, allocs)
        }
    }

    suspend fun exportCsv(dateInWeek: String): String {
        val ts = getWeeklyTimesheet(dateInWeek)
        val dates = generateDateRange(ts.weekStartDate, ts.weekEndDate)
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val sb = StringBuilder()
        sb.appendLine("Project,${days.joinToString(",")},Total")
        ts.rows.forEach { r ->
            val hrs = dates.map { String.format("%.2f", r.hoursPerDay[it] ?: 0.0) }
            sb.appendLine("${r.project.name},${hrs.joinToString(",")},${String.format("%.2f", r.totalHours)}")
        }
        val totals = dates.map { String.format("%.2f", ts.dailyTotals[it] ?: 0.0) }
        sb.appendLine("TOTAL,${totals.joinToString(",")},${String.format("%.2f", ts.grandTotal)}")
        return sb.toString()
    }

    fun saveReminderSettings(settings: TimeReminderSettings) {
        scope.launch { repository.saveReminderSettings(settings); _reminderSettings.value = settings }
    }

    private suspend fun refreshToday() {
        _todayEntries.value = repository.getTimeEntriesForDate(epochToDateString(currentTimeMillis()))
    }

    private fun getWeekBounds(dateStr: String): Pair<String, String> {
        val dow = dayOfWeekISO(dateStr)
        val monday = addDays(dateStr, -dow)
        val sunday = addDays(dateStr, 6 - dow)
        return Pair(monday, sunday)
    }

    private fun dayOfWeekISO(dateStr: String): Int {
        val (y0, m0, d) = dateStr.split("-").map { it.toInt() }
        var y = y0; var m = m0
        if (m < 3) { m += 12; y-- }
        val h = (d + (13 * (m + 1)) / 5 + y % 100 + (y % 100) / 4 + (y / 100) / 4 - 2 * (y / 100)) % 7
        return (h + 5) % 7 // 0=Mon, 6=Sun
    }

    private fun addDays(dateStr: String, offset: Int): String {
        val parts = dateStr.split("-").map { it.toInt() }.toMutableList()
        var y = parts[0]; var m = parts[1]; var d = parts[2]
        d += offset
        val dim = { yr: Int, mo: Int -> when (mo) { 2 -> if (yr % 4 == 0 && (yr % 100 != 0 || yr % 400 == 0)) 29 else 28; 4,6,9,11 -> 30; else -> 31 } }
        while (d > dim(y, m)) { d -= dim(y, m); m++; if (m > 12) { m = 1; y++ } }
        while (d < 1) { m--; if (m < 1) { m = 12; y-- }; d += dim(y, m) }
        return String.format("%04d-%02d-%02d", y, m, d)
    }

    private fun generateDateRange(start: String, end: String): List<String> {
        val dates = mutableListOf(start)
        var cur = start
        repeat(6) { cur = addDays(cur, 1); dates.add(cur) }
        return dates
    }

    companion object {
        fun epochToDateString(epochMs: Long): String {
            val days = (epochMs / 86_400_000).toInt()
            var y = 1970; var rem = days
            while (true) {
                val diy = if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
                if (rem < diy) break; rem -= diy; y++
            }
            val dim = intArrayOf(31, if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            var m = 0
            while (rem >= dim[m]) { rem -= dim[m]; m++ }
            return String.format("%04d-%02d-%02d", y, m + 1, rem + 1)
        }

        fun dateStringToEpoch(date: String): Long {
            val (y, m, d) = date.split("-").map { it.toInt() }
            var days = 0L
            for (yr in 1970 until y) days += if (yr % 4 == 0 && (yr % 100 != 0 || yr % 400 == 0)) 366 else 365
            val dim = intArrayOf(31, if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            for (mo in 0 until m - 1) days += dim[mo]
            days += d - 1
            return days * 86_400_000
        }
    }
}
