package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    var weeklyTimesheet by remember { mutableStateOf<WeeklyTimesheet?>(null) }
    var showAddProject by remember { mutableStateOf(false) }
    var showDistribution by remember { mutableStateOf(false) }
    var distributionPreview by remember { mutableStateOf<List<DistributionPreview>>(emptyList()) }
    var timerTick by remember { mutableStateOf(0L) }

    LaunchedEffect(currentEntry) {
        while (isActive && currentEntry?.isRunning == true) { delay(1000); timerTick = System.currentTimeMillis() }
    }

    LaunchedEffect(todayEntries) {
        val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        weeklyTimesheet = appState.timeManager.getWeeklyTimesheet(today)
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

        Spacer(Modifier.height(20.dp))

        Row(Modifier.weight(1f)) {
            // Timesheet
            Column(Modifier.weight(0.6f)) {
                Text("Weekly Timesheet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                weeklyTimesheet?.let { ts ->
                    TimesheetGrid(ts)
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
                            val csv = appState.timeManager.exportCsv(TimeTrackingManager.epochToDateString(System.currentTimeMillis()))
                            val f = java.io.File(System.getProperty("user.home"), "Jeeves/timesheet_export.csv")
                            f.writeText(csv)
                            java.awt.Desktop.getDesktop().open(f)
                        }
                    }) { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Export CSV") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            distributionPreview = appState.timeManager.previewDistribution(TimeTrackingManager.epochToDateString(System.currentTimeMillis()))
                            showDistribution = distributionPreview.isNotEmpty()
                        }
                    }) { Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Distribute Admin Time") }
                }
            }

            Spacer(Modifier.width(20.dp))

            // Today
            Column(Modifier.weight(0.4f)) {
                Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            }
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
private fun TimesheetGrid(ts: WeeklyTimesheet) {
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
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
