package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.AiEndpointType
import com.jeeves.shared.domain.AppSettings
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

        Spacer(modifier = Modifier.weight(1f))

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
