package com.jeeves.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.RecordingState
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// --- Data classes for grouped recordings ---

private data class RecordingGroup(
    val label: String,
    val sublabel: String? = null,
    val recordings: List<Recording>
)

// --- Grouping logic ---

private fun groupRecordings(recordings: List<Recording>): List<RecordingGroup> {
    if (recordings.isEmpty()) return emptyList()

    val now = LocalDate.now()
    val startOfWeek = now.with(java.time.DayOfWeek.MONDAY)
    val startOfMonth = now.withDayOfMonth(1)

    val today = mutableListOf<Recording>()
    val thisWeek = mutableListOf<Recording>()
    val thisMonth = mutableListOf<Recording>()
    val olderByMonth = mutableMapOf<YearMonth, MutableList<Recording>>()

    for (recording in recordings) {
        val date = Instant.ofEpochMilli(recording.createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        when {
            date == now -> today.add(recording)
            date >= startOfWeek -> thisWeek.add(recording)
            date >= startOfMonth -> thisMonth.add(recording)
            else -> olderByMonth.getOrPut(YearMonth.from(date)) { mutableListOf() }.add(recording)
        }
    }

    val groups = mutableListOf<RecordingGroup>()

    if (today.isNotEmpty()) {
        groups.add(RecordingGroup("Today", null, today))
    }
    if (thisWeek.isNotEmpty()) {
        groups.add(RecordingGroup("This Week", null, thisWeek))
    }
    if (thisMonth.isNotEmpty()) {
        groups.add(RecordingGroup("This Month", null, thisMonth))
    }

    // Sort older months descending
    val sortedMonths = olderByMonth.keys.sortedDescending()
    val currentYear = now.year

    for (yearMonth in sortedMonths) {
        val monthName = yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val label = if (yearMonth.year == currentYear) {
            monthName
        } else {
            "$monthName ${yearMonth.year}"
        }
        groups.add(RecordingGroup(label, "${olderByMonth[yearMonth]!!.size} recordings", olderByMonth[yearMonth]!!))
    }

    return groups
}

// --- Main screen ---

@Composable
fun RecordingsListScreen() {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var selectedRecording by remember { mutableStateOf<Recording?>(null) }
    var transcription by remember { mutableStateOf<TranscriptionResult?>(null) }
    var summary by remember { mutableStateOf<SummaryResult?>(null) }

    // Refresh recordings list and selected detail when recording state returns to IDLE
    val recordingState by appState.recordingManager.state.collectAsState()

    LaunchedEffect(Unit) {
        recordings = appState.recordingsRepository.getRecordings()
    }

    // Auto-refresh when processing completes
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.IDLE) {
            recordings = appState.recordingsRepository.getRecordings()
            selectedRecording?.let { selected ->
                transcription = appState.recordingsRepository.getTranscription(selected.id)
                summary = appState.recordingsRepository.getSummary(selected.id)
            }
        }
    }

    val groups = remember(recordings) { groupRecordings(recordings) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Recordings list panel
        Surface(
            modifier = Modifier.width(320.dp).fillMaxHeight(),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Panel header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Recordings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (recordings.isNotEmpty()) {
                        Text(
                            "${recordings.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // Grouped list
                if (recordings.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No recordings yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Press Ctrl+Shift+R to start",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        groups.forEach { group ->
                            // Section header
                            item(key = "header_${group.label}") {
                                SectionHeader(
                                    label = group.label,
                                    sublabel = group.sublabel,
                                    count = group.recordings.size
                                )
                            }

                            // Recording items
                            items(group.recordings, key = { it.id }) { recording ->
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

                            // Spacer between groups
                            item(key = "spacer_${group.label}") {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        // Detail view
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(20.dp)
        ) {
            if (selectedRecording == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.TouchApp,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Select a recording",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

// --- Section header ---

@Composable
private fun SectionHeader(label: String, sublabel: String?, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// --- Recording list item ---

@Composable
private fun RecordingListItem(
    recording: Recording,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surface
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = backgroundColor,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time indicator dot
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.size(8.dp)
            ) {}

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = formatRecordingTime(recording.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = formatRecordingDuration(recording.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Delete button (subtle, shown on hover ideally but always visible for now)
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// --- Detail view (unchanged from before) ---

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
                    fontWeight = FontWeight.Bold,
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

        Spacer(modifier = Modifier.height(16.dp))

        // Audio playback controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = { appState.audioPlayer.stop() }) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Slider(
                        value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                        onValueChange = { fraction ->
                            val seekMs = (fraction * durationMs).toLong()
                            appState.audioPlayer.seekTo(seekMs)
                        },
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${formatTimestamp(positionMs)} / ${formatTimestamp(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            Text("Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(summary.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (summary.keyPoints.isNotEmpty()) {
            item {
                Text("Key Points", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(summary.keyPoints) { point ->
                Text("• $point", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        if (summary.actionItems.isNotEmpty()) {
            item {
                Text("Action Items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(summary.actionItems) { action ->
                Text("☐ $action", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
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

// --- Formatters ---

private fun formatRecordingTime(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return date.format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun formatRecordingDate(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return date.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
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
