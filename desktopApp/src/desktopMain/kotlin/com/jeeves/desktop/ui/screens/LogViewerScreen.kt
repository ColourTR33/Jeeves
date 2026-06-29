package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeeves.shared.ai.AppLogger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen() {
    val entries by AppLogger.entries.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var filterLevel by remember { mutableStateOf<String?>(null) } // null = all
    var autoScroll by remember { mutableStateOf(true) }

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    val filteredEntries = if (filterLevel != null) entries.filter { it.level == filterLevel } else entries

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Application Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Filter chips
                FilterChip(
                    selected = filterLevel == null,
                    onClick = { filterLevel = null },
                    label = { Text("All (${entries.size})") }
                )
                FilterChip(
                    selected = filterLevel == "ERROR",
                    onClick = { filterLevel = if (filterLevel == "ERROR") null else "ERROR" },
                    label = { Text("Errors (${entries.count { it.level == "ERROR" }})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer
                    )
                )
                FilterChip(
                    selected = filterLevel == "WARN",
                    onClick = { filterLevel = if (filterLevel == "WARN") null else "WARN" },
                    label = { Text("Warnings (${entries.count { it.level == "WARN" }})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFFF3E0)
                    )
                )

                Spacer(Modifier.width(8.dp))

                // Auto-scroll toggle
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        if (autoScroll) Icons.Filled.VerticalAlignBottom else Icons.Filled.PauseCircle,
                        contentDescription = if (autoScroll) "Auto-scroll on" else "Auto-scroll off",
                        tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Clear button
                IconButton(onClick = { AppLogger.clear() }) {
                    Icon(Icons.Filled.Delete, "Clear logs", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Copy all to clipboard
                IconButton(onClick = {
                    val text = filteredEntries.joinToString("\n") { e ->
                        val time = formatLogTime(e.timestamp)
                        "[$time][${e.level}][${e.tag}] ${e.message}"
                    }
                    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                }) {
                    Icon(Icons.Filled.ContentCopy, "Copy logs")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Log entries
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            if (filteredEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No log entries yet", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    items(filteredEntries) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: AppLogger.LogEntry) {
    val levelColor = when (entry.level) {
        "ERROR" -> Color(0xFFFF5252)
        "WARN" -> Color(0xFFFFB74D)
        "INFO" -> Color(0xFF81C784)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            formatLogTime(entry.timestamp),
            color = Color(0xFF757575),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(65.dp)
        )
        // Level
        Text(
            entry.level.padEnd(5),
            color = levelColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(45.dp)
        )
        // Tag
        Text(
            "[${entry.tag}]",
            color = Color(0xFF64B5F6),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(180.dp),
            maxLines = 1
        )
        // Message
        Text(
            entry.message,
            color = Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatLogTime(epochMs: Long): String {
    val totalSeconds = (epochMs / 1000) % 86400  // seconds since midnight UTC
    val h = (totalSeconds / 3600) % 24
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
