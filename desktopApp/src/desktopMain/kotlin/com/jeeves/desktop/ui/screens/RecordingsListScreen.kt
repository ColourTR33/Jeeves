package com.jeeves.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jeeves.desktop.data.SearchResult
import com.jeeves.shared.domain.QualityRating
import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.RecordingState
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import com.jeeves.shared.ui.groupBySpeaker
import kotlin.math.floor
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsListScreen(
    focusedRecordingId: String? = null,
    onRecordingSelected: ((String?) -> Unit)? = null,
    defaultContent: (@Composable () -> Unit)? = null
) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var selectedRecording by remember { mutableStateOf<Recording?>(null) }
    var transcription by remember { mutableStateOf<TranscriptionResult?>(null) }
    var summary by remember { mutableStateOf<SummaryResult?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    // Refresh recordings list and selected detail when recording state returns to IDLE
    val recordingState by appState.recordingManager.state.collectAsState()

    LaunchedEffect(Unit) {
        recordings = appState.recordingsRepository.getRecordings()
        // If opened with a specific recording focused, select it
        if (focusedRecordingId != null) {
            val focused = recordings.find { it.id == focusedRecordingId }
            if (focused != null) {
                selectedRecording = focused
                transcription = appState.recordingsRepository.getTranscription(focused.id)
                summary = appState.recordingsRepository.getSummary(focused.id)
            }
        }
    }

    // Auto-refresh when processing completes
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.IDLE) {
            recordings = appState.recordingsRepository.getRecordings()
            appState.searchService.invalidateCache()
            selectedRecording?.let { selected ->
                transcription = appState.recordingsRepository.getTranscription(selected.id)
                summary = appState.recordingsRepository.getSummary(selected.id)
            }
        }
    }

    // Debounced search
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchResults = appState.searchService.search(searchQuery, recordings)
    }

    // Tag filter state
    var selectedTag by remember { mutableStateOf<String?>(null) }
    val allTags = remember(recordings) {
        recordings.flatMap { it.tags }.distinct().sorted()
    }
    val filteredRecordings = remember(recordings, selectedTag) {
        if (selectedTag == null) recordings
        else recordings.filter { it.tags.contains(selectedTag) }
    }

    val groups = remember(filteredRecordings) { groupRecordings(filteredRecordings) }

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

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search recordings...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                // Tag filter chips
                if (allTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                            label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                        )
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { selectedTag = if (selectedTag == tag) null else tag },
                                label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Grouped list or search results
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
                } else if (searchQuery.isNotEmpty()) {
                    // Search results view
                    if (searchResults.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No results found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            items(searchResults, key = { it.recording.id }) { result ->
                                SearchResultItem(
                                    result = result,
                                    isSelected = result.recording.id == selectedRecording?.id,
                                    onClick = {
                                        selectedRecording = result.recording
                                        onRecordingSelected?.invoke(result.recording.id)
                                        scope.launch {
                                            transcription = appState.recordingsRepository.getTranscription(result.recording.id)
                                            summary = appState.recordingsRepository.getSummary(result.recording.id)
                                        }
                                    }
                                )
                            }
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
                                        onRecordingSelected?.invoke(recording.id)
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
                if (defaultContent != null) {
                    defaultContent()
                } else {
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
private fun SearchResultItem(
    result: SearchResult,
    isSelected: Boolean,
    onClick: () -> Unit
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.recording.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (result.matchSource == "transcription")
                        MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = result.matchSource,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (result.matchSource == "transcription")
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --- Recording list item (grouped view) ---

@Composable
private fun RecordingListItem(
    recording: Recording,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val appState = LocalAppState.current
    val queueItems by appState.recordingManager.processingQueue.queue.collectAsState()
    val processingItem = queueItems.find { it.recordingId == recording.id }

    // Check if transcription and summary exist for status indicators
    var hasTranscription by remember { mutableStateOf(false) }
    var hasSummary by remember { mutableStateOf(false) }
    LaunchedEffect(recording.id) {
        hasTranscription = appState.recordingsRepository.getTranscription(recording.id) != null
        hasSummary = appState.recordingsRepository.getSummary(recording.id) != null
    }

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
                if (recording.folder.isNotBlank()) {
                    Text(
                        text = "📁 ${recording.folder}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (recording.tags.isNotEmpty()) {
                    Text(
                        text = recording.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Processing status indicator OR completion indicators
            if (processingItem != null && processingItem.status != com.jeeves.shared.recording.ProcessingStatus.COMPLETE) {
                ProcessingStatusIcon(processingItem)
            } else {
                // Show completion status: green checkmarks for transcription/summary
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (hasTranscription) {
                        Icon(Icons.Filled.TextSnippet, "Transcribed", Modifier.size(14.dp), tint = Color(0xFF4CAF50))
                    }
                    if (hasSummary) {
                        Icon(Icons.Filled.AutoAwesome, "Summarized", Modifier.size(14.dp), tint = Color(0xFF4CAF50))
                    }
                    if (!hasTranscription && !hasSummary && processingItem == null) {
                        Icon(Icons.Filled.Schedule, "Pending", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                }
            }

            // Delete button
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

/**
 * Small icon showing the processing status of a recording.
 * - WAITING: hourglass icon (amber)
 * - TRANSCRIBING: small spinner (blue)
 * - SUMMARIZING: small spinner (purple)
 * - COMPLETE: checkmark (green)
 * - FAILED: error icon (red)
 * - null (not in queue): no icon shown
 */
@Composable
private fun ProcessingStatusIcon(item: com.jeeves.shared.recording.ProcessingItem?) {
    if (item == null) return

    val (icon, tint, description) = when (item.status) {
        com.jeeves.shared.recording.ProcessingStatus.WAITING -> Triple(
            Icons.Filled.Schedule,
            MaterialTheme.colorScheme.tertiary,
            "Queued"
        )
        com.jeeves.shared.recording.ProcessingStatus.TRANSCRIBING -> Triple(
            Icons.Filled.Mic,
            MaterialTheme.colorScheme.primary,
            "Transcribing"
        )
        com.jeeves.shared.recording.ProcessingStatus.DIARIZING -> Triple(
            Icons.Filled.People,
            MaterialTheme.colorScheme.primary,
            "Identifying speakers"
        )
        com.jeeves.shared.recording.ProcessingStatus.SUMMARIZING -> Triple(
            Icons.Filled.AutoAwesome,
            MaterialTheme.colorScheme.secondary,
            "Summarising"
        )
        com.jeeves.shared.recording.ProcessingStatus.COMPLETE -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Complete"
        )
        com.jeeves.shared.recording.ProcessingStatus.FAILED -> Triple(
            Icons.Filled.Error,
            MaterialTheme.colorScheme.error,
            "Failed"
        )
    }

    if (item.status == com.jeeves.shared.recording.ProcessingStatus.TRANSCRIBING ||
        item.status == com.jeeves.shared.recording.ProcessingStatus.DIARIZING ||
        item.status == com.jeeves.shared.recording.ProcessingStatus.SUMMARIZING) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp).padding(end = 4.dp),
            strokeWidth = 2.dp,
            color = tint
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(16.dp).padding(end = 4.dp),
            tint = tint
        )
    }
}

/**
 * Displays a horizontal strip of screenshot thumbnails with timestamps.
 * Click to view full-size, with delete option.
 */
@Composable
private fun AttachmentsGallery(recording: Recording) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    var previewAttachment by remember { mutableStateOf<com.jeeves.shared.domain.Attachment?>(null) }

    Column {
        Text(
            "Screenshots (${recording.attachments.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Thumbnail strip
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            recording.attachments.forEach { attachment ->
                AttachmentThumbnail(
                    attachment = attachment,
                    onClick = { previewAttachment = attachment },
                    onDelete = {
                        scope.launch {
                            val updated = recording.copy(
                                attachments = recording.attachments.filter { it.id != attachment.id }
                            )
                            appState.recordingsRepository.updateRecording(updated)
                            // Delete the file
                            try { java.io.File(attachment.filePath).delete() } catch (_: Exception) {}
                        }
                    }
                )
            }
        }
    }

    // Full-size preview dialog
    if (previewAttachment != null) {
        AlertDialog(
            onDismissRequest = { previewAttachment = null },
            title = { Text("Screenshot at ${formatAttachmentTime(previewAttachment!!.timestampMs)}") },
            text = {
                val imageBitmap = remember(previewAttachment!!.filePath) {
                    loadImageBitmap(previewAttachment!!.filePath)
                }
                if (imageBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = imageBitmap,
                        contentDescription = "Screenshot",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Could not load image", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { previewAttachment = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: com.jeeves.shared.domain.Attachment,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val imageBitmap = remember(attachment.filePath) { loadImageBitmap(attachment.filePath) }

    Card(
        modifier = Modifier
            .size(width = 120.dp, height = 90.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box {
            if (imageBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = imageBitmap,
                    contentDescription = "Screenshot",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }

            // Timestamp badge
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    formatAttachmentTime(attachment.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Delete",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun loadImageBitmap(filePath: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val file = java.io.File(filePath)
        if (!file.exists()) return null
        file.inputStream().buffered().use { stream ->
            androidx.compose.ui.res.loadImageBitmap(stream)
        }
    } catch (_: Exception) {
        null
    }
}

private fun formatAttachmentTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${String.format("%02d", seconds)}"
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

        // Tags section
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Existing tags as chips
            recording.tags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.clickable {
                        scope.launch {
                            val updated = recording.copy(tags = recording.tags - tag)
                            appState.recordingsRepository.updateRecording(updated)
                        }
                    }
                ) {
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Add tag button/field
            var addingTag by remember { mutableStateOf(false) }
            var newTag by remember { mutableStateOf("") }

            if (addingTag) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { value ->
                        // Submit on Enter key (newline in the value)
                        if (value.contains("\n")) {
                            val tagText = value.replace("\n", "").trim()
                            if (tagText.isNotBlank()) {
                                scope.launch {
                                    val cleanTag = tagText.removePrefix("#").trim()
                                    val updated = recording.copy(tags = recording.tags + cleanTag)
                                    appState.recordingsRepository.updateRecording(updated)
                                }
                            }
                            newTag = ""
                            addingTag = false
                        } else {
                            newTag = value
                        }
                    },
                    singleLine = false,
                    maxLines = 1,
                    modifier = Modifier.width(120.dp).height(36.dp),
                    textStyle = MaterialTheme.typography.labelSmall,
                    placeholder = { Text("tag name", style = MaterialTheme.typography.labelSmall) }
                )
            } else {
                IconButton(onClick = { addingTag = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add tag", modifier = Modifier.size(16.dp))
                }
            }
        }

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

        // Screenshots section (if any attachments)
        if (recording.attachments.isNotEmpty()) {
            AttachmentsGallery(recording = recording)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Export buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        appState.exportService.exportRecording(
                            recording, transcription, summary, com.jeeves.desktop.data.ExportFormat.MARKDOWN
                        )
                    }
                }
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Markdown")
            }
            OutlinedButton(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        appState.exportService.exportRecording(
                            recording, transcription, summary, com.jeeves.desktop.data.ExportFormat.TEXT
                        )
                    }
                }
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Text")
            }

            // Reminders export with feedback
            var remindersStatus by remember { mutableStateOf("") }
            OutlinedButton(
                onClick = {
                    remindersStatus = "⏳ Opening..."
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val count = appState.remindersExportService.exportToReminders(
                            summary?.actionItems ?: emptyList(),
                            recording.title
                        )
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            remindersStatus = if (count > 0) "✅ Sent to Reminders" else "❌ Failed"
                        }
                        kotlinx.coroutines.delay(3000)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            remindersStatus = ""
                        }
                    }
                },
                enabled = summary?.actionItems?.isNotEmpty() == true && remindersStatus.isEmpty()
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (remindersStatus.isEmpty()) "Add to Reminders" else remindersStatus)
            }
            OutlinedButton(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        appState.emailExportService.sendRecap(recording, transcription, summary)
                    }
                }
            ) {
                Icon(
                    Icons.Filled.Email,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Email Recap")
            }
            OutlinedButton(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val currentSettings = appState.settingsRepository.getSettings()
                        val vaultPath = currentSettings.obsidianVaultPath.ifBlank {
                            System.getProperty("user.home") + "/Obsidian/Jeeves"
                        }
                        appState.obsidianExportService.vaultPath = vaultPath
                        appState.obsidianExportService.exportToVault(recording, transcription, summary)
                    }
                }
            ) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save to Obsidian")
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
            0 -> SummaryView(summary, recording) { note ->
                scope.launch {
                    appState.recordingsRepository.updateRecordingNote(recording.id, note)
                }
            }
            1 -> TranscriptionView(transcription, recording)
        }
    }
}

@Composable
private fun QualityRatingIndicator(qualityRating: QualityRating?) {
    if (qualityRating == null) return

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Star row with overall score
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    "Meeting Quality",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(12.dp))

                // Star icons
                val overall = qualityRating.overall
                val fullStars = floor(overall).toInt()
                val fractional = overall - fullStars
                val hasHalfStar = fractional in 0.3..0.7
                val emptyStars = 5 - fullStars - if (hasHalfStar) 1 else 0

                repeat(fullStars) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (hasHalfStar) {
                    Icon(
                        Icons.Filled.StarHalf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                repeat(emptyStars) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${qualityRating.overall}/5",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expandable criteria section
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                QualityCriteriaRow("Pacing", qualityRating.pacing)
                QualityCriteriaRow("Questions", qualityRating.questions)
                QualityCriteriaRow("Goal-Setting", qualityRating.goalSetting)
                QualityCriteriaRow("Next Steps", qualityRating.nextSteps)
            }
        }
    }
}

@Composable
private fun QualityCriteriaRow(label: String, score: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = "$score/5",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SummaryView(summary: SummaryResult?, recording: Recording, onNoteChanged: (String) -> Unit) {
    if (summary == null) {
        // Still show the notes editor even when there's no summary
        LazyColumn {
            item {
                Text("No summary available yet.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                PostRecordingNotesEditor(recording = recording, onNoteChanged = onNoteChanged)
            }
        }
        return
    }

    LazyColumn {
        item {
            Text("Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(summary.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Quality rating indicator
        item {
            QualityRatingIndicator(qualityRating = summary.qualityRating)
            if (summary.qualityRating != null) {
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Text(
                    "Meeting quality rating not available (requires longer transcription)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
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

        // Follow-up questions section (below summary/key points, before action items)
        if (summary.recommendedQuestions.isNotEmpty()) {
            item {
                FollowUpQuestionsSection(questions = summary.recommendedQuestions)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        if (summary.actionItems.isNotEmpty()) {
            item {
                Text("Action Items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(summary.actionItems) { action ->
                Text("☐ $action", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        if (summary.questions.isNotEmpty()) {
            item {
                Text("Questions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(summary.questions) { question ->
                Text("❓ $question", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
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

        // Post-recording notes editor at the bottom of the summary section
        item {
            Spacer(modifier = Modifier.height(16.dp))
            PostRecordingNotesEditor(recording = recording, onNoteChanged = onNoteChanged)
        }
    }
}

/**
 * Displays AI-recommended follow-up questions in a visually distinct card.
 * Returns early (renders nothing) when the questions list is empty.
 */
@Composable
private fun FollowUpQuestionsSection(questions: List<String>) {
    if (questions.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "💡 Follow-Up Questions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            questions.forEach { question ->
                Text(
                    text = "• $question",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * Editable text area for post-recording notes.
 * Auto-saves on focus loss when content has changed.
 * Loads existing note content from recording.postRecordingNote.
 */
@Composable
fun PostRecordingNotesEditor(
    recording: Recording,
    onNoteChanged: (String) -> Unit
) {
    var noteText by remember(recording.id) { mutableStateOf(recording.postRecordingNote) }
    var lastSavedText by remember(recording.id) { mutableStateOf(recording.postRecordingNote) }

    Column {
        Text(
            "Notes",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = noteText,
            onValueChange = { noteText = it },
            placeholder = { Text("Add notes about this meeting...") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && noteText != lastSavedText) {
                        onNoteChanged(noteText)
                        lastSavedText = noteText
                    }
                },
            minLines = 4,
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TranscriptionView(transcription: TranscriptionResult?, recording: Recording) {
    if (transcription == null) {
        val appState = LocalAppState.current
        val queueItems by appState.recordingManager.processingQueue.queue.collectAsState()
        val itemInQueue = queueItems.find { it.recordingId == recording.id }
        val isProcessing = itemInQueue != null &&
            itemInQueue.status != com.jeeves.shared.recording.ProcessingStatus.COMPLETE &&
            itemInQueue.status != com.jeeves.shared.recording.ProcessingStatus.FAILED

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
            Text("No transcription available yet.", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))

            if (isProcessing) {
                val statusText = when (itemInQueue?.status) {
                    com.jeeves.shared.recording.ProcessingStatus.WAITING -> "Queued..."
                    com.jeeves.shared.recording.ProcessingStatus.TRANSCRIBING -> "Transcribing..."
                    com.jeeves.shared.recording.ProcessingStatus.SUMMARIZING -> "Summarising..."
                    else -> "Processing..."
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Button(
                    onClick = { appState.recordingManager.retranscribeRecording(recording) }
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retranscribe")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { appState.recordingManager.resummarizeRecording(recording) }
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Re-summarize")
                }
            }
        }
        return
    }

    val hasSpeakerData = transcription.segments.any { it.speaker != null }

    if (hasSpeakerData) {
        SpeakerSegmentDisplay(transcription, recording)
    } else {
        // Fallback: plain text or timestamped display without speaker data
        val appState = LocalAppState.current
        val scope = rememberCoroutineScope()

        LazyColumn {
            if (transcription.segments.isNotEmpty()) {
                items(transcription.segments) { segment ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = formatTimestamp(segment.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .width(60.dp)
                                .clickable {
                                    scope.launch {
                                        appState.audioPlayer.play(recording.filePath)
                                        appState.audioPlayer.seekTo(segment.startMs)
                                    }
                                }
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
}

@Composable
private fun SpeakerSegmentDisplay(transcription: TranscriptionResult, recording: Recording) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    val speakerColorMap = remember(transcription.segments) {
        buildSpeakerColorMap(transcription.segments)
    }

    val groups = remember(transcription.segments) {
        groupBySpeaker(transcription.segments)
    }

    // State for inline speaker name editing
    var editingSpeaker by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }

    // A recomposition key that increments when a name is saved, forcing display names to refresh
    var nameVersion by remember { mutableStateOf(0) }

    val saveName: () -> Unit = {
        editingSpeaker?.let { speaker ->
            val trimmed = editingName.trim()
            if (trimmed.isNotEmpty()) {
                appState.speakerNameService.setName(speaker, trimmed)
            }
            nameVersion++
        }
        editingSpeaker = null
        editingName = ""
    }

    LazyColumn {
        items(groups) { group ->
            val speaker = group.speaker
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Show speaker label once per group (only if speaker is non-null)
                if (speaker != null) {
                    val bgColor = speakerColorMap[speaker]

                    // Read display name from service; nameVersion triggers recomposition on save
                    val displayName = remember(speaker, nameVersion) {
                        appState.speakerNameService.getName(speaker)
                    }

                    if (editingSpeaker == speaker) {
                        // Inline edit mode
                        val focusRequester = remember { FocusRequester() }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            OutlinedTextField(
                                value = editingName,
                                onValueChange = { editingName = it },
                                singleLine = true,
                                modifier = Modifier
                                    .width(150.dp)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { state ->
                                        if (!state.isFocused && editingSpeaker == speaker) {
                                            saveName()
                                        }
                                    },
                                textStyle = MaterialTheme.typography.labelMedium,
                                placeholder = { Text(speaker) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { saveName() })
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { saveName() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Save name",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    editingSpeaker = null
                                    editingName = ""
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Cancel",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        LaunchedEffect(speaker) {
                            focusRequester.requestFocus()
                        }
                    } else {
                        // Clickable speaker label
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .then(
                                    if (bgColor != null) Modifier
                                        .background(bgColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                    else Modifier
                                )
                                .clickable {
                                    editingSpeaker = speaker
                                    editingName = displayName
                                }
                        )
                    }
                }

                // Render each segment in the group with timestamp
                for (segment in group.segments) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .then(
                                if (speaker != null) {
                                    val bgColor = speakerColorMap[speaker]
                                    if (bgColor != null) Modifier
                                        .background(bgColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                    else Modifier
                                } else Modifier
                            )
                    ) {
                        Text(
                            text = formatTimestamp(segment.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .width(60.dp)
                                .clickable {
                                    scope.launch {
                                        appState.audioPlayer.play(recording.filePath)
                                        appState.audioPlayer.seekTo(segment.startMs)
                                    }
                                }
                        )
                        Text(
                            text = segment.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
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
