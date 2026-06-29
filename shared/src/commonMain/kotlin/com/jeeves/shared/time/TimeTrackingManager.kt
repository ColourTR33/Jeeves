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

    // ───────────────────────────────────────────────────────────────────────────
    // Weekly Planning & Burndown
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Save a weekly plan (per-project target hours for a given week).
     * weekStartDate must be a Monday.
     */
    fun saveWeeklyPlan(plan: WeeklyPlan) {
        scope.launch { repository.saveWeeklyPlan(plan) }
    }

    /**
     * Get the weekly plan for the week containing [dateInWeek].
     * Falls back to building a plan from project defaults if no explicit plan exists.
     */
    suspend fun getWeeklyPlan(dateInWeek: String): WeeklyPlan {
        val (monday, _) = getWeekBounds(dateInWeek)
        val explicit = repository.getWeeklyPlan(monday)
        if (explicit != null) return explicit

        // Build default plan from project defaultTargetHours
        val projects = repository.getProjects()
        val targets = projects
            .filter { it.defaultTargetHours > 0 && !it.isDistributed }
            .map { ProjectWeeklyTarget(it.id, it.defaultTargetHours) }
        return WeeklyPlan(
            weekStartDate = monday,
            targets = targets,
            totalTargetHours = _reminderSettings.value.targetWeeklyHours
        )
    }

    /**
     * Delete the explicit weekly plan for the given week. Reverts to defaults.
     */
    fun deleteWeeklyPlan(dateInWeek: String) {
        scope.launch {
            val (monday, _) = getWeekBounds(dateInWeek)
            repository.deleteWeeklyPlan(monday)
        }
    }

    /**
     * Get all saved weekly plans (for the forward planning grid).
     */
    suspend fun getAllWeeklyPlans(): List<WeeklyPlan> = repository.getAllWeeklyPlans()

    /**
     * Calculate the burndown for the week containing [dateInWeek].
     * Shows target vs actual per project and overall, with daily cumulative lines.
     */
    suspend fun getWeeklyBurndown(dateInWeek: String): WeeklyBurndown {
        val (monday, sunday) = getWeekBounds(dateInWeek)
        val entries = repository.getTimeEntries(monday, sunday)
        val projects = repository.getProjects()
        val projectMap = projects.associateBy { it.id }
        val plan = getWeeklyPlan(dateInWeek)
        val now = currentTimeMillis()

        // Working days: Mon-Fri (first 5 days of the week)
        val workDays = (0..4).map { addDays(monday, it) }

        // Build per-project burndowns
        val projectBurndowns = plan.targets.mapNotNull { target ->
            val project = projectMap[target.projectId] ?: return@mapNotNull null
            val projEntries = entries.filter { it.projectId == target.projectId }

            // Daily cumulative actual hours
            var cumulative = 0.0
            val dailyCumulative = mutableMapOf<String, Double>()
            for (day in workDays) {
                val dayHours = projEntries
                    .filter { it.date == day }
                    .sumOf { it.effectiveHours(now) }
                cumulative += dayHours
                dailyCumulative[day] = cumulative
            }

            // Ideal burndown: evenly distributed target across 5 work days
            val dailyIdeal = mutableMapOf<String, Double>()
            val idealPerDay = target.targetHours / 5.0
            for ((i, day) in workDays.withIndex()) {
                dailyIdeal[day] = idealPerDay * (i + 1)
            }

            ProjectBurndown(
                project = project,
                targetHours = target.targetHours,
                actualHours = cumulative,
                remainingHours = (target.targetHours - cumulative).coerceAtLeast(0.0),
                dailyCumulative = dailyCumulative,
                dailyIdeal = dailyIdeal
            )
        }

        // Overall totals
        val totalTarget = plan.totalTargetHours
        val dailyCumulativeTotal = mutableMapOf<String, Double>()
        val dailyIdealTotal = mutableMapOf<String, Double>()
        val idealPerDayTotal = totalTarget / 5.0

        for ((i, day) in workDays.withIndex()) {
            dailyCumulativeTotal[day] = projectBurndowns.sumOf { it.dailyCumulative[day] ?: 0.0 }
            dailyIdealTotal[day] = idealPerDayTotal * (i + 1)
        }

        val totalActual = projectBurndowns.sumOf { it.actualHours }

        return WeeklyBurndown(
            weekStartDate = monday,
            totalTarget = totalTarget,
            totalActual = totalActual,
            totalRemaining = (totalTarget - totalActual).coerceAtLeast(0.0),
            projectBurndowns = projectBurndowns,
            dailyCumulativeTotal = dailyCumulativeTotal,
            dailyIdealTotal = dailyIdealTotal
        )
    }

    /**
     * Auto-distribute admin/distributed project hours to billable projects
     * that are below their weekly target. Called on Friday (or manually).
     *
     * Logic: For each distributed project (e.g. "Admin", "Email"), take its total
     * hours for the week and split them evenly across billable projects that still
     * have remaining target hours. Creates new time entries attributed to those projects.
     *
     * Returns the list of new entries created (for undo/confirmation).
     */
    suspend fun autoDistributeAdminHours(dateInWeek: String): List<TimeEntry> {
        val (monday, sunday) = getWeekBounds(dateInWeek)
        val entries = repository.getTimeEntries(monday, sunday)
        val projects = repository.getProjects()
        val projectMap = projects.associateBy { it.id }
        val plan = getWeeklyPlan(dateInWeek)
        val now = currentTimeMillis()

        // Find billable projects that are under target
        val targetMap = plan.targets.associate { it.projectId to it.targetHours }
        val billableShortfall = mutableMapOf<String, Double>() // projectId -> remaining hours

        for (target in plan.targets) {
            val project = projectMap[target.projectId] ?: continue
            if (!project.isBillable || project.isDistributed) continue
            val actual = entries.filter { it.projectId == target.projectId }.sumOf { it.effectiveHours(now) }
            val remaining = target.targetHours - actual
            if (remaining > 0.1) { // At least 6 minutes shortfall
                billableShortfall[target.projectId] = remaining
            }
        }

        if (billableShortfall.isEmpty()) return emptyList()

        // Find distributed project hours to redistribute
        val distributedProjects = projects.filter { it.isDistributed }
        val totalDistributedHours = distributedProjects.sumOf { dp ->
            entries.filter { it.projectId == dp.id }.sumOf { it.effectiveHours(now) }
        }

        if (totalDistributedHours < 0.1) return emptyList()

        // Distribute evenly across under-target projects
        val totalShortfall = billableShortfall.values.sum()
        val newEntries = mutableListOf<TimeEntry>()
        val today = epochToDateString(now)

        for ((projectId, shortfall) in billableShortfall) {
            // Proportional share of distributed hours based on shortfall ratio
            val share = totalDistributedHours * (shortfall / totalShortfall)
            val allocatedHours = minOf(share, shortfall) // Don't exceed target
            if (allocatedHours < 0.05) continue // Skip trivial amounts

            val durationMs = (allocatedHours * 3_600_000).toLong()
            val entry = TimeEntry(
                id = generateId(),
                projectId = projectId,
                taskDescription = "Admin time (auto-distributed)",
                startTime = dateStringToEpoch(today),
                endTime = dateStringToEpoch(today) + durationMs,
                durationMs = durationMs,
                date = today,
                isRunning = false
            )
            repository.saveTimeEntry(entry)
            newEntries.add(entry)
        }

        refreshToday()
        return newEntries
    }

    /**
     * Get entries for a specific project in a given week (for click-through detail view).
     */
    suspend fun getProjectEntriesForWeek(projectId: String, dateInWeek: String): List<TimeEntry> {
        val (monday, sunday) = getWeekBounds(dateInWeek)
        return repository.getTimeEntries(monday, sunday).filter { it.projectId == projectId }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Backlog & Sprint Planning
    // ───────────────────────────────────────────────────────────────────────────

    private val _activeSprintItem = MutableStateFlow<SprintItem?>(null)
    val activeSprintItem: StateFlow<SprintItem?> = _activeSprintItem.asStateFlow()

    /**
     * Add a new item to a project's backlog.
     */
    fun addBacklogItem(projectId: String, title: String, description: String = "", estimateMinutes: Int = 60) {
        scope.launch {
            val items = repository.getBacklogItems(projectId).toMutableList()
            val maxPriority = items.maxOfOrNull { it.priority } ?: 0
            items.add(BacklogItem(
                id = generateId(),
                projectId = projectId,
                title = title,
                description = description,
                priority = maxPriority + 1,
                estimateMinutes = estimateMinutes,
                status = BacklogStatus.BACKLOG,
                createdAt = currentTimeMillis()
            ))
            repository.saveBacklogItems(projectId, items)
        }
    }

    /**
     * Update a backlog item (edit title, description, estimate, priority).
     */
    fun updateBacklogItem(item: BacklogItem) {
        scope.launch {
            val items = repository.getBacklogItems(item.projectId).toMutableList()
            val i = items.indexOfFirst { it.id == item.id }
            if (i >= 0) { items[i] = item; repository.saveBacklogItems(item.projectId, items) }
        }
    }

    /**
     * Delete a backlog item.
     */
    fun deleteBacklogItem(projectId: String, itemId: String) {
        scope.launch {
            val items = repository.getBacklogItems(projectId).filter { it.id != itemId }
            repository.saveBacklogItems(projectId, items)
        }
    }

    /**
     * Reorder backlog: move item to new priority position.
     * All items get re-numbered sequentially.
     */
    fun reorderBacklog(projectId: String, itemId: String, newIndex: Int) {
        scope.launch {
            val items = repository.getBacklogItems(projectId).sortedBy { it.priority }.toMutableList()
            val item = items.find { it.id == itemId } ?: return@launch
            items.remove(item)
            items.add(newIndex.coerceIn(0, items.size), item)
            // Re-number priorities
            val renumbered = items.mapIndexed { idx, it -> it.copy(priority = idx + 1) }
            repository.saveBacklogItems(projectId, renumbered)
        }
    }

    /**
     * Get the backlog for a project, sorted by priority.
     */
    suspend fun getBacklog(projectId: String): List<BacklogItem> {
        return repository.getBacklogItems(projectId).sortedBy { it.priority }
    }

    /**
     * Accept backlog items into the weekly sprint.
     * Takes the top N items from the backlog that fit within the project's allocated hours.
     * Items are moved from BACKLOG → PLANNED status.
     */
    fun acceptIntoSprint(projectId: String, itemIds: List<String>, weekStartDate: String) {
        scope.launch {
            val backlog = repository.getBacklogItems(projectId).toMutableList()
            val existingSprint = repository.getSprintItems(weekStartDate).toMutableList()

            for (itemId in itemIds) {
                val item = backlog.find { it.id == itemId } ?: continue
                // Update backlog status
                val idx = backlog.indexOfFirst { it.id == itemId }
                if (idx >= 0) backlog[idx] = item.copy(status = BacklogStatus.PLANNED)

                // Create sprint item (skip if already in sprint)
                if (existingSprint.none { it.backlogItemId == itemId }) {
                    existingSprint.add(SprintItem(
                        id = generateId(),
                        backlogItemId = itemId,
                        projectId = projectId,
                        weekStartDate = weekStartDate,
                        title = item.title,
                        allocatedMinutes = item.estimateMinutes
                    ))
                }
            }

            repository.saveBacklogItems(projectId, backlog)
            repository.saveSprintItems(weekStartDate, existingSprint)
        }
    }

    /**
     * Remove a sprint item (send back to backlog).
     */
    fun removeFromSprint(sprintItemId: String, weekStartDate: String) {
        scope.launch {
            val sprint = repository.getSprintItems(weekStartDate).toMutableList()
            val item = sprint.find { it.id == sprintItemId } ?: return@launch
            sprint.remove(item)
            repository.saveSprintItems(weekStartDate, sprint)

            // Revert backlog item to BACKLOG status
            val backlog = repository.getBacklogItems(item.projectId).toMutableList()
            val idx = backlog.indexOfFirst { it.id == item.backlogItemId }
            if (idx >= 0) {
                backlog[idx] = backlog[idx].copy(status = BacklogStatus.BACKLOG)
                repository.saveBacklogItems(item.projectId, backlog)
            }
        }
    }

    /**
     * Get sprint items for a given week.
     */
    suspend fun getSprintForWeek(weekStartDate: String): List<SprintItem> {
        return repository.getSprintItems(weekStartDate)
    }

    /**
     * Start working on a sprint item (begins the countdown timer).
     * Stops any currently-active sprint item first.
     */
    fun startSprintTask(sprintItemId: String, weekStartDate: String) {
        scope.launch {
            val sprint = repository.getSprintItems(weekStartDate).toMutableList()
            val now = currentTimeMillis()

            // Stop any currently active item
            val activeIdx = sprint.indexOfFirst { it.isActive }
            if (activeIdx >= 0) {
                val active = sprint[activeIdx]
                val elapsed = if (active.activeStartTime != null) {
                    ((now - active.activeStartTime) / 60_000).toInt()
                } else 0
                sprint[activeIdx] = active.copy(
                    isActive = false,
                    activeStartTime = null,
                    elapsedMinutes = active.elapsedMinutes + elapsed
                )
            }

            // Start the requested item
            val idx = sprint.indexOfFirst { it.id == sprintItemId }
            if (idx >= 0) {
                sprint[idx] = sprint[idx].copy(isActive = true, activeStartTime = now)
                _activeSprintItem.value = sprint[idx]

                // Update backlog status to IN_PROGRESS
                val item = sprint[idx]
                val backlog = repository.getBacklogItems(item.projectId).toMutableList()
                val bIdx = backlog.indexOfFirst { it.id == item.backlogItemId }
                if (bIdx >= 0) {
                    backlog[bIdx] = backlog[bIdx].copy(status = BacklogStatus.IN_PROGRESS)
                    repository.saveBacklogItems(item.projectId, backlog)
                }
            }

            repository.saveSprintItems(weekStartDate, sprint)
        }
    }

    /**
     * Stop the currently-active sprint task, recording elapsed time.
     */
    fun stopSprintTask(weekStartDate: String) {
        scope.launch {
            val sprint = repository.getSprintItems(weekStartDate).toMutableList()
            val now = currentTimeMillis()

            val activeIdx = sprint.indexOfFirst { it.isActive }
            if (activeIdx >= 0) {
                val active = sprint[activeIdx]
                val elapsed = if (active.activeStartTime != null) {
                    ((now - active.activeStartTime) / 60_000).toInt()
                } else 0
                sprint[activeIdx] = active.copy(
                    isActive = false,
                    activeStartTime = null,
                    elapsedMinutes = active.elapsedMinutes + elapsed
                )
                repository.saveSprintItems(weekStartDate, sprint)
            }

            _activeSprintItem.value = null
        }
    }

    /**
     * Mark a sprint item as done. Records final elapsed time and updates backlog status.
     */
    fun completeSprintTask(sprintItemId: String, weekStartDate: String) {
        scope.launch {
            val sprint = repository.getSprintItems(weekStartDate).toMutableList()
            val now = currentTimeMillis()

            val idx = sprint.indexOfFirst { it.id == sprintItemId }
            if (idx >= 0) {
                val item = sprint[idx]
                val elapsed = if (item.isActive && item.activeStartTime != null) {
                    ((now - item.activeStartTime) / 60_000).toInt()
                } else 0
                sprint[idx] = item.copy(
                    isActive = false,
                    activeStartTime = null,
                    elapsedMinutes = item.elapsedMinutes + elapsed
                )
                repository.saveSprintItems(weekStartDate, sprint)

                if (item.isActive) _activeSprintItem.value = null

                // Update backlog status to DONE
                val backlog = repository.getBacklogItems(item.projectId).toMutableList()
                val bIdx = backlog.indexOfFirst { it.id == item.backlogItemId }
                if (bIdx >= 0) {
                    backlog[bIdx] = backlog[bIdx].copy(status = BacklogStatus.DONE, completedAt = now)
                    repository.saveBacklogItems(item.projectId, backlog)
                }
            }
        }
    }

    /**
     * Auto-fit: take top-priority backlog items and accept them into the sprint
     * until the project's allocated hours for the week are filled.
     */
    fun autoFitBacklogToSprint(projectId: String, weekStartDate: String) {
        scope.launch {
            val plan = getWeeklyPlan(weekStartDate)
            val targetMinutes = ((plan.targets.find { it.projectId == projectId }?.targetHours ?: 0.0) * 60).toInt()
            if (targetMinutes <= 0) return@launch

            val existingSprint = repository.getSprintItems(weekStartDate)
            val alreadyPlannedMinutes = existingSprint.filter { it.projectId == projectId }.sumOf { it.allocatedMinutes }
            val remainingBudget = targetMinutes - alreadyPlannedMinutes
            if (remainingBudget <= 0) return@launch

            val backlog = repository.getBacklogItems(projectId).sortedBy { it.priority }
            val unplanned = backlog.filter { it.status == BacklogStatus.BACKLOG }

            var budget = remainingBudget
            val toAccept = mutableListOf<String>()
            for (item in unplanned) {
                if (item.estimateMinutes <= budget) {
                    toAccept.add(item.id)
                    budget -= item.estimateMinutes
                }
                if (budget <= 0) break
            }

            if (toAccept.isNotEmpty()) {
                acceptIntoSprint(projectId, toAccept, weekStartDate)
            }
        }
    }

    /**
     * Initialize active sprint item on app start (restore running countdown if any).
     */
    fun initializeActiveSprint(weekStartDate: String) {
        scope.launch {
            val sprint = repository.getSprintItems(weekStartDate)
            _activeSprintItem.value = sprint.find { it.isActive }
        }
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
