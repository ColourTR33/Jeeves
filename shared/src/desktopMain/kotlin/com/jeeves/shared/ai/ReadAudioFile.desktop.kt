package com.jeeves.shared.ai

import java.io.File

actual suspend fun readAudioFile(filePath: String): ByteArray {
    return File(filePath).readBytes()
}
