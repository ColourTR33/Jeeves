package com.jeeves.shared.recording

import platform.Foundation.*

actual fun currentTimeMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).toLong()
}

actual fun generateId(): String {
    return NSUUID().UUIDString
}

actual fun generateOutputPath(format: String): String {
    val documentsDir = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true
    ).firstOrNull() as? String ?: ""
    val timestamp = currentTimeMillis()
    return "$documentsDir/recording_$timestamp.$format"
}
