package com.jeeves.shared.ai

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readAudioFile(filePath: String): ByteArray {
    val nsData = NSData.dataWithContentsOfFile(filePath)
        ?: throw IllegalStateException("Failed to read audio file: $filePath")
    return nsData.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    val bytes = ByteArray(length)
    if (length > 0) {
        kotlinx.cinterop.memcpy(
            bytes.refTo(0),
            this.bytes,
            length.toULong()
        )
    }
    return bytes
}

private fun ByteArray.refTo(index: Int): kotlinx.cinterop.CValuesRef<kotlinx.cinterop.ByteVar> {
    return this.usePinned { pinned ->
        pinned.addressOf(index)
    }
}
