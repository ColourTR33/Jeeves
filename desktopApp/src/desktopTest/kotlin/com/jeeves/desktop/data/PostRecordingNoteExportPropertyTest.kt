package com.jeeves.desktop.data

import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

// Feature: meeting-intelligence, Property 5: Post-recording note included in export

/**
 * Property-based test for post-recording note inclusion in export output.
 *
 * For any recording with a non-empty postRecordingNote, the exported
 * markdown or text output should contain the note content as a substring.
 *
 * **Validates: Requirements 3.5**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class PostRecordingNoteExportPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    private val exportService = ExportService()

    /**
     * Property 5: Post-recording note included in export
     *
     * For any recording with a non-empty note and any export format:
     * 1. Create a recording with a non-blank postRecordingNote
     * 2. Export using formatContent (markdown or text)
     * 3. Verify the exported output contains the note as a substring
     *
     * **Validates: Requirements 3.5**
     */
    @Test
    fun property5_postRecordingNoteIncludedInExport_containsNoteAsSubstring() = runTest {
        checkAll(
            config,
            Arb.string(1..100).filter { it.isNotBlank() },
            Arb.enum<ExportFormat>()
        ) { note, format ->
            val recording = Recording(
                id = "test-recording",
                filePath = "/tmp/test.wav",
                durationMs = 60000L,
                createdAt = System.currentTimeMillis(),
                title = "Test Meeting",
                postRecordingNote = note
            )

            val exportedContent = exportService.formatContent(
                recording = recording,
                transcription = null,
                summary = null,
                format = format
            )

            assertTrue(
                exportedContent.contains(note),
                "Exported $format output should contain the post-recording note as a substring. " +
                    "Note: \"$note\", Export content: \"$exportedContent\""
            )
        }
    }
}
