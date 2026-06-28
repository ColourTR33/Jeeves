package com.jeeves.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppVersion {
    const val VERSION = "1.1.0"
    const val BUILD_DATE = "2025-07-15"
}

data class ReleaseNote(
    val version: String,
    val date: String,
    val features: List<String>
)

val releaseHistory = listOf(
    ReleaseNote(
        version = "1.1.0",
        date = "2025-07-15",
        features = listOf(
            "Meeting Intelligence: custom prompt templates per meeting type (Standup, 1:1, Interview, Brainstorm)",
            "Cloud LLM support: OpenAI-compatible API for summarization (GPT-4o, Claude, etc.)",
            "Quality ratings: AI-generated meeting effectiveness scores (pacing, questions, goals, next steps)",
            "Recommended follow-up questions generated after each meeting",
            "Auto-generated hashtag tags for recording organization",
            "About dialog with version and release history"
        )
    ),
    ReleaseNote(
        version = "1.0.0",
        date = "2025-07-10",
        features = listOf(
            "Speaker diarization via local pyannote server",
            "Dedicated streaming transcription endpoint (second whisper-server)",
            "System audio capture for recording both sides of calls",
            "Live transcription with server status indicator",
            "Time tracking with burndown charts and forward planning",
            "Recording → timesheet auto-logging (+10 min handoff)",
            "Screenshots captured during recording with gallery view",
            "Groq cloud transcription (whisper-large-v3)",
            "Obsidian and email export",
            "Apple Reminders integration for action items",
            "Global hotkey recording toggle",
            "Post-recording notes and metadata"
        )
    )
)

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Jeeves",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Meeting Recorder, Transcriber & Summariser",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version ${AppVersion.VERSION}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                releaseHistory.forEachIndexed { index, release ->
                    if (index > 0) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                    }
                    Text(
                        text = "v${release.version} — ${release.date}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    release.features.forEach { feature ->
                        Row(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                            Text("•", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = feature,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    )
}
