package com.jeeves.shared.recording

import java.io.File
import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun generateId(): String = UUID.randomUUID().toString()

actual fun generateOutputPath(format: String): String {
    val recordingsDir = File(System.getProperty("user.home"), "Jeeves/recordings")
    recordingsDir.mkdirs()
    val timestamp = System.currentTimeMillis()
    return File(recordingsDir, "recording_$timestamp.$format").absolutePath
}
