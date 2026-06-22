package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.AiEndpointType
import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.domain.AudioSource
import com.jeeves.shared.domain.DiarizationMode
import com.jeeves.shared.domain.validateChunkInterval
import com.jeeves.shared.domain.validateOverlapWindow
import com.jeeves.shared.domain.validateOverlapLessThanInterval
import kotlinx.coroutines.launch
import com.jeeves.desktop.audio.AudioInputDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(AppSettings()) }
    var isSaved by remember { mutableStateOf(false) }

    // Available audio input devices
    var availableDevices by remember { mutableStateOf<List<AudioInputDevice>>(emptyList()) }

    // Streaming settings text fields for validation
    var chunkIntervalText by remember { mutableStateOf("") }
    var overlapWindowText by remember { mutableStateOf("") }

    // Derived validation states
    val chunkIntervalValue = chunkIntervalText.toIntOrNull()
    val overlapWindowValue = overlapWindowText.toFloatOrNull()
    val isChunkIntervalValid = chunkIntervalValue != null && validateChunkInterval(chunkIntervalValue)
    val isOverlapWindowValid = overlapWindowValue != null && validateOverlapWindow(overlapWindowValue)
    val isOverlapLessThanInterval = if (overlapWindowValue != null && chunkIntervalValue != null) {
        validateOverlapLessThanInterval(overlapWindowValue, chunkIntervalValue)
    } else true
    val hasStreamingValidationError = settings.streamingEnabled && (
        (chunkIntervalText.isNotEmpty() && !isChunkIntervalValid) ||
        (overlapWindowText.isNotEmpty() && !isOverlapWindowValid) ||
        !isOverlapLessThanInterval
    )

    LaunchedEffect(Unit) {
        val loaded = appState.settingsRepository.getSettings()
        settings = loaded
        chunkIntervalText = loaded.chunkIntervalSeconds.toString()
        overlapWindowText = loaded.overlapWindowSeconds.toString()
        availableDevices = appState.audioRecorder.getAvailableInputDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        // Transcription endpoint
        EndpointSettings(
            title = "Transcription (Whisper)",
            config = settings.transcriptionEndpoint,
            onConfigChange = { newConfig ->
                settings = settings.copy(transcriptionEndpoint = newConfig)
                isSaved = false
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Summarization endpoint
        EndpointSettings(
            title = "Summarization (LLM)",
            config = settings.summarizationEndpoint,
            onConfigChange = { newConfig ->
                settings = settings.copy(summarizationEndpoint = newConfig)
                isSaved = false
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Hotkey settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Recording Hotkey", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.recordingHotkey,
                    onValueChange = {
                        settings = settings.copy(recordingHotkey = it)
                        isSaved = false
                    },
                    label = { Text("Hotkey combination") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Audio settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Audio Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = settings.audioFormat,
                        onValueChange = {
                            settings = settings.copy(audioFormat = it)
                            isSaved = false
                        },
                        label = { Text("Format") },
                        modifier = Modifier.width(120.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = settings.sampleRate.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { rate ->
                                settings = settings.copy(sampleRate = rate)
                                isSaved = false
                            }
                        },
                        label = { Text("Sample Rate (Hz)") },
                        modifier = Modifier.width(180.dp),
                        singleLine = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Audio Input Device selection (for system audio capture)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Audio Input Device", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Audio source toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use specific audio device")
                    Switch(
                        checked = settings.audioSource == AudioSource.SPECIFIC_DEVICE,
                        onCheckedChange = { useSpecific ->
                            settings = settings.copy(
                                audioSource = if (useSpecific) AudioSource.SPECIFIC_DEVICE else AudioSource.DEFAULT_MICROPHONE
                            )
                            isSaved = false
                        }
                    )
                }

                if (settings.audioSource == AudioSource.SPECIFIC_DEVICE) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Device dropdown
                    var deviceExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = deviceExpanded,
                        onExpandedChange = { deviceExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = settings.audioDeviceName.ifEmpty { "Select a device..." },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Input Device") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = deviceExpanded,
                            onDismissRequest = { deviceExpanded = false }
                        ) {
                            if (availableDevices.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No input devices found") },
                                    onClick = { deviceExpanded = false },
                                    enabled = false
                                )
                            } else {
                                availableDevices.forEach { device ->
                                    DropdownMenuItem(
                                        text = { Text(device.name) },
                                        onClick = {
                                            settings = settings.copy(audioDeviceName = device.name)
                                            isSaved = false
                                            deviceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Refresh button
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        availableDevices = appState.audioRecorder.getAvailableInputDevices()
                    }) {
                        Text("Refresh Devices")
                    }

                    // Help text for system audio capture (platform-specific)
                    val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
                    val hasBlackHole = availableDevices.any { it.name.contains("BlackHole", ignoreCase = true) }
                    val hasStereoMix = availableDevices.any { it.name.contains("Stereo Mix", ignoreCase = true) || it.name.contains("CABLE", ignoreCase = true) }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isMacOS) {
                        if (hasBlackHole) {
                            Text(
                                "✓ BlackHole detected — select it above to capture system audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "To capture system audio on macOS, install BlackHole (a virtual audio driver) " +
                                    "and set up a Multi-Output Device in Audio MIDI Setup. Then select BlackHole here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Windows
                        if (hasStereoMix) {
                            Text(
                                "✓ Stereo Mix / virtual cable detected — select it above to capture system audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "To capture system audio on Windows, enable 'Stereo Mix' in Sound Settings " +
                                    "(Recording tab → right-click → Show Disabled Devices), or install VB-CABLE " +
                                    "(a virtual audio driver). Then select the device here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Speaker Diarization settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Speaker Diarization", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Enable diarization toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable speaker identification")
                    Switch(
                        checked = settings.diarizationEnabled,
                        onCheckedChange = {
                            settings = settings.copy(diarizationEnabled = it)
                            isSaved = false
                        }
                    )
                }

                // Mode selection and stereo - only shown when diarization is enabled
                if (settings.diarizationEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Mode", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    // TinyDiarize radio button
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.diarizationMode == DiarizationMode.TINYDIARIZE,
                            onClick = {
                                settings = settings.copy(diarizationMode = DiarizationMode.TINYDIARIZE)
                                isSaved = false
                            }
                        )
                        Text("TinyDiarize (recommended)", modifier = Modifier.padding(start = 4.dp))
                    }

                    // Diarize radio button
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.diarizationMode == DiarizationMode.DIARIZE,
                            onClick = {
                                settings = settings.copy(diarizationMode = DiarizationMode.DIARIZE)
                                isSaved = false
                            }
                        )
                        Text("Diarize (stereo)", modifier = Modifier.padding(start = 4.dp))
                    }

                    // Stereo recording switch - only shown when DIARIZE mode selected
                    if (settings.diarizationMode == DiarizationMode.DIARIZE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Record in stereo")
                            Switch(
                                checked = settings.stereoRecording,
                                onCheckedChange = {
                                    settings = settings.copy(stereoRecording = it)
                                    isSaved = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Streaming Transcription settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Streaming Transcription", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Enable streaming toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable live transcription during recording")
                    Switch(
                        checked = settings.streamingEnabled,
                        onCheckedChange = {
                            settings = settings.copy(streamingEnabled = it)
                            isSaved = false
                        }
                    )
                }

                if (settings.streamingEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Chunk interval field
                    OutlinedTextField(
                        value = chunkIntervalText,
                        onValueChange = { newValue ->
                            chunkIntervalText = newValue
                            newValue.toIntOrNull()?.let { intVal ->
                                settings = settings.copy(chunkIntervalSeconds = intVal)
                            }
                            isSaved = false
                        },
                        label = { Text("Chunk Interval (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = chunkIntervalText.isNotEmpty() && !isChunkIntervalValid
                    )
                    if (chunkIntervalText.isNotEmpty() && !isChunkIntervalValid) {
                        Text(
                            text = "Must be an integer between 3 and 30",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Overlap window field
                    OutlinedTextField(
                        value = overlapWindowText,
                        onValueChange = { newValue ->
                            overlapWindowText = newValue
                            newValue.toFloatOrNull()?.let { floatVal ->
                                settings = settings.copy(overlapWindowSeconds = floatVal)
                            }
                            isSaved = false
                        },
                        label = { Text("Overlap Window (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = (overlapWindowText.isNotEmpty() && !isOverlapWindowValid) || !isOverlapLessThanInterval
                    )
                    if (overlapWindowText.isNotEmpty() && !isOverlapWindowValid) {
                        Text(
                            text = "Must be a number between 0.5 and 5.0",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                    if (isOverlapWindowValid && !isOverlapLessThanInterval) {
                        Text(
                            text = "Overlap window must be less than chunk interval",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Custom Vocabulary
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Custom Vocabulary", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.customVocabulary,
                    onValueChange = {
                        settings = settings.copy(customVocabulary = it)
                        isSaved = false
                    },
                    label = { Text("Custom terms (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
                Text(
                    "Add industry terms to improve transcription accuracy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Obsidian Vault settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Obsidian Vault", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.obsidianVaultPath,
                    onValueChange = {
                        settings = settings.copy(obsidianVaultPath = it)
                        isSaved = false
                    },
                    label = { Text("Vault path (e.g. ~/Obsidian/Jeeves)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Save button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (isSaved) {
                Text(
                    "Settings saved",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp, top = 8.dp)
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        appState.settingsRepository.saveSettings(settings)
                        isSaved = true
                    }
                },
                enabled = !hasStreamingValidationError
            ) {
                Text("Save Settings")
            }
        }
    }
}

@Composable
private fun EndpointSettings(
    title: String,
    config: AiEndpointConfig,
    onConfigChange: (AiEndpointConfig) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = config.name,
                onValueChange = { onConfigChange(config.copy(name = it)) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = config.modelName,
                onValueChange = { onConfigChange(config.copy(modelName = it)) },
                label = { Text("Model Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}
