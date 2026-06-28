package com.jeeves.desktop.ui

import com.jeeves.desktop.ui.components.estimateDownloadTime
import com.jeeves.desktop.ui.components.formatFileSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for AudioDownloadPrompt utility functions.
 *
 * **Validates: Requirements 4.3**
 */
class AudioDownloadPromptTest {

    // --- formatFileSize tests ---

    @Test
    fun formatFileSize_bytes() {
        assertEquals("512 B", formatFileSize(512))
    }

    @Test
    fun formatFileSize_kilobytes() {
        assertEquals("1.0 KB", formatFileSize(1_024))
        assertEquals("5.5 KB", formatFileSize(5_632))
    }

    @Test
    fun formatFileSize_megabytes() {
        assertEquals("1.0 MB", formatFileSize(1_048_576))
        assertEquals("104.9 MB", formatFileSize(109_951_163)) // ~105 MB
        assertEquals("200.0 MB", formatFileSize(209_715_200))
    }

    @Test
    fun formatFileSize_gigabytes() {
        assertEquals("1.0 GB", formatFileSize(1_073_741_824))
        assertEquals("2.5 GB", formatFileSize(2_684_354_560))
    }

    // --- estimateDownloadTime tests ---

    @Test
    fun estimateDownloadTime_smallFile_lessThanOneSecond() {
        // 100KB at 10 Mbps = ~0.08 seconds
        assertEquals("< 1 second", estimateDownloadTime(100_000))
    }

    @Test
    fun estimateDownloadTime_mediumFile_seconds() {
        // 5 MB at 10 Mbps = ~4 seconds
        assertEquals("4 seconds", estimateDownloadTime(5_000_000))
    }

    @Test
    fun estimateDownloadTime_largeFile_minutes() {
        // 100 MB at 10 Mbps = ~80 seconds = 1 min 20 sec
        assertEquals("1 min 20 sec", estimateDownloadTime(100_000_000))
    }

    @Test
    fun estimateDownloadTime_veryLargeFile_hours() {
        // 5 GB at 10 Mbps = ~4000 seconds = ~1 hr 6 min
        assertEquals("1 hr 6 min", estimateDownloadTime(5_000_000_000))
    }

    @Test
    fun estimateDownloadTime_exactMinute() {
        // At 10 Mbps, 75 MB = 60 seconds exactly
        // 75_000_000 bytes * 8 bits / 10_000_000 bits/sec = 60 sec
        assertEquals("1 min", estimateDownloadTime(75_000_000))
    }
}
