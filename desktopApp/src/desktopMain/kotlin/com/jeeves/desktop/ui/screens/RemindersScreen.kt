package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jeeves.desktop.data.FileReminderRepository
import com.jeeves.shared.domain.*
import com.jeeves.shared.recording.currentTimeMillis
import com.jeeves.shared.recording.generateId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen() {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    val projects by appState.timeManager.projects.collectAsState()

    val repository = remember { FileReminderRepository() }
    var reminders by remember { mutableStateOf(repository.getAll()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showConvertDialog by remember { mutableStateOf<Reminder?>(null) }

    fun refresh() { reminders = repository.getAll() }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Reminders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Reminder")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Split: open vs completed
        val openReminders = reminders.filter { it.status == ReminderStatus.OPEN }
            .sortedWith(compareBy<Reminder> { it.urgency.ordinal }.reversed().thenBy { it.dueAt })
        val doneReminders = reminders.filter { it.status != ReminderStatus.OPEN }
            .sortedByDescending { it.completedAt ?: it.createdAt }

        if (openReminders.isEmpty() && doneReminders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.NotificationsNone, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No reminders yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Open reminders
                if (openReminders.isNotEmpty()) {
                    item {
                        Text("Open (${openReminders.size})", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(openReminders, key = { it.id }) { reminder ->
                        ReminderCard(
                            reminder = reminder,
                            project = projects.find { it.id == reminder.projectId },
                            onComplete = {
                                repository.save(reminder.copy(
                                    status = ReminderStatus.DONE,
                                    completedAt = currentTimeMillis()
                                ))
                                refresh()
                            },
                            onDelete = { repository.delete(reminder.id); refresh() },
                            onConvertToBacklog = { showConvertDialog = reminder }
                        )
                    }
                }

                // Completed/converted
                if (doneReminders.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text("Done (${doneReminders.size})", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(doneReminders.take(20), key = { it.id }) { reminder ->
                        DoneReminderCard(
                            reminder = reminder,
                            project = projects.find { it.id == reminder.projectId },
                            onDelete = { repository.delete(reminder.id); refresh() }
                        )
                    }
                }
            }
        }
    }

    // Add reminder dialog
    if (showAddDialog) {
        AddReminderDialog(
            projects = projects,
            onDismiss = { showAddDialog = false },
            onSave = { reminder ->
                repository.save(reminder)
                refresh()
                showAddDialog = false
            }
        )
    }

    // Convert to backlog dialog
    showConvertDialog?.let { reminder ->
        ConvertToBacklogDialog(
            reminder = reminder,
            project = projects.find { it.id == reminder.projectId },
            onDismiss = { showConvertDialog = null },
            onConvert = { estimateMinutes, priority ->
                scope.launch {
                    val backlogItem = BacklogItem(
                        id = generateId(),
                        projectId = reminder.projectId,
                        title = reminder.task,
                        description = "Converted from reminder. Originally for: ${reminder.assignedTo.ifBlank { "self" }}",
                        priority = priority,
                        estimateMinutes = estimateMinutes,
                        status = BacklogStatus.BACKLOG,
                        createdAt = currentTimeMillis()
                    )
                    // Save to backlog
                    val existing = appState.timeManager.getBacklog(reminder.projectId)
                    appState.timeManager.saveBacklog(reminder.projectId, existing + backlogItem)
                    // Mark reminder as converted
                    repository.save(reminder.copy(
                        status = ReminderStatus.CONVERTED_TO_BACKLOG,
                        completedAt = currentTimeMillis(),
                        convertedBacklogItemId = backlogItem.id
                    ))
                    refresh()
                    showConvertDialog = null
                }
            }
        )
    }
}

// ─── Reminder Card ──────────────────────────────────────────────────────────────

@Composable
private fun ReminderCard(
    reminder: Reminder,
    project: Project?,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onConvertToBacklog: () -> Unit
) {
    val now = currentTimeMillis()
    val isOverdue = reminder.dueAt > 0 && reminder.dueAt < now
    val urgencyColor = when (reminder.urgency) {
        ReminderUrgency.CRITICAL -> Color(0xFFD32F2F)
        ReminderUrgency.HIGH -> Color(0xFFF57C00)
        ReminderUrgency.MEDIUM -> Color(0xFF1976D2)
        ReminderUrgency.LOW -> Color(0xFF4CAF50)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOverdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Urgency indicator
            Box(Modifier.size(10.dp).clip(CircleShape).background(urgencyColor))
            Spacer(Modifier.width(10.dp))

            // Content
            Column(Modifier.weight(1f)) {
                Text(reminder.task, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (project != null) {
                        Text(project.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (reminder.assignedTo.isNotBlank()) {
                        Text("→ ${reminder.assignedTo}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (reminder.dueAt > 0) {
                        val dueStr = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(reminder.dueAt))
                        Text(
                            if (isOverdue) "⚠️ $dueStr" else "Due: $dueStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions
            IconButton(onClick = onComplete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.CheckCircle, "Complete", Modifier.size(20.dp), tint = Color(0xFF4CAF50))
            }
            IconButton(onClick = onConvertToBacklog, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.MoveToInbox, "Convert to backlog", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, "Delete", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun DoneReminderCard(reminder: Reminder, project: Project?, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (reminder.status == ReminderStatus.CONVERTED_TO_BACKLOG) Icons.Filled.MoveToInbox else Icons.Filled.CheckCircle,
                null, Modifier.size(16.dp),
                tint = if (reminder.status == ReminderStatus.CONVERTED_TO_BACKLOG) MaterialTheme.colorScheme.secondary
                       else Color(0xFF4CAF50).copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                reminder.task,
                style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (project != null) {
                Text(project.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            Spacer(Modifier.width(4.dp))
            if (reminder.status == ReminderStatus.CONVERTED_TO_BACKLOG) {
                Text("→ Backlog", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, "Remove", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }
    }
}

// ─── Add Reminder Dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onSave: (Reminder) -> Unit
) {
    var task by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf(projects.firstOrNull()?.id ?: "") }
    var assignedTo by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(ReminderUrgency.MEDIUM) }
    var dueDate by remember { mutableStateOf("") }  // YYYY-MM-DD
    var dueHour by remember { mutableStateOf("17") }
    var dueMinute by remember { mutableStateOf("00") }
    var projExpanded by remember { mutableStateOf(false) }
    var urgencyExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Reminder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = task, onValueChange = { task = it },
                    label = { Text("What needs to be done?") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    placeholder = { Text("e.g. Reply to Dan about sizing") }
                )
                Spacer(Modifier.height(10.dp))

                // Project picker
                ExposedDropdownMenuBox(expanded = projExpanded, onExpandedChange = { projExpanded = it }) {
                    OutlinedTextField(
                        value = projects.find { it.id == projectId }?.name ?: "Select project",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Project") }, modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projExpanded) }
                    )
                    ExposedDropdownMenu(projExpanded, { projExpanded = false }) {
                        projects.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) },
                                onClick = { projectId = p.id; projExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = assignedTo, onValueChange = { assignedTo = it },
                    label = { Text("Who is it for? (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Name or 'self'") }
                )
                Spacer(Modifier.height(10.dp))

                // Urgency picker
                ExposedDropdownMenuBox(expanded = urgencyExpanded, onExpandedChange = { urgencyExpanded = it }) {
                    OutlinedTextField(
                        value = urgency.name.lowercase().replaceFirstChar { it.uppercase() },
                        onValueChange = {}, readOnly = true,
                        label = { Text("Urgency") }, modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(urgencyExpanded) }
                    )
                    ExposedDropdownMenu(urgencyExpanded, { urgencyExpanded = false }) {
                        ReminderUrgency.values().forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { urgency = u; urgencyExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Due date/time
                Text("Due by (optional)", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = dueDate, onValueChange = { dueDate = it },
                        label = { Text("Date") }, modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("YYYY-MM-DD") }
                    )
                    OutlinedTextField(
                        value = dueHour, onValueChange = { dueHour = it },
                        label = { Text("H") }, modifier = Modifier.width(55.dp), singleLine = true
                    )
                    Text(":", modifier = Modifier.align(Alignment.CenterVertically))
                    OutlinedTextField(
                        value = dueMinute, onValueChange = { dueMinute = it },
                        label = { Text("M") }, modifier = Modifier.width(55.dp), singleLine = true
                    )
                }

                // Quick due buttons
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val today = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis())
                    val tomorrow = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis() + 86_400_000)
                    FilterChip(selected = dueDate == today, onClick = { dueDate = today }, label = { Text("Today") })
                    FilterChip(selected = dueDate == tomorrow, onClick = { dueDate = tomorrow }, label = { Text("Tomorrow") })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (task.isNotBlank() && projectId.isNotBlank()) {
                        val dueMs = if (dueDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            val h = dueHour.toIntOrNull() ?: 17
                            val m = dueMinute.toIntOrNull() ?: 0
                            val cal = java.util.Calendar.getInstance()
                            val parts = dueDate.split("-").map { it.toInt() }
                            cal.set(parts[0], parts[1] - 1, parts[2], h, m, 0)
                            cal.timeInMillis
                        } else 0L

                        onSave(Reminder(
                            id = generateId(),
                            projectId = projectId,
                            task = task.trim(),
                            assignedTo = assignedTo.trim(),
                            dueAt = dueMs,
                            urgency = urgency,
                            status = ReminderStatus.OPEN,
                            createdAt = currentTimeMillis()
                        ))
                    }
                },
                enabled = task.isNotBlank() && projectId.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Convert to Backlog Dialog ──────────────────────────────────────────────────

@Composable
private fun ConvertToBacklogDialog(
    reminder: Reminder,
    project: Project?,
    onDismiss: () -> Unit,
    onConvert: (estimateMinutes: Int, priority: Int) -> Unit
) {
    var estimateMinutes by remember { mutableStateOf("30") }
    var priority by remember { mutableStateOf("50") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convert to Backlog Item") },
        text = {
            Column {
                Text(
                    "This reminder will become a backlog item under ${project?.name ?: "the project"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Text(reminder.task, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = estimateMinutes, onValueChange = { estimateMinutes = it },
                    label = { Text("Estimate (minutes)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = priority, onValueChange = { priority = it },
                    label = { Text("Priority (lower = higher priority)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("1-100") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val est = estimateMinutes.toIntOrNull() ?: 30
                val pri = priority.toIntOrNull() ?: 50
                onConvert(est, pri)
            }) { Text("Convert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
