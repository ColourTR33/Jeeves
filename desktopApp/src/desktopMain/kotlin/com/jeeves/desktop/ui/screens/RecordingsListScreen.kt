package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import kotlinx.coroutines.launch

@Composable
fun RecordingsListScreen() {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var selectedRecording by remember { mutableStateOf<Recording?>(null) }
    var transcription by remember { mutableStateOf<TranscriptionResult?>(null) }
    var summary by remember { mutableStateOf<SummaryResult?>(null) }

    LaunchedEffect(Unit) {
        recordings = appState.recordingsRepository.getRecordings()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Recordings list
        LazyColumn(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            if (recordings.isEmpty()) {
                item {
                    Text(
                        text = "No recordings yet.\nStart a recording to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            items(recordings) { recording ->
                RecordingListItem(
                    recording = recording,
                    isSelected = recording.id == selectedRecording?.id,
                    onClick = {
                        selectedRecording = recording
                        scope.launch {
                            transcription = appState.recordingsRepository.getTranscription(recording.id)
                            summary = appState.recordingsRepository.getSummary(recording.id)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            appState.recordingsRepository.deleteRecording(recording.id)
                            recordings = appState.recordingsRepository.getRecordings()
                            if (selectedRecording?.id == recording.id) {
                                selectedRecording = null
                                transcription = null
                                summary = null
                            }
                        }
                    }
                )
            }
        }

        // Divider
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        // Detail view
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            if (selectedRecording == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Select a recording to view details")
                }
            } else {
                RecordingDetail(
                    recording = selectedRecording!!,
                    transcription = transcription,
                    summary = summary
                )
            }
        }
    }
}

@Composable
private fun RecordingListItem(
    recording: Recording,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = formatRecordingDate(recording.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatRecordingDuration(recording.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete recording",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RecordingDetail(
    recording: Recording,
    transcription: TranscriptionResult?,
    summary: SummaryResult?
) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }

    val isPlaying by appState.audioPlayer.isPlaying.collectAsState()
    val positionMs by appState.audioPlayer.positionMs.collectAsState()
    val durationMs by appState.audioPlayer.durationMs.collectAsState()

    var isEditingTitle by remember { mutableStateOf(false) }
    var editedTitle by remember(recording.id) { mutableStateOf(recording.title) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Editable title
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isEditingTitle) {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = {
                    scope.launch {
                        appState.recordingsRepository.updateRecording(
                            recording.copy(title = editedTitle)
                        )
                    }
                    isEditingTitle = false
                }) {
                    Icon(Icons.Filled.Check, contentDescription = "Save title")
                }
                IconButton(onClick = {
                    editedTitle = recording.title
                    isEditingTitle = false
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
            } else {
                Text(
                    text = editedTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { isEditingTitle = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit title")
                }
            }
        }
        Text(
            text = "${formatRecordingDate(recording.createdAt)} · ${formatRecordingDuration(recording.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Audio playback controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Play/Pause button
                    IconButton(onClick = {
                        scope.launch {
                            if (isPlaying) {
                                appState.audioPlayer.pause()
                            } else if (positionMs > 0 && positionMs < durationMs) {
                                appState.audioPlayer.resume()
                            } else {
                                appState.audioPlayer.play(recording.filePath)
                            }
                        }
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Stop button
                    IconButton(onClick = { appState.audioPlayer.stop() }) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop"
                        )
                    }

                    // Position / Duration text
                    Text(
                        text = "${formatTimestamp(positionMs)} / ${formatTimestamp(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Seek slider
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                    onValueChange = { fraction ->
                        val seekMs = (fraction * durationMs).toLong()
                        appState.audioPlayer.seekTo(seekMs)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Summary", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Transcription", modifier = Modifier.padding(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> SummaryView(summary)
            1 -> TranscriptionView(transcription)
        }
    }
}

@Composable
private fun SummaryView(summary: SummaryResult?) {
    if (summary == null) {
        Text("No summary available yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    LazyColumn {
        item {
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(summary.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (summary.keyPoints.isNotEmpty()) {
            item {
                Text("Key Points", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(summary.keyPoints) { point ->
                Text("• $point", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        if (summary.actionItems.isNotEmpty()) {
            item {
                Text("Action Items", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(summary.actionItems) { action ->
                Text("☐ $action", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Model: ${summary.modelUsed}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TranscriptionView(transcription: TranscriptionResult?) {
    if (transcription == null) {
        Text("No transcription available yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    LazyColumn {
        if (transcription.segments.isNotEmpty()) {
            items(transcription.segments) { segment ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = formatTimestamp(segment.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(60.dp)
                    )
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            item {
                Text(transcription.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun formatRecordingDate(timestamp: Long): String {
    val date = java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
    return date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
}

private fun formatRecordingDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m"
    else if (minutes > 0) "${minutes}m ${seconds % 60}s"
    else "${seconds}s"
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
