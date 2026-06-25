package com.jeeves.desktop.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeeves.desktop.data.CalendarEvent
import com.jeeves.desktop.hotkey.HotkeyManager
import com.jeeves.shared.domain.MeetingTemplate
import com.jeeves.shared.domain.AudioSource
import com.jeeves.shared.domain.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(hotkeyManager: HotkeyManager) {
    val appState = LocalAppState.current

    val recordingState by appState.recordingManager.state.collectAsState()
    val error by appState.recordingManager.error.collectAsState()
    val progress by appState.recordingManager.transcriptionProgress.collectAsState()
    val audioLevel by appState.audioRecorder.audioLevel.collectAsState()

    // Streaming transcription state
    val liveTranscript by appState.streamingTranscriber.liveTranscript.collectAsState()
    val isTranscribing by appState.streamingTranscriber.isTranscribing.collectAsState()
    val serverStatus by appState.streamingTranscriber.serverStatus.collectAsState()

    // System audio capture status
    val systemCaptureStatus by appState.audioRecorder.systemCaptureStatus.collectAsState()
    val systemCaptureDetail by appState.audioRecorder.systemCaptureDetail.collectAsState()

    // Timer display
    var elapsedSeconds by remember { mutableStateOf(0L) }

    // Meeting metadata (editable during recording)
    var meetingTitle by remember { mutableStateOf("") }
    var meetingDescription by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(listOf<com.jeeves.shared.domain.Attachment>()) }

    // Read settings to check if streaming is enabled
    var streamingEnabled by remember { mutableStateOf(true) }
    var audioSourceLabel by remember { mutableStateOf("🎤 Default Microphone") }
    LaunchedEffect(Unit) {
        val settings = appState.settingsRepository.getSettings()
        streamingEnabled = settings.streamingEnabled
        audioSourceLabel = when (settings.audioSource) {
            AudioSource.DEFAULT_MICROPHONE -> "🎤 Default Microphone"
            AudioSource.SPECIFIC_DEVICE -> "🔊 ${settings.audioDeviceName.ifEmpty { "Unknown Device" }}"
        }
    }

    // Track whether this is a fresh recording start (not a re-entry to the tab)
    var hasResetForCurrentRecording by remember { mutableStateOf(false) }

    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            // Only reset metadata once at the START of a new recording
            if (!hasResetForCurrentRecording) {
                hasResetForCurrentRecording = true
                meetingTitle = ""
                meetingDescription = ""
                attachments = emptyList()
                appState.recordingManager.pendingTitle = ""
                appState.recordingManager.pendingDescription = ""
                appState.recordingManager.pendingAttachments = emptyList()
            } else {
                // Re-entering tab while recording — restore from pending fields
                meetingTitle = appState.recordingManager.pendingTitle
                meetingDescription = appState.recordingManager.pendingDescription
                attachments = appState.recordingManager.pendingAttachments
            }
            // Refresh streaming setting and audio source at start of each recording
            val settings = appState.settingsRepository.getSettings()
            streamingEnabled = settings.streamingEnabled
            audioSourceLabel = when (settings.audioSource) {
                AudioSource.DEFAULT_MICROPHONE -> "🎤 Default Microphone"
                AudioSource.SPECIFIC_DEVICE -> "🔊 ${settings.audioDeviceName.ifEmpty { "Unknown Device" }}"
            }
            // Timer — runs here for screenshot timestamp reference
            elapsedSeconds = 0
            while (isActive && recordingState == RecordingState.RECORDING) {
                delay(1000)
                elapsedSeconds++
            }
        } else if (recordingState == RecordingState.IDLE) {
            elapsedSeconds = 0
            hasResetForCurrentRecording = false
        }
    }

    // Register hotkey toggle
    LaunchedEffect(Unit) {
        hotkeyManager.onToggleRecording = {
            appState.recordingManager.toggleRecording()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status indicator
        Text(
            text = when (recordingState) {
                RecordingState.IDLE -> "Ready to Record"
                RecordingState.RECORDING -> "Recording..."
                RecordingState.PAUSED -> "Paused"
                RecordingState.PROCESSING -> "Processing..."
            },
            style = MaterialTheme.typography.headlineLarge,
            color = when (recordingState) {
                RecordingState.RECORDING -> Color.Red
                RecordingState.PROCESSING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onBackground
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Timer
        // Audio level meter (shown during recording or paused)
        if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
            AudioLevelMeter(
                level = audioLevel,
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(40.dp)
            )

            // System audio capture status indicator
            if (systemCaptureStatus != com.jeeves.desktop.audio.SystemCaptureStatus.INACTIVE) {
                Spacer(modifier = Modifier.height(6.dp))
                val (icon, color, text) = when (systemCaptureStatus) {
                    com.jeeves.desktop.audio.SystemCaptureStatus.CAPTURING ->
                        Triple("🔊", MaterialTheme.colorScheme.primary, systemCaptureDetail ?: "System audio active")
                    com.jeeves.desktop.audio.SystemCaptureStatus.FAILED ->
                        Triple("⚠️", MaterialTheme.colorScheme.error, systemCaptureDetail ?: "System audio capture failed")
                    com.jeeves.desktop.audio.SystemCaptureStatus.UNAVAILABLE ->
                        Triple("⚠️", MaterialTheme.colorScheme.error, systemCaptureDetail ?: "No loopback device found")
                    else ->
                        Triple("🔇", MaterialTheme.colorScheme.onSurfaceVariant, "System audio off")
                }
                Text(
                    text = "$icon $text",
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Live transcript display (shown during RECORDING, PAUSED, and PROCESSING when streaming is enabled)
        if (streamingEnabled && (recordingState == RecordingState.RECORDING ||
                    recordingState == RecordingState.PAUSED ||
                    recordingState == RecordingState.PROCESSING)) {

            // Meeting metadata fields (during recording)
            if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = meetingTitle,
                            onValueChange = {
                                meetingTitle = it
                                appState.recordingManager.pendingTitle = it
                            },
                            label = { Text("Meeting Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = meetingDescription,
                            onValueChange = {
                                meetingDescription = it
                                appState.recordingManager.pendingDescription = it
                            },
                            label = { Text("Description / Agenda (optional)") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 120.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            maxLines = 5
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    val scope = CoroutineScope(Dispatchers.IO)
                                    scope.launch {
                                        val screenshot = com.jeeves.desktop.audio.ScreenshotCapture.captureScreen(
                                            elapsedSeconds * 1000
                                        )
                                        if (screenshot != null) {
                                            attachments = attachments + screenshot
                                            appState.recordingManager.pendingAttachments = attachments
                                        }
                                    }
                                },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Screenshot", style = MaterialTheme.typography.labelSmall)
                            }
                            if (attachments.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "${attachments.size} screenshot(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            LiveTranscriptSection(
                liveTranscript = liveTranscript,
                isTranscribing = isTranscribing,
                isRecording = recordingState == RecordingState.RECORDING,
                serverStatus = serverStatus
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Processing progress
        progress?.let { msg ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = msg, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Main record button
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Record / Stop button
            FilledIconButton(
                onClick = { appState.recordingManager.toggleRecording() },
                modifier = Modifier.size(80.dp),
                enabled = recordingState != RecordingState.PROCESSING,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (recordingState == RecordingState.RECORDING)
                        Color.Red else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (recordingState == RecordingState.RECORDING)
                        Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recordingState == RecordingState.RECORDING)
                        "Stop recording" else "Start recording",
                    modifier = Modifier.size(40.dp)
                )
            }

            // Pause button (only during recording)
            if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
                FilledTonalIconButton(
                    onClick = {
                        CoroutineScope(Dispatchers.Main).launch {
                            if (recordingState == RecordingState.PAUSED) {
                                appState.recordingManager.resumeRecording()
                            } else {
                                appState.recordingManager.pauseRecording()
                            }
                        }
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (recordingState == RecordingState.PAUSED)
                            Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (recordingState == RecordingState.PAUSED)
                            "Resume" else "Pause"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Hotkey hint
        Text(
            text = "Hotkey: Ctrl+Shift+R to toggle recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Audio source indicator
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = audioSourceLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Feature 2: Meeting Template Picker (only shown when IDLE)
        if (recordingState == RecordingState.IDLE) {
            Spacer(modifier = Modifier.height(16.dp))
            var selectedTemplate by remember { mutableStateOf(MeetingTemplate.GENERAL) }
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedTemplate.name.replace("_", " ").lowercase()
                        .replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Meeting Type") },
                    modifier = Modifier.menuAnchor().width(200.dp),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MeetingTemplate.values().forEach { template ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    template.name.replace("_", " ").lowercase()
                                        .replaceFirstChar { it.uppercase() }
                                )
                            },
                            onClick = { selectedTemplate = template; expanded = false }
                        )
                    }
                }
            }

            // Calendar integration - show next meeting
            var nextMeeting by remember { mutableStateOf<CalendarEvent?>(null) }
            LaunchedEffect(recordingState) {
                if (recordingState == RecordingState.IDLE) {
                    while (isActive && recordingState == RecordingState.IDLE) {
                        nextMeeting = withContext(Dispatchers.IO) {
                            appState.calendarService.getNextMeeting()
                        }
                        delay(60_000)
                    }
                }
            }

            if (nextMeeting != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (nextMeeting!!.isOngoing) "🔴 In meeting: " else "📅 Next: ",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = nextMeeting!!.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Feature 5: Bookmark button (only shown during recording)
        if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    val timestampMs = elapsedSeconds * 1000
                    println("Bookmark added at ${timestampMs}ms")
                }
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Bookmark",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bookmark")
            }
        }

        // Error display
        error?.let { errorMsg ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { appState.recordingManager.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

/**
 * Live transcript section: scrollable container with auto-scroll,
 * in-flight indicator, and placeholder text.
 * [serverStatus] shows a transient note when the Whisper server is busy/connecting.
 */
@Composable
private fun LiveTranscriptSection(
    liveTranscript: String,
    isTranscribing: Boolean,
    isRecording: Boolean,
    serverStatus: String? = null
) {
    val scrollState = rememberScrollState()
    // Track whether user has scrolled away from the bottom
    val isAtBottom by remember {
        derivedStateOf {
            scrollState.value >= scrollState.maxValue
        }
    }

    // Auto-scroll to bottom when new content arrives and user is at bottom
    LaunchedEffect(liveTranscript) {
        if (isAtBottom || scrollState.maxValue == 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .heightIn(min = 60.dp, max = 200.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            if (liveTranscript.isEmpty()) {
                // Show server status if available, otherwise the normal "Listening" placeholder
                val placeholderText = serverStatus ?: if (isRecording) "Listening for speech\u2026" else ""
                if (placeholderText.isNotEmpty()) {
                    Text(
                        text = placeholderText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (serverStatus != null)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Scrollable transcript text
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = liveTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                // Show status note below the text when server is catching up
                if (serverStatus != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = serverStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            }

            // In-flight indicator when transcribing
            if (isTranscribing) {
                Spacer(modifier = Modifier.height(4.dp))
                PulsingTranscribingIndicator()
            }
        }
    }
}

/**
 * A pulsing "Transcribing..." indicator shown while a chunk request is in flight.
 */
@Composable
private fun PulsingTranscribingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "transcribing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulsing_alpha"
    )

    Text(
        text = "Transcribing\u2026",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    )
}

/**
 * Visual audio level meter with segmented bar display.
 * Shows green for low levels, yellow for medium, red for high.
 */
@Composable
private fun AudioLevelMeter(level: Float, modifier: Modifier = Modifier) {
    val animatedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 50)
    )

    val barCount = 20
    val activeColor = MaterialTheme.colorScheme.primary
    val warningColor = Color(0xFFFFB020)
    val hotColor = Color(0xFFFF4444)
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = modifier) {
            val barWidth = (size.width - (barCount - 1) * 3f) / barCount
            val barHeight = size.height

            for (i in 0 until barCount) {
                val fraction = (i + 1).toFloat() / barCount
                val isActive = animatedLevel >= fraction - (0.5f / barCount)

                val color = when {
                    !isActive -> inactiveColor
                    fraction <= 0.6f -> activeColor
                    fraction <= 0.85f -> warningColor
                    else -> hotColor
                }

                val x = i * (barWidth + 3f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Level percentage text
        Text(
            text = "${(animatedLevel * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}
