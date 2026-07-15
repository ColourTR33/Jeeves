package com.jeeves.desktop.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeeves.shared.domain.*
import com.jeeves.shared.mantra.MantraManager
import com.jeeves.shared.recording.generateId
import com.jeeves.shared.recording.currentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MantraScreen(mantraManager: MantraManager) {
    val mantras by mantraManager.mantras.collectAsState()
    val activeExecution by mantraManager.activeExecution.collectAsState()
    val activeMantra by mantraManager.activeMantra.collectAsState()
    val stepSecondsRemaining by mantraManager.stepSecondsRemaining.collectAsState()
    val isRunning by mantraManager.isRunning.collectAsState()
    val todayExecutions by mantraManager.todayExecutions.collectAsState()

    // Timer tick — drives the countdown every second
    LaunchedEffect(isRunning) {
        while (isActive && isRunning) {
            delay(1000)
            mantraManager.tick()
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingMantra by remember { mutableStateOf<Mantra?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        // If there's an active execution, show the focus view
        if (activeExecution != null && activeMantra != null) {
            ActiveExecutionView(
                mantra = activeMantra!!,
                execution = activeExecution!!,
                secondsRemaining = stepSecondsRemaining,
                isRunning = isRunning,
                onCheckOff = { mantraManager.checkOffCurrentStep() },
                onPause = { mantraManager.togglePause() },
                onSkipStep = { mantraManager.skipStepTimer() },
                onAbandon = { mantraManager.skipExecution() }
            )
        } else {
            // Normal view: list of mantras + due today
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mantras", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Mantra")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Due today section
            val dueMantras = mantraManager.getDueMantras()
            if (dueMantras.isNotEmpty()) {
                Text("Due Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                dueMantras.forEach { mantra ->
                    DueMantraCard(
                        mantra = mantra,
                        onStart = { mantraManager.startExecution(mantra.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            // Today's completed
            val completedToday = todayExecutions.filter { it.status == MantraExecutionStatus.COMPLETED }
            if (completedToday.isNotEmpty()) {
                Text("Completed Today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                completedToday.forEach { exec ->
                    val m = mantras.find { it.id == exec.mantraId }
                    if (m != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(m.name, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text("${exec.elapsedSeconds / 60}m ${exec.elapsedSeconds % 60}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // All mantras list
            Text("All Mantras", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (mantras.isEmpty()) {
                Text(
                    "No mantras yet. Create your first one to build a daily routine.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(mantras) { mantra ->
                        MantraListItem(
                            mantra = mantra,
                            onStart = { mantraManager.startExecution(mantra.id) },
                            onEdit = { editingMantra = mantra },
                            onToggleActive = { mantraManager.toggleActive(mantra.id) },
                            onDelete = { mantraManager.deleteMantra(mantra.id) }
                        )
                    }
                }
            }
        }
    }

    // Create/Edit dialog
    if (showCreateDialog || editingMantra != null) {
        MantraEditorDialog(
            existing = editingMantra,
            projects = emptyList(), // Filled via AppState below
            mantraManager = mantraManager,
            onDismiss = { showCreateDialog = false; editingMantra = null }
        )
    }
}

// ─── Active Execution View (Focus Mode) ─────────────────────────────────────────

@Composable
private fun ActiveExecutionView(
    mantra: Mantra,
    execution: MantraExecution,
    secondsRemaining: Int,
    isRunning: Boolean,
    onCheckOff: () -> Unit,
    onPause: () -> Unit,
    onSkipStep: () -> Unit,
    onAbandon: () -> Unit
) {
    val currentStep = mantra.steps.getOrNull(execution.currentStepIndex)
    val progress = execution.currentStepIndex.toFloat() / mantra.steps.size.toFloat()
    val stepProgress = if (currentStep != null && currentStep.durationSeconds > 0) {
        1f - (secondsRemaining.toFloat() / currentStep.durationSeconds.toFloat())
    } else 1f

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(mantra.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Step ${execution.currentStepIndex + 1} of ${mantra.steps.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // Overall progress bar
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth(0.6f).height(6.dp).clip(RoundedCornerShape(3.dp))
        )

        Spacer(Modifier.height(32.dp))

        // Current step card
        if (currentStep != null) {
            Card(
                modifier = Modifier.fillMaxWidth(0.7f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Phase label
                    Text(
                        currentStep.phase,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(12.dp))

                    // Action text
                    Text(
                        currentStep.action,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(24.dp))

                    // Countdown timer
                    val minutes = secondsRemaining / 60
                    val seconds = secondsRemaining % 60
                    Text(
                        String.format("%02d:%02d", minutes, seconds),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (secondsRemaining <= 10 && secondsRemaining > 0)
                            MaterialTheme.colorScheme.error
                        else if (secondsRemaining == 0)
                            Color(0xFF4CAF50)
                        else
                            MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(8.dp))

                    // Step progress bar
                    LinearProgressIndicator(
                        progress = stepProgress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (secondsRemaining == 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(16.dp))

                    // Status text
                    if (secondsRemaining == 0) {
                        Text(
                            "Time's up! Check off to continue.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF4CAF50)
                        )
                    } else if (!isRunning) {
                        Text(
                            "Paused",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pause/Resume
            if (secondsRemaining > 0) {
                OutlinedButton(onClick = onPause) {
                    Icon(
                        if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRunning) "Pause" else "Resume")
                }

                // Skip step timer
                OutlinedButton(onClick = onSkipStep) {
                    Icon(Icons.Filled.SkipNext, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Skip Timer")
                }
            }

            // Check off (enabled when timer is done)
            if (secondsRemaining == 0) {
                Button(
                    onClick = onCheckOff,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done — Next Step")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Abandon button
        TextButton(onClick = onAbandon, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text("Abandon this run")
        }

        Spacer(Modifier.height(24.dp))

        // Steps overview (scrollable list showing all steps with checkmarks)
        Card(
            Modifier.fillMaxWidth(0.7f).weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                mantra.steps.forEachIndexed { index, step ->
                    val isCompleted = execution.completedSteps.contains(step.id)
                    val isCurrent = index == execution.currentStepIndex
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status icon
                        when {
                            isCompleted -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                            isCurrent -> Icon(Icons.Filled.RadioButtonChecked, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                step.phase,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            "${step.durationSeconds / 60}:${String.format("%02d", step.durationSeconds % 60)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ─── Due Mantra Card ────────────────────────────────────────────────────────────

@Composable
private fun DueMantraCard(mantra: Mantra, onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(mantra.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${mantra.steps.size} steps · ${mantra.totalDurationFormatted} · ${mantra.schedule.startTimeFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onStart, modifier = Modifier.height(36.dp)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Start")
            }
        }
    }
}

// ─── Mantra List Item ───────────────────────────────────────────────────────────

@Composable
private fun MantraListItem(
    mantra: Mantra,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (mantra.isActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mantra.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (!mantra.isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text("PAUSED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "${mantra.steps.size} steps · ${mantra.totalDurationFormatted} · ${mantra.schedule.daysOfWeek.joinToString { it.name.take(3) }} @ ${mantra.schedule.startTimeFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onStart, enabled = mantra.isActive) {
                Icon(Icons.Filled.PlayArrow, "Start", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, "Edit", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onToggleActive) {
                Icon(
                    if (mantra.isActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (mantra.isActive) "Pause" else "Activate",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── Mantra Editor Dialog ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MantraEditorDialog(
    existing: Mantra?,
    projects: List<Project>,
    mantraManager: MantraManager,
    onDismiss: () -> Unit
) {
    val appState = LocalAppState.current
    val allProjects by appState.timeManager.projects.collectAsState()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var projectId by remember { mutableStateOf(existing?.projectId ?: "") }
    var steps by remember { mutableStateOf(existing?.steps ?: emptyList()) }
    var scheduleDays by remember { mutableStateOf(existing?.schedule?.daysOfWeek ?: listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )) }
    var scheduleHour by remember { mutableStateOf(existing?.schedule?.startTimeHour?.toString() ?: "9") }
    var scheduleMinute by remember { mutableStateOf(existing?.schedule?.startTimeMinute?.toString() ?: "0") }

    // Step editor state
    var newPhase by remember { mutableStateOf("") }
    var newAction by remember { mutableStateOf("") }
    var newMinutes by remember { mutableStateOf("5") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit Mantra" else "New Mantra") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).widthIn(min = 400.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("e.g. Midday Comms Check") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                // Project picker for time logging
                var projExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = projExpanded, onExpandedChange = { projExpanded = it }) {
                    OutlinedTextField(
                        value = allProjects.find { it.id == projectId }?.name ?: "(don't log time)",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Log time to project") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projExpanded) }
                    )
                    ExposedDropdownMenu(projExpanded, { projExpanded = false }) {
                        DropdownMenuItem(text = { Text("(don't log time)") }, onClick = { projectId = ""; projExpanded = false })
                        allProjects.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { projectId = p.id; projExpanded = false })
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Schedule
                Text("Schedule", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.values().forEach { day ->
                        FilterChip(
                            selected = scheduleDays.contains(day),
                            onClick = {
                                scheduleDays = if (scheduleDays.contains(day)) scheduleDays - day else scheduleDays + day
                            },
                            label = { Text(day.name.take(2), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = scheduleHour, onValueChange = { scheduleHour = it },
                        label = { Text("Hour") }, modifier = Modifier.width(70.dp), singleLine = true
                    )
                    Text(":", modifier = Modifier.align(Alignment.CenterVertically))
                    OutlinedTextField(
                        value = scheduleMinute, onValueChange = { scheduleMinute = it },
                        label = { Text("Min") }, modifier = Modifier.width(70.dp), singleLine = true
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Steps list
                Text("Steps (${steps.size})", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))

                steps.forEachIndexed { index, step ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(step.phase, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(step.action, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${step.durationSeconds / 60}m", style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { steps = steps.toMutableList().apply { removeAt(index) } }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, "Remove", Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Add step
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = newPhase, onValueChange = { newPhase = it },
                                label = { Text("Phase") }, modifier = Modifier.weight(1f), singleLine = true,
                                placeholder = { Text("e.g. Recon") }
                            )
                            OutlinedTextField(
                                value = newMinutes, onValueChange = { newMinutes = it },
                                label = { Text("Min") }, modifier = Modifier.width(60.dp), singleLine = true
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = newAction, onValueChange = { newAction = it },
                            label = { Text("Action") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("What to do in this step...") }
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                val mins = newMinutes.toIntOrNull() ?: 5
                                if (newPhase.isNotBlank()) {
                                    steps = steps + MantraStep(
                                        id = generateId(),
                                        phase = newPhase.trim(),
                                        action = newAction.trim(),
                                        durationSeconds = mins * 60
                                    )
                                    newPhase = ""; newAction = ""; newMinutes = "5"
                                }
                            },
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Step", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && steps.isNotEmpty()) {
                        val mantra = Mantra(
                            id = existing?.id ?: generateId(),
                            name = name.trim(),
                            description = description.trim(),
                            steps = steps,
                            schedule = MantraSchedule(
                                daysOfWeek = scheduleDays,
                                startTimeHour = scheduleHour.toIntOrNull() ?: 9,
                                startTimeMinute = scheduleMinute.toIntOrNull() ?: 0
                            ),
                            projectId = projectId,
                            isActive = existing?.isActive ?: true,
                            createdAt = existing?.createdAt ?: currentTimeMillis(),
                            updatedAt = currentTimeMillis()
                        )
                        mantraManager.saveMantra(mantra)
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank() && steps.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
