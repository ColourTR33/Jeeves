package com.jeeves.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jeeves.shared.domain.AiEndpointConfig
import com.jeeves.shared.domain.AiEndpointType
import com.jeeves.shared.domain.AppSettings
import com.jeeves.shared.domain.AudioSource
import com.jeeves.shared.domain.CloudLlmConfig
import com.jeeves.shared.domain.DiarizationMode
import com.jeeves.shared.domain.MeetingTemplate
import com.jeeves.shared.domain.validateChunkInterval
import com.jeeves.shared.domain.validateOverlapWindow
import com.jeeves.shared.domain.validateOverlapLessThanInterval
import com.jeeves.shared.ai.PromptTemplateManager
import com.jeeves.shared.sync.ConnectionErrorType
import com.jeeves.shared.sync.ConnectionTestResult
import com.jeeves.shared.sync.SyncConfiguration
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

        // LLM Provider selection (Local vs Cloud)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("LLM Provider", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Provider toggle
                var isCloudEnabled by remember(settings) {
                    mutableStateOf(settings.cloudLlmConfig?.enabled == true)
                }
                var cloudBaseUrl by remember(settings) {
                    mutableStateOf(settings.cloudLlmConfig?.baseUrl ?: "")
                }
                var cloudApiKey by remember(settings) {
                    mutableStateOf(settings.cloudLlmConfig?.apiKey ?: "")
                }
                var cloudModelName by remember(settings) {
                    mutableStateOf(settings.cloudLlmConfig?.modelName ?: "")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isCloudEnabled) "Cloud (OpenAI-compatible)" else "Local (Ollama)")
                    Switch(
                        checked = isCloudEnabled,
                        onCheckedChange = { enabled ->
                            isCloudEnabled = enabled
                            settings = settings.copy(
                                cloudLlmConfig = CloudLlmConfig(
                                    baseUrl = cloudBaseUrl,
                                    apiKey = cloudApiKey,
                                    modelName = cloudModelName,
                                    enabled = enabled
                                )
                            )
                            isSaved = false
                        }
                    )
                }

                if (isCloudEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = cloudBaseUrl,
                        onValueChange = { newValue ->
                            cloudBaseUrl = newValue
                            settings = settings.copy(
                                cloudLlmConfig = CloudLlmConfig(
                                    baseUrl = newValue,
                                    apiKey = cloudApiKey,
                                    modelName = cloudModelName,
                                    enabled = true
                                )
                            )
                            isSaved = false
                        },
                        label = { Text("API Base URL") },
                        placeholder = { Text("https://api.openai.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = cloudApiKey,
                        onValueChange = { newValue ->
                            cloudApiKey = newValue
                            settings = settings.copy(
                                cloudLlmConfig = CloudLlmConfig(
                                    baseUrl = cloudBaseUrl,
                                    apiKey = newValue,
                                    modelName = cloudModelName,
                                    enabled = true
                                )
                            )
                            isSaved = false
                        },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = cloudModelName,
                        onValueChange = { newValue ->
                            cloudModelName = newValue
                            settings = settings.copy(
                                cloudLlmConfig = CloudLlmConfig(
                                    baseUrl = cloudBaseUrl,
                                    apiKey = cloudApiKey,
                                    modelName = newValue,
                                    enabled = true
                                )
                            )
                            isSaved = false
                        },
                        label = { Text("Model Name") },
                        placeholder = { Text("gpt-4o") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Uses the OpenAI-compatible /v1/chat/completions API format.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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

        // System Audio Capture (for recording both sides of calls)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("System Audio Capture", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Capture system audio (call participants)")
                        Text(
                            "Records what comes out of your speakers alongside your mic, so both sides of calls are captured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.captureSystemAudio,
                        onCheckedChange = {
                            settings = settings.copy(captureSystemAudio = it)
                            isSaved = false
                        }
                    )
                }

                if (settings.captureSystemAudio) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val loopbackAvailable = remember { appState.audioRecorder.let {
                        com.jeeves.desktop.audio.SystemAudioCapture().isAvailable()
                    }}
                    if (loopbackAvailable) {
                        Text(
                            "✓ System audio device detected. Both sides of calls will be recorded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            com.jeeves.desktop.audio.SystemAudioCapture().getSetupInstructions(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
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

                // Mode selection - only shown when diarization is enabled
                if (settings.diarizationEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Mode", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    // Pyannote radio button (recommended)
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.diarizationMode == DiarizationMode.PYANNOTE,
                            onClick = {
                                settings = settings.copy(diarizationMode = DiarizationMode.PYANNOTE)
                                isSaved = false
                            }
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text("Pyannote (recommended)")
                            Text(
                                "Accurate speaker identification via local pyannote server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

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
                        Text("TinyDiarize (legacy)", modifier = Modifier.padding(start = 4.dp))
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

                    // Pyannote server URL - shown when PYANNOTE mode selected
                    if (settings.diarizationMode == DiarizationMode.PYANNOTE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = settings.diarizationServerUrl,
                            onValueChange = {
                                settings = settings.copy(diarizationServerUrl = it)
                                isSaved = false
                            },
                            label = { Text("Diarization Server URL") },
                            placeholder = { Text("http://localhost:8180") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Start with: cd diarization-server && python server.py",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // --- Dedicated streaming endpoint (optional) ---
                    Text("Dedicated Streaming Server (optional)", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Run a second whisper-server instance on a different port so live transcription " +
                            "chunks don't compete with post-recording full-file transcription. " +
                            "Leave blank to share the main Transcription endpoint above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val hasStreamingEndpoint = settings.streamingTranscriptionEndpoint != null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Use separate streaming server")
                        Switch(
                            checked = hasStreamingEndpoint,
                            onCheckedChange = { enable ->
                                settings = if (enable) {
                                    // Pre-fill with main endpoint URL but bump port by 1 as a hint
                                    val mainUrl = settings.transcriptionEndpoint.baseUrl
                                    val suggestedUrl = mainUrl.replace(Regex(":(\\d+)$")) { mr ->
                                        ":${(mr.groupValues[1].toIntOrNull() ?: 8178) + 1}"
                                    }
                                    settings.copy(
                                        streamingTranscriptionEndpoint = AiEndpointConfig(
                                            name = "Streaming Whisper",
                                            baseUrl = suggestedUrl,
                                            modelName = settings.transcriptionEndpoint.modelName,
                                            type = AiEndpointType.WHISPER_TRANSCRIPTION
                                        )
                                    )
                                } else {
                                    settings.copy(streamingTranscriptionEndpoint = null)
                                }
                                isSaved = false
                            }
                        )
                    }

                    if (hasStreamingEndpoint) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.streamingTranscriptionEndpoint!!.baseUrl,
                            onValueChange = { url ->
                                settings = settings.copy(
                                    streamingTranscriptionEndpoint = settings.streamingTranscriptionEndpoint!!.copy(baseUrl = url)
                                )
                                isSaved = false
                            },
                            label = { Text("Streaming Server URL") },
                            placeholder = { Text("http://localhost:8179") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Start a second instance: ./server -m models/ggml-small.bin -p 8179",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
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

        // Prompt Template Editor
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Prompt Templates", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Template type dropdown
                var templateExpanded by remember { mutableStateOf(false) }
                var selectedTemplate by remember { mutableStateOf(MeetingTemplate.GENERAL) }
                var templateText by remember { mutableStateOf("") }

                // Load effective template text when selection changes or settings load
                LaunchedEffect(selectedTemplate, settings.promptTemplates) {
                    val custom = settings.promptTemplates[selectedTemplate]
                    templateText = if (custom.isNullOrBlank()) {
                        PromptTemplateManager.DEFAULT_PROMPTS[selectedTemplate]
                            ?: PromptTemplateManager.DEFAULT_PROMPTS[MeetingTemplate.GENERAL]!!
                    } else {
                        custom
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = templateExpanded,
                    onExpandedChange = { templateExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedTemplate.name.replace("_", " "),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Meeting Type") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = templateExpanded,
                        onDismissRequest = { templateExpanded = false }
                    ) {
                        MeetingTemplate.entries.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.name.replace("_", " ")) },
                                onClick = {
                                    selectedTemplate = template
                                    templateExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Multi-line template editor
                OutlinedTextField(
                    value = templateText,
                    onValueChange = { newText ->
                        templateText = newText
                        // Update the promptTemplates map in settings
                        val updatedTemplates = settings.promptTemplates.toMutableMap()
                        updatedTemplates[selectedTemplate] = newText
                        settings = settings.copy(promptTemplates = updatedTemplates)
                        isSaved = false
                    },
                    label = { Text("Prompt Template") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Reset to Default button
                TextButton(onClick = {
                    val defaultPrompt = PromptTemplateManager.DEFAULT_PROMPTS[selectedTemplate]
                        ?: PromptTemplateManager.DEFAULT_PROMPTS[MeetingTemplate.GENERAL]!!
                    templateText = defaultPrompt
                    // Remove the custom template entry to fall back to default
                    val updatedTemplates = settings.promptTemplates.toMutableMap()
                    updatedTemplates.remove(selectedTemplate)
                    settings = settings.copy(promptTemplates = updatedTemplates)
                    isSaved = false
                }) {
                    Text("Reset to Default")
                }
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

        // Sync Configuration
        SyncSettingsSection(
            settings = settings,
            onSettingsChange = { newSettings ->
                settings = newSettings
                isSaved = false
            }
        )

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

/**
 * Formats a relative time string for sync timestamp display.
 * Shows relative duration for <24h, absolute date/time for ≥24h.
 */
private fun formatSyncTimestamp(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - epochMillis
    val diffSeconds = diffMs / 1000
    val diffMinutes = diffSeconds / 60
    val diffHours = diffMinutes / 60
    val twentyFourHoursMs = 24 * 60 * 60 * 1000L

    return if (diffMs < twentyFourHoursMs) {
        when {
            diffSeconds < 60 -> "Just now"
            diffMinutes < 60 -> "${diffMinutes} minute${if (diffMinutes != 1L) "s" else ""} ago"
            else -> "${diffHours} hour${if (diffHours != 1L) "s" else ""} ago"
        }
    } else {
        val instant = java.time.Instant.ofEpochMilli(epochMillis)
        val dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        dateTime.format(formatter)
    }
}

/**
 * Sync configuration section for the Settings screen.
 * Provides input fields for CouchDB remote URL, username, password (masked),
 * a "Test Connection" button, enable/disable toggle, audio download policy selector,
 * and last successful sync timestamp.
 *
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.8, 4.5, 6.4
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncSettingsSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    val syncEngine = appState.syncEngine

    // Local state for connection test
    var connectionTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // Validation state for empty fields
    var emptyFieldErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    // Observe last sync timestamp from SyncEngine
    val lastSyncTimestamp = syncEngine?.lastSyncTimestamp?.collectAsState()?.value

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Enable/disable sync toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable synchronization")
                Switch(
                    checked = settings.syncEnabled,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(syncEnabled = enabled))
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Remote Database URL
            OutlinedTextField(
                value = settings.syncRemoteUrl,
                onValueChange = { newValue ->
                    onSettingsChange(settings.copy(syncRemoteUrl = newValue))
                    emptyFieldErrors = emptyFieldErrors - "URL"
                },
                label = { Text("Remote Database URL") },
                placeholder = { Text("https://couch.example.com/jeeves") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = "URL" in emptyFieldErrors
            )
            if ("URL" in emptyFieldErrors) {
                Text(
                    text = "URL is required",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Username
            OutlinedTextField(
                value = settings.syncUsername,
                onValueChange = { newValue ->
                    onSettingsChange(settings.copy(syncUsername = newValue))
                    emptyFieldErrors = emptyFieldErrors - "username"
                },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = "username" in emptyFieldErrors
            )
            if ("username" in emptyFieldErrors) {
                Text(
                    text = "Username is required",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Password (masked)
            OutlinedTextField(
                value = settings.syncPassword,
                onValueChange = { newValue ->
                    onSettingsChange(settings.copy(syncPassword = newValue))
                    emptyFieldErrors = emptyFieldErrors - "password"
                },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = "password" in emptyFieldErrors
            )
            if ("password" in emptyFieldErrors) {
                Text(
                    text = "Password is required",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Test Connection button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        // Validate empty fields first (Requirement 1.8)
                        val config = SyncConfiguration(
                            remoteUrl = settings.syncRemoteUrl,
                            username = settings.syncUsername,
                            encryptedPassword = settings.syncPassword,
                            enabled = settings.syncEnabled
                        )
                        val missingFields = config.findEmptyFields()
                        if (missingFields.isNotEmpty()) {
                            emptyFieldErrors = missingFields
                            connectionTestResult = null
                            return@Button
                        }
                        emptyFieldErrors = emptyList()

                        // Test the connection
                        isTesting = true
                        connectionTestResult = null
                        scope.launch {
                            try {
                                val result = syncEngine?.testConnection(config)
                                    ?: ConnectionTestResult(
                                        success = false,
                                        errorType = null,
                                        message = "Sync engine not initialized"
                                    )
                                connectionTestResult = result
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test Connection")
                }

                // Connection test result feedback
                connectionTestResult?.let { result ->
                    if (result.success) {
                        Text(
                            "✓ Connected successfully",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        val errorText = when (result.errorType) {
                            ConnectionErrorType.INVALID_URL -> "Invalid URL format"
                            ConnectionErrorType.NETWORK_UNREACHABLE -> "Network unreachable"
                            ConnectionErrorType.AUTHENTICATION_FAILED -> "Authentication failed"
                            ConnectionErrorType.TLS_ERROR -> "TLS certificate error"
                            ConnectionErrorType.TIMEOUT -> "Connection timed out"
                            null -> result.message
                        }
                        Text(
                            "✗ $errorText",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))

            // Audio download policy selector (Requirement 4.5)
            Text("Audio Download Policy", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            val audioDownloadPolicies = listOf(
                "ALWAYS" to "Always download",
                "WIFI_ONLY" to "Download on Wi-Fi only",
                "ON_DEMAND" to "Download on demand"
            )

            audioDownloadPolicies.forEach { (value, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.syncAudioDownloadPolicy == value,
                        onClick = {
                            onSettingsChange(settings.copy(syncAudioDownloadPolicy = value))
                        }
                    )
                    Text(label, modifier = Modifier.padding(start = 4.dp))
                }
            }

            // Last successful sync timestamp (Requirement 6.4)
            val displayTimestamp = lastSyncTimestamp
            if (displayTimestamp != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Last synced: ${formatSyncTimestamp(displayTimestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
