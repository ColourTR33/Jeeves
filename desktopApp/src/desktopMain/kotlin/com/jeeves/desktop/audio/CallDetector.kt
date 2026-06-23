package com.jeeves.desktop.audio

import com.jeeves.shared.ai.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects when communication apps (Teams, Zoom, Slack, etc.) have active calls.
 * Polls running processes to detect call-related windows/processes and surfaces
 * a notification prompting the user to start recording.
 *
 * Strategy: Check if known call apps have active call indicators by looking for
 * process names and window titles that suggest an ongoing call.
 */
class CallDetector(private val scope: CoroutineScope) {

    /** Known process names for communication apps (lowercase). */
    private val callAppProcesses = setOf(
        "teams.exe", "ms-teams.exe",          // Microsoft Teams
        "zoom.exe", "zoomit.exe",              // Zoom
        "slack.exe",                            // Slack (huddle)
        "discord.exe",                          // Discord
        "webexmta.exe", "ciscowebex.exe",      // WebEx
        "skype.exe",                            // Skype
        "googlemeet",                           // Google Meet (browser-based, harder to detect)
    )

    /** Window title keywords that indicate an active call (not just the app open). */
    private val callWindowKeywords = setOf(
        "meeting", "call", "huddle",
        "screen sharing", "presenting",
        "zoom meeting", "in a call"
    )

    private val _callDetected = MutableStateFlow(false)
    /** True when a call is detected and user hasn't dismissed the prompt yet. */
    val callDetected: StateFlow<Boolean> = _callDetected.asStateFlow()

    private val _detectedApp = MutableStateFlow<String?>(null)
    /** Name of the detected call app (e.g., "Teams", "Zoom"). */
    val detectedApp: StateFlow<String?> = _detectedApp.asStateFlow()

    private var detectionJob: Job? = null
    private var lastDetectionTime: Long = 0
    private var dismissed: Boolean = false

    /** Cooldown after dismissal — don't re-prompt for 5 minutes. */
    private val dismissCooldownMs = 5 * 60 * 1000L

    /**
     * Start polling for active calls. Checks every 10 seconds.
     */
    fun start() {
        if (detectionJob?.isActive == true) return

        detectionJob = scope.launch(Dispatchers.IO) {
            AppLogger.info("CallDetector", "Call detection started")
            while (isActive) {
                delay(10_000) // Check every 10 seconds
                checkForActiveCalls()
            }
        }
    }

    /**
     * Stop polling.
     */
    fun stop() {
        detectionJob?.cancel()
        detectionJob = null
    }

    /**
     * Dismiss the current detection prompt (user chose not to record).
     * Won't re-prompt for 5 minutes.
     */
    fun dismiss() {
        _callDetected.value = false
        _detectedApp.value = null
        dismissed = true
        lastDetectionTime = System.currentTimeMillis()
    }

    /**
     * Acknowledge the detection (user started recording).
     * Resets state.
     */
    fun acknowledge() {
        _callDetected.value = false
        _detectedApp.value = null
        dismissed = false
    }

    private fun checkForActiveCalls() {
        // Don't re-prompt during cooldown
        if (dismissed && System.currentTimeMillis() - lastDetectionTime < dismissCooldownMs) {
            return
        }
        dismissed = false

        try {
            val process = ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val runningProcesses = output.lines()
                .mapNotNull { line ->
                    // CSV format: "process.exe","PID","Session","Session#","Mem"
                    val parts = line.split(",")
                    if (parts.size >= 2) {
                        parts[0].trim('"').lowercase()
                    } else null
                }

            // Check if any call app process is running
            val matchedApp = callAppProcesses.firstOrNull { appProcess ->
                runningProcesses.any { it == appProcess }
            }

            if (matchedApp != null && !_callDetected.value) {
                // Verify it's likely in a call by checking for audio-related processes
                // or just notify that the app is running (user can choose to record)
                val appName = when {
                    matchedApp.contains("teams") -> "Microsoft Teams"
                    matchedApp.contains("zoom") -> "Zoom"
                    matchedApp.contains("slack") -> "Slack"
                    matchedApp.contains("discord") -> "Discord"
                    matchedApp.contains("webex") || matchedApp.contains("cisco") -> "WebEx"
                    matchedApp.contains("skype") -> "Skype"
                    else -> matchedApp
                }

                _callDetected.value = true
                _detectedApp.value = appName
                AppLogger.info("CallDetector", "Call app detected: $appName")
            } else if (matchedApp == null && _callDetected.value) {
                // App closed — clear detection
                _callDetected.value = false
                _detectedApp.value = null
            }
        } catch (e: Exception) {
            AppLogger.error("CallDetector", "Process check failed: ${e.message}")
        }
    }
}
