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
import com.jeeves.shared.domain.DiarizationMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(AppSettings()) }
    var isSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings = appState.settingsRepository.getSettings()
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
                }
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
