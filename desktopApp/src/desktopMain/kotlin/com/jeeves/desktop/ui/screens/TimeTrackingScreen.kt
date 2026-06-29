package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeeves.shared.domain.*
import com.jeeves.shared.time.TimeTrackingManager
import com.jeeves.desktop.time.ReminderType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeTrackingScreen() {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    val currentEntry by appState.timeManager.currentEntry.collectAsState()
    val projects by appState.timeManager.projects.collectAsState()
    val todayEntries by appState.timeManager.todayEntries.collectAsState()
    val reminder by appState.reminderService.pendingReminder.collectAsState()

    // Week navigation state
    var weekOffset by remember { mutableStateOf(0) } // 0 = current, -1 = last week, +1 = next week, etc.
    var currentWeekDate by remember { mutableStateOf(TimeTrackingManager.epochToDateString(System.currentTimeMillis())) }

    var weeklyTimesheet by remember { mutableStateOf<WeeklyTimesheet?>(null) }
    var weeklyBurndown by remember { mutableStateOf<WeeklyBurndown?>(null) }
    var weeklyPlan by remember { mutableStateOf<WeeklyPlan?>(null) }
    var showAddProject by remember { mutableStateOf(false) }
    var showDistribution by remember { mutableStateOf(false) }
    var showPlanEditor by remember { mutableStateOf(false) }
    var distributionPreview by remember { mutableStateOf<List<DistributionPreview>>(emptyList()) }
    var timerTick by remember { mutableStateOf(0L) }

    // Tab state: Timesheet | Burndown | Plan
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(currentEntry) {
        while (isActive && currentEntry?.isRunning == true) { delay(1000); timerTick = System.currentTimeMillis() }
    }

    // Refresh data when week changes or entries change
    LaunchedEffect(weekOffset, todayEntries) {
        val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        currentWeekDate = if (weekOffset == 0) today else {
            // Navigate by weekOffset * 7 days
            val baseEpoch = System.currentTimeMillis() + (weekOffset.toLong() * 7 * 86_400_000)
            TimeTrackingManager.epochToDateString(baseEpoch)
        }
        weeklyTimesheet = appState.timeManager.getWeeklyTimesheet(currentWeekDate)
        weeklyBurndown = appState.timeManager.getWeeklyBurndown(currentWeekDate)
        weeklyPlan = appState.timeManager.getWeeklyPlan(currentWeekDate)
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        // Reminder
        reminder?.let { n ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = if (n.type == ReminderType.IDLE) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (n.type == ReminderType.IDLE) Icons.Filled.AccessTime else Icons.Filled.Timer, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(n.message, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { appState.reminderService.dismiss() }) { Text("Dismiss") }
                }
            }
        }

        // Timer bar
        TimerBar(currentEntry, projects, timerTick,
            onStart = { pid, desc -> appState.timeManager.startTimer(pid, desc) },
            onStop = { appState.timeManager.stopTimer() },
            onAddProject = { showAddProject = true }
        )

        Spacer(Modifier.height(16.dp))

        // Week navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { weekOffset-- }) { Icon(Icons.Filled.ChevronLeft, "Previous week") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = weeklyTimesheet?.let { "Week: ${it.weekStartDate} → ${it.weekEndDate}" } ?: "Loading...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (weekOffset != 0) {
                    TextButton(onClick = { weekOffset = 0 }) { Text("Today", style = MaterialTheme.typography.labelSmall) }
                }
            }
            IconButton(onClick = { if (weekOffset < 4) weekOffset++ }) { Icon(Icons.Filled.ChevronRight, "Next week") }
        }

        Spacer(Modifier.height(8.dp))

        // Tabs: Timesheet | Burndown | Plan | Work Plan
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Timesheet") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Burndown") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Plan") })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Work Plan") })
        }

        Spacer(Modifier.height(12.dp))

        // Tab content
        when (selectedTab) {
            0 -> TimesheetTab(weeklyTimesheet, scope, appState, currentWeekDate, distributionPreview, showDistribution,
                onShowDistribution = { preview, show -> distributionPreview = preview; showDistribution = show })
            1 -> BurndownTab(weeklyBurndown, projects)
            2 -> PlanTab(weeklyPlan, projects, scope, appState, currentWeekDate, weekOffset,
                onPlanSaved = { scope.launch { weeklyPlan = appState.timeManager.getWeeklyPlan(currentWeekDate) } })
            3 -> WorkPlanTab(projects, scope, appState, currentWeekDate, weeklyPlan)
        }
    }

    if (showAddProject) AddProjectDialog({ showAddProject = false }) { name, billable, dist ->
        appState.timeManager.addProject(name, isBillable = billable, isDistributed = dist)
        showAddProject = false
    }

    if (showDistribution) AlertDialog(
        onDismissRequest = { showDistribution = false },
        title = { Text("Admin Time Distribution") },
        text = {
            Column { distributionPreview.forEach { p ->
                Text("${p.distributedProjectName}: ${String.format("%.1f", p.totalDistributedHours)}h", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                p.allocations.forEach { a -> Text("  ${a.project.name}: +${String.format("%.1f", a.additionalHours)}h → ${String.format("%.1f", a.newTotalHours)}h", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }}
        },
        confirmButton = { TextButton(onClick = { showDistribution = false }) { Text("Close") } }
    )
}

// ─── Timesheet Tab ──────────────────────────────────────────────────────────────

@Composable
private fun TimesheetTab(
    weeklyTimesheet: WeeklyTimesheet?,
    scope: kotlinx.coroutines.CoroutineScope,
    appState: AppState,
    currentWeekDate: String,
    distributionPreview: List<DistributionPreview>,
    showDistribution: Boolean,
    onShowDistribution: (List<DistributionPreview>, Boolean) -> Unit
) {
    // Click-through detail state
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var projectEntries by remember { mutableStateOf<List<TimeEntry>>(emptyList()) }

    // Load entries when a project row is clicked
    LaunchedEffect(selectedProjectId) {
        if (selectedProjectId != null) {
            projectEntries = appState.timeManager.getProjectEntriesForWeek(selectedProjectId!!, currentWeekDate)
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.6f)) {
            weeklyTimesheet?.let { ts ->
                TimesheetGrid(ts, onRowClick = { projectId -> selectedProjectId = projectId })
                Spacer(Modifier.height(12.dp))
                val progress = (ts.grandTotal / ts.targetHours).toFloat().coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.weight(1f).height(8.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${String.format("%.1f", ts.grandTotal)} / ${String.format("%.0f", ts.targetHours)}h", style = MaterialTheme.typography.labelSmall)
                }
            } ?: Text("No entries this week", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val csv = appState.timeManager.exportCsv(currentWeekDate)
                        val f = java.io.File(System.getProperty("user.home"), "Jeeves/timesheet_export.csv")
                        f.writeText(csv)
                        java.awt.Desktop.getDesktop().open(f)
                    }
                }) { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Export CSV") }

                OutlinedButton(onClick = {
                    scope.launch {
                        val preview = appState.timeManager.previewDistribution(currentWeekDate)
                        onShowDistribution(preview, preview.isNotEmpty())
                    }
                }) { Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Distribute Admin") }

                OutlinedButton(onClick = {
                    scope.launch { appState.timeManager.autoDistributeAdminHours(currentWeekDate) }
                }) { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Auto-Fill Shortfall") }
            }

            Spacer(Modifier.height(8.dp))

            // Weekly Export button
            var showExportDialog by remember { mutableStateOf(false) }
            var exportText by remember { mutableStateOf("") }

            Button(onClick = {
                scope.launch {
                    val generator = com.jeeves.shared.time.WeeklyExportGenerator()
                    val projects = appState.timeManager.projects.value
                    val settings = appState.timeManager.reminderSettings.value
                    val sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate)
                    val backlogByProject = projects.associate { p ->
                        p.id to appState.timeManager.getBacklog(p.id)
                    }
                    // Calculate Friday date for subject line
                    val friday = currentWeekDate // Will be adjusted by generator context
                    exportText = generator.generate(projects, sprintItems, backlogByProject, settings, currentWeekDate)
                    showExportDialog = true
                }
            }) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Generate Weekly Export")
            }

            if (showExportDialog) {
                WeeklyExportDialog(exportText) { showExportDialog = false }
            }
        }

        Spacer(Modifier.width(20.dp))

        // Today's entries
        Column(Modifier.weight(0.4f)) {
            val todayEntries by appState.timeManager.todayEntries.collectAsState()
            val projects by appState.timeManager.projects.collectAsState()
            var showManualEntry by remember { mutableStateOf(false) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { showManualEntry = true }, modifier = Modifier.height(30.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Log Time", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (todayEntries.isEmpty()) {
                Text("No entries today", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    items(todayEntries) { entry ->
                        val proj = projects.find { it.id == entry.projectId }
                        TodayItem(entry, proj) { appState.timeManager.deleteEntry(entry.id) }
                    }
                }
            }

            if (showManualEntry) {
                ManualTimeEntryDialog(
                    projects = projects,
                    onDismiss = { showManualEntry = false },
                    onSubmit = { projectId, description, date, durationMinutes ->
                        appState.timeManager.addManualEntry(projectId, description, date, durationMinutes * 60_000L)
                        showManualEntry = false
                    }
                )
            }
        }
    }

    // Project detail dialog (click-through from timesheet row)
    if (selectedProjectId != null) {
        val selectedProject = weeklyTimesheet?.rows?.find { it.project.id == selectedProjectId }?.project
        AlertDialog(
            onDismissRequest = { selectedProjectId = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedProject != null) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(hexToColor(selectedProject.color)))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("${selectedProject?.name ?: "Project"} — This Week")
                }
            },
            text = {
                if (projectEntries.isEmpty()) {
                    Text("No entries this week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(projectEntries.sortedBy { it.startTime }) { entry ->
                            Card(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            entry.taskDescription.ifEmpty { "(no description)" },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            fmtShort(entry.durationMs ?: 0),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row {
                                        Text(entry.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (entry.linkedRecordingId != null) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(Icons.Filled.Mic, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
                                            Spacer(Modifier.width(2.dp))
                                            Text("Linked recording", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedProjectId = null }) { Text("Close") } }
        )
    }
}

// ─── Burndown Tab ───────────────────────────────────────────────────────────────

@Composable
private fun BurndownTab(weeklyBurndown: WeeklyBurndown?, projects: List<Project>) {
    if (weeklyBurndown == null) {
        Text("No burndown data available. Set project targets in the Plan tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Overall burndown summary
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Weekly Burndown (${String.format("%.0f", weeklyBurndown.totalTarget)}h target)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BurndownStat("Logged", String.format("%.1f", weeklyBurndown.totalActual) + "h", MaterialTheme.colorScheme.primary)
                    BurndownStat("Remaining", String.format("%.1f", weeklyBurndown.totalRemaining) + "h", MaterialTheme.colorScheme.tertiary)
                    val pct = if (weeklyBurndown.totalTarget > 0) (weeklyBurndown.totalActual / weeklyBurndown.totalTarget * 100) else 0.0
                    BurndownStat("Progress", String.format("%.0f", pct) + "%", MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(16.dp))

                // Simple burndown chart
                BurndownChart(
                    dailyCumulative = weeklyBurndown.dailyCumulativeTotal,
                    dailyIdeal = weeklyBurndown.dailyIdealTotal,
                    target = weeklyBurndown.totalTarget,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Per-project burndown rows
        Text("By Project", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        LazyColumn {
            items(weeklyBurndown.projectBurndowns) { pb ->
                ProjectBurndownRow(pb)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BurndownStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProjectBurndownRow(pb: ProjectBurndown) {
    val progress = if (pb.targetHours > 0) (pb.actualHours / pb.targetHours).toFloat().coerceIn(0f, 1.2f) else 0f
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(hexToColor(pb.project.color)))
            Spacer(Modifier.width(8.dp))
            Text(pb.project.name, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = progress.coerceAtMost(1f),
                modifier = Modifier.weight(1f).height(6.dp),
                color = if (progress > 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text("${String.format("%.1f", pb.actualHours)} / ${String.format("%.1f", pb.targetHours)}h",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(80.dp))
        }
    }
}

/**
 * Simple line chart showing actual cumulative hours vs ideal burndown line.
 */
@Composable
private fun BurndownChart(
    dailyCumulative: Map<String, Double>,
    dailyIdeal: Map<String, Double>,
    target: Double,
    modifier: Modifier = Modifier
) {
    val days = dailyCumulative.keys.sorted()
    if (days.isEmpty()) return

    val actualColor = Color(0xFF2196F3)
    val idealColor = Color(0xFFFF9800)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxY = target * 1.1f  // 10% headroom
        val padding = 4f

        val xStep = if (days.size > 1) (w - padding * 2) / (days.size - 1) else w
        val yScale = (h - padding * 2) / maxY.toFloat()

        // Draw ideal line (dashed)
        val idealPath = Path()
        days.forEachIndexed { i, day ->
            val x = padding + i * xStep
            val y = h - padding - ((dailyIdeal[day] ?: 0.0).toFloat() * yScale)
            if (i == 0) idealPath.moveTo(x, y) else idealPath.lineTo(x, y)
        }
        drawPath(idealPath, idealColor.copy(alpha = 0.6f), style = Stroke(width = 2f))

        // Draw actual line (solid)
        val actualPath = Path()
        days.forEachIndexed { i, day ->
            val x = padding + i * xStep
            val y = h - padding - ((dailyCumulative[day] ?: 0.0).toFloat() * yScale)
            if (i == 0) actualPath.moveTo(x, y) else actualPath.lineTo(x, y)
        }
        drawPath(actualPath, actualColor, style = Stroke(width = 3f))

        // Draw dots on actual
        days.forEachIndexed { i, day ->
            val x = padding + i * xStep
            val y = h - padding - ((dailyCumulative[day] ?: 0.0).toFloat() * yScale)
            drawCircle(actualColor, radius = 4f, center = Offset(x, y))
        }

        // Target line
        val targetY = h - padding - (target.toFloat() * yScale)
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, targetY), Offset(w, targetY), strokeWidth = 1f)
    }

    // Legend
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(10.dp, 3.dp).background(Color(0xFF2196F3)))
        Spacer(Modifier.width(4.dp))
        Text("Actual", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(10.dp, 3.dp).background(Color(0xFFFF9800)))
        Spacer(Modifier.width(4.dp))
        Text("Ideal", style = MaterialTheme.typography.labelSmall)
    }
}

// ─── Plan Tab ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanTab(
    weeklyPlan: WeeklyPlan?,
    projects: List<Project>,
    scope: kotlinx.coroutines.CoroutineScope,
    appState: AppState,
    currentWeekDate: String,
    weekOffset: Int,
    onPlanSaved: () -> Unit
) {
    val billableProjects = projects.filter { !it.isDistributed }
    var editTargets by remember(weeklyPlan) {
        mutableStateOf(
            billableProjects.associate { proj ->
                proj.id to (weeklyPlan?.targets?.find { it.projectId == proj.id }?.targetHours ?: proj.defaultTargetHours)
            }
        )
    }

    val totalPlanned = editTargets.values.sum()
    val totalTarget = weeklyPlan?.totalTargetHours ?: 40.0

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Project Hour Targets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${String.format("%.1f", totalPlanned)} / ${String.format("%.0f", totalTarget)}h planned",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (totalPlanned > totalTarget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(12.dp))

                billableProjects.forEach { project ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(hexToColor(project.color)))
                        Spacer(Modifier.width(8.dp))
                        Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = String.format("%.1f", editTargets[project.id] ?: 0.0),
                            onValueChange = { text ->
                                text.toDoubleOrNull()?.let { editTargets = editTargets + (project.id to it) }
                            },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            suffix = { Text("h", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val targets = editTargets.filter { it.value > 0 }.map { (pid, hrs) -> ProjectWeeklyTarget(pid, hrs) }
                            val plan = WeeklyPlan(
                                weekStartDate = weeklyPlan?.weekStartDate ?: currentWeekDate,
                                targets = targets,
                                totalTargetHours = totalTarget
                            )
                            appState.timeManager.saveWeeklyPlan(plan)
                            onPlanSaved()
                        }
                    }) { Text("Save Plan") }

                    if (weekOffset <= 0) {
                        // Only show "Copy to next weeks" for current or past weeks
                        OutlinedButton(onClick = {
                            scope.launch {
                                val targets = editTargets.filter { it.value > 0 }.map { (pid, hrs) -> ProjectWeeklyTarget(pid, hrs) }
                                // Copy plan to next 4 weeks
                                for (i in 1..4) {
                                    val futureEpoch = System.currentTimeMillis() + ((weekOffset + i).toLong() * 7 * 86_400_000)
                                    val futureDate = TimeTrackingManager.epochToDateString(futureEpoch)
                                    val plan = WeeklyPlan(weekStartDate = futureDate, targets = targets, totalTargetHours = totalTarget)
                                    appState.timeManager.saveWeeklyPlan(plan)
                                }
                                onPlanSaved()
                            }
                        }) { Text("Copy to next 4 weeks") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Forward planning overview (4 weeks)
        Text("Forward Plan Overview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        var allPlans by remember { mutableStateOf<List<WeeklyPlan>>(emptyList()) }
        LaunchedEffect(weeklyPlan) { allPlans = appState.timeManager.getAllWeeklyPlans() }

        if (allPlans.isEmpty()) {
            Text("No forward plans set. Use the form above to plan project hours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Week", Modifier.weight(1.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        billableProjects.take(5).forEach { p ->
                            Text(p.name.take(8), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Divider(Modifier.padding(vertical = 4.dp))
                    allPlans.sortedBy { it.weekStartDate }.takeLast(8).forEach { plan ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(plan.weekStartDate.substring(5), Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
                            billableProjects.take(5).forEach { p ->
                                val h = plan.targets.find { it.projectId == p.id }?.targetHours ?: 0.0
                                Text(if (h > 0) String.format("%.0f", h) else "-", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(String.format("%.0f", plan.targets.sumOf { it.targetHours }), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ─── Manual Time Entry Dialog ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTimeEntryDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onSubmit: (projectId: String, description: String, date: String, durationMinutes: Int) -> Unit
) {
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id ?: "") }
    var description by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis())) }
    var projExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Time Manually") },
        text = {
            Column {
                // Project picker
                ExposedDropdownMenuBox(expanded = projExpanded, onExpandedChange = { projExpanded = it }) {
                    OutlinedTextField(
                        value = projects.find { it.id == selectedProjectId }?.name ?: "Select project",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Project") }, modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projExpanded) }
                    )
                    ExposedDropdownMenu(projExpanded, { projExpanded = false }) {
                        projects.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { selectedProjectId = p.id; projExpanded = false },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(hexToColor(p.color))) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("What did you work on?") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hours, onValueChange = { hours = it },
                        label = { Text("Hours") }, modifier = Modifier.width(80.dp), singleLine = true
                    )
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it },
                        label = { Text("Minutes") }, modifier = Modifier.width(90.dp), singleLine = true
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it },
                    label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("2026-06-19") }
                )
                Text("Use a past date to log time retroactively", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hours.toIntOrNull() ?: 0
                val m = minutes.toIntOrNull() ?: 0
                val totalMinutes = h * 60 + m
                if (selectedProjectId.isNotBlank() && totalMinutes > 0 && dateText.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    onSubmit(selectedProjectId, description, dateText, totalMinutes)
                }
            }) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Weekly Export Dialog ────────────────────────────────────────────────────────

@Composable
private fun WeeklyExportDialog(exportText: String, onDismiss: () -> Unit) {
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Weekly Export for Gemini")
            }
        },
        text = {
            Column {
                Text(
                    "Copy this prompt and paste into Gemini to generate your weekly summary emails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = exportText,
                        modifier = Modifier.padding(12.dp).fillMaxWidth()
                            .verticalScroll(scrollState),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(exportText), null)
                    copied = true
                }
            ) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied!" else "Copy to Clipboard")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ─── Work Plan Tab ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkPlanTab(
    projects: List<Project>,
    scope: kotlinx.coroutines.CoroutineScope,
    appState: AppState,
    currentWeekDate: String,
    weeklyPlan: WeeklyPlan?
) {
    val billableProjects = projects.filter { !it.isDistributed }
    var selectedProjectId by remember { mutableStateOf(billableProjects.firstOrNull()?.id ?: "") }
    var backlogItems by remember { mutableStateOf<List<BacklogItem>>(emptyList()) }
    var sprintItems by remember { mutableStateOf<List<SprintItem>>(emptyList()) }
    var showAddItem by remember { mutableStateOf(false) }

    val activeSprintItem by appState.timeManager.activeSprintItem.collectAsState()
    var timerTick by remember { mutableStateOf(0L) }

    // Refresh timer for active countdown
    LaunchedEffect(activeSprintItem) {
        while (isActive && activeSprintItem?.isActive == true) { delay(1000); timerTick = System.currentTimeMillis() }
    }

    // Load data when project or week changes
    LaunchedEffect(selectedProjectId, currentWeekDate) {
        if (selectedProjectId.isNotEmpty()) {
            backlogItems = appState.timeManager.getBacklog(selectedProjectId)
        }
        sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate)
    }

    Row(Modifier.fillMaxSize()) {
        // Left panel: Backlog + Sprint for selected project
        Column(Modifier.weight(0.55f)) {
            // Project selector
            var projExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(expanded = projExpanded, onExpandedChange = { projExpanded = it }, modifier = Modifier.width(220.dp)) {
                    OutlinedTextField(
                        value = billableProjects.find { it.id == selectedProjectId }?.name ?: "Select project",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Project") }, modifier = Modifier.menuAnchor(), singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projExpanded) }
                    )
                    ExposedDropdownMenu(projExpanded, { projExpanded = false }) {
                        billableProjects.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { selectedProjectId = p.id; projExpanded = false },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(hexToColor(p.color))) }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                val allocated = weeklyPlan?.targets?.find { it.projectId == selectedProjectId }?.targetHours ?: 0.0
                val sprintPlanned = sprintItems.filter { it.projectId == selectedProjectId }.sumOf { it.allocatedMinutes }
                Text("${String.format("%.0f", allocated)}h allocated · ${sprintPlanned / 60}h ${sprintPlanned % 60}m planned",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(12.dp))

            // Backlog section
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Backlog", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAddItem = true }, modifier = Modifier.height(32.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Add", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = {
                        appState.timeManager.autoFitBacklogToSprint(selectedProjectId, currentWeekDate)
                        scope.launch {
                            delay(200)
                            backlogItems = appState.timeManager.getBacklog(selectedProjectId)
                            sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate)
                        }
                    }, modifier = Modifier.height(32.dp)) {
                        Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Auto-Fit", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            val unplannedItems = backlogItems.filter { it.status == BacklogStatus.BACKLOG }
            if (unplannedItems.isEmpty()) {
                Text("No items in backlog. Add tasks to plan your work.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(unplannedItems) { item ->
                        BacklogItemRow(item,
                            onAccept = {
                                appState.timeManager.acceptIntoSprint(selectedProjectId, listOf(item.id), currentWeekDate)
                                scope.launch {
                                    delay(200)
                                    backlogItems = appState.timeManager.getBacklog(selectedProjectId)
                                    sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate)
                                }
                            },
                            onDelete = {
                                appState.timeManager.deleteBacklogItem(selectedProjectId, item.id)
                                scope.launch { delay(100); backlogItems = appState.timeManager.getBacklog(selectedProjectId) }
                            },
                            onMoveUp = {
                                val idx = unplannedItems.indexOf(item)
                                if (idx > 0) {
                                    appState.timeManager.reorderBacklog(selectedProjectId, item.id, idx - 1)
                                    scope.launch { delay(100); backlogItems = appState.timeManager.getBacklog(selectedProjectId) }
                                }
                            },
                            onMoveDown = {
                                val idx = unplannedItems.indexOf(item)
                                if (idx < unplannedItems.size - 1) {
                                    appState.timeManager.reorderBacklog(selectedProjectId, item.id, idx + 1)
                                    scope.launch { delay(100); backlogItems = appState.timeManager.getBacklog(selectedProjectId) }
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Right panel: Sprint (accepted items) + Active task countdown
        Column(Modifier.weight(0.45f)) {
            // Active task countdown
            activeSprintItem?.let { active ->
                val remaining = active.effectiveRemainingMinutes(timerTick)
                val isOverrun = remaining < 0
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOverrun) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Active Task", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(active.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (isOverrun) "-${fmtMins(-remaining)}" else fmtMins(remaining),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOverrun) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                                Text(if (isOverrun) "OVERRUN" else "remaining", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { appState.timeManager.stopSprintTask(currentWeekDate) }) {
                                Icon(Icons.Filled.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Pause")
                            }
                            FilledTonalButton(onClick = {
                                appState.timeManager.completeSprintTask(active.id, currentWeekDate)
                                scope.launch { delay(200); sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate) }
                            }) {
                                Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Done")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Sprint items for this week
            Text("This Week's Sprint", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            val projectSprint = sprintItems.filter { it.projectId == selectedProjectId }
            if (projectSprint.isEmpty()) {
                Text("No tasks planned. Accept items from the backlog or use Auto-Fit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    items(projectSprint) { item ->
                        SprintItemRow(item, timerTick,
                            onStart = {
                                appState.timeManager.startSprintTask(item.id, currentWeekDate)
                                scope.launch { delay(200); sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate) }
                            },
                            onComplete = {
                                appState.timeManager.completeSprintTask(item.id, currentWeekDate)
                                scope.launch { delay(200); sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate) }
                            },
                            onRemove = {
                                appState.timeManager.removeFromSprint(item.id, currentWeekDate)
                                scope.launch {
                                    delay(200)
                                    sprintItems = appState.timeManager.getSprintForWeek(currentWeekDate)
                                    backlogItems = appState.timeManager.getBacklog(selectedProjectId)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Add backlog item dialog
    if (showAddItem) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var estimate by remember { mutableStateOf("60") }
        AlertDialog(
            onDismissRequest = { showAddItem = false },
            title = { Text("Add Backlog Item") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(estimate, { estimate = it }, label = { Text("Estimate (minutes)") }, singleLine = true, modifier = Modifier.width(150.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        appState.timeManager.addBacklogItem(selectedProjectId, title, desc, estimate.toIntOrNull() ?: 60)
                        scope.launch { delay(200); backlogItems = appState.timeManager.getBacklog(selectedProjectId) }
                        showAddItem = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddItem = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BacklogItemRow(
    item: BacklogItem,
    onAccept: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Priority arrows
            Column {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(20.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", Modifier.size(14.dp)) }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(20.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", Modifier.size(14.dp)) }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
                if (item.description.isNotEmpty()) Text(item.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Text("${item.estimateMinutes}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onAccept, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ArrowForward, "Accept", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Delete", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
        }
    }
}

@Composable
private fun SprintItemRow(
    item: SprintItem,
    nowMs: Long,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    onRemove: () -> Unit
) {
    val remaining = item.effectiveRemainingMinutes(nowMs)
    val isOverrun = remaining < 0
    val isDone = item.elapsedMinutes >= item.allocatedMinutes && !item.isActive

    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                item.isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                isOverrun -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${item.elapsedMinutes}m / ${item.allocatedMinutes}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Remaining indicator
            Text(
                if (isOverrun) "-${fmtMins(-remaining)}" else fmtMins(remaining),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isOverrun) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            if (!item.isActive) {
                IconButton(onClick = onStart, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.PlayArrow, "Start", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            IconButton(onClick = onComplete, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.CheckCircle, "Done", Modifier.size(16.dp), tint = Color(0xFF4CAF50)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
        }
    }
}

private fun fmtMins(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ─── Shared Components ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerBar(currentEntry: TimeEntry?, projects: List<Project>, timerTick: Long,
                     onStart: (String, String) -> Unit, onStop: () -> Unit, onAddProject: () -> Unit) {
    var selectedId by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (currentEntry?.isRunning == true) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = {
                if (currentEntry?.isRunning == true) onStop() else if (selectedId.isNotEmpty()) { onStart(selectedId, desc); desc = "" }
            }, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (currentEntry?.isRunning == true) Color.Red else MaterialTheme.colorScheme.primary)
            ) { Icon(if (currentEntry?.isRunning == true) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(24.dp)) }

            Spacer(Modifier.width(12.dp))

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.width(180.dp)) {
                OutlinedTextField(value = projects.find { it.id == selectedId }?.name ?: "", onValueChange = {}, readOnly = true,
                    label = { Text("Project") }, modifier = Modifier.menuAnchor(), singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { selectedId = p.id; expanded = false },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(hexToColor(p.color))) }) }
                    DropdownMenuItem(text = { Text("+ Add Project") }, onClick = { expanded = false; onAddProject() },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
                }
            }

            Spacer(Modifier.width(12.dp))
            OutlinedTextField(value = if (currentEntry?.isRunning == true) currentEntry.taskDescription else desc,
                onValueChange = { if (currentEntry?.isRunning != true) desc = it },
                label = { Text("What are you working on?") }, modifier = Modifier.weight(1f), singleLine = true,
                readOnly = currentEntry?.isRunning == true)

            Spacer(Modifier.width(12.dp))
            val dur = if (currentEntry?.isRunning == true) currentEntry.effectiveDurationMs(timerTick) else 0L
            Text(fmtTimer(dur), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = if (currentEntry?.isRunning == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimesheetGrid(ts: WeeklyTimesheet, onRowClick: (String) -> Unit = {}) {
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val dates = buildList { var d = ts.weekStartDate; repeat(7) { add(d); d = addDay(d) } }

    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Project", Modifier.weight(2f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                dayNames.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Divider(Modifier.padding(vertical = 4.dp))
            ts.rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onRowClick(row.project.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(hexToColor(row.project.color)))
                        Spacer(Modifier.width(4.dp))
                        Text(row.project.name + if (row.project.isDistributed) " *" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    dates.forEach { d -> val h = row.hoursPerDay[d] ?: 0.0; Text(if (h > 0) String.format("%.1f", h) else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                    Text(String.format("%.1f", row.totalHours), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Total", Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                dates.forEach { d -> val t = ts.dailyTotals[d] ?: 0.0; Text(if (t > 0) String.format("%.1f", t) else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                Text(String.format("%.1f", ts.grandTotal), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TodayItem(entry: TimeEntry, project: Project?, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (entry.isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (project != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(hexToColor(project.color))); Spacer(Modifier.width(6.dp)); Text(project.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
            if (entry.taskDescription.isNotEmpty()) Text(" - ${entry.taskDescription}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (entry.isRunning) Text("●", color = Color.Red) else Text(fmtShort(entry.durationMs ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
        }
    }
}

@Composable
private fun AddProjectDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var billable by remember { mutableStateOf(true) }
    var distributed by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add Project") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Project Name") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(billable, { billable = it }); Text("Billable") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(distributed, { distributed = it; if (it) billable = false }); Text("Distribute (Email/Admin)") }
        }},
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onAdd(name, billable, distributed) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun hexToColor(hex: String): Color { return try { val c = hex.removePrefix("#").toLong(16); Color(((c shr 16) and 0xFF).toInt(), ((c shr 8) and 0xFF).toInt(), (c and 0xFF).toInt()) } catch (_: Exception) { Color.Gray } }
private fun fmtTimer(ms: Long): String { val s = ms / 1000; return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) }
private fun fmtShort(ms: Long): String { val m = ms / 60000; val h = m / 60; return if (h > 0) "${h}h ${m % 60}m" else "${m}m" }
private fun addDay(d: String): String { val p = d.split("-").map { it.toInt() }; var y = p[0]; var m = p[1]; var day = p[2]; val dim = intArrayOf(0,31,28,31,30,31,30,31,31,30,31,30,31); if (y%4==0&&(y%100!=0||y%400==0)) dim[2]=29; day++; if (day>dim[m]){day=1;m++}; if (m>12){m=1;y++}; return String.format("%04d-%02d-%02d",y,m,day) }
