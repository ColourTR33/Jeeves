package com.jeeves.desktop.data

import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

enum class ExportFormat { MARKDOWN, TEXT }

class ExportService {

    /**
     * Formats recording content for export without triggering file save dialog.
     * Exposed as internal for testing purposes.
     */
    internal fun formatContent(
        recording: Recording,
        transcription: TranscriptionResult?,
        summary: SummaryResult?,
        format: ExportFormat
    ): String {
        return when (format) {
            ExportFormat.MARKDOWN -> formatAsMarkdown(recording, transcription, summary)
            ExportFormat.TEXT -> formatAsText(recording, transcription, summary)
        }
    }

    fun exportRecording(
        recording: Recording,
        transcription: TranscriptionResult?,
        summary: SummaryResult?,
        format: ExportFormat
    ): Boolean {
        val content = formatContent(recording, transcription, summary, format)

        val extension = when (format) {
            ExportFormat.MARKDOWN -> "md"
            ExportFormat.TEXT -> "txt"
        }

        val defaultName = "${recording.title.replace(" ", "_")}_${formatDate(recording.createdAt)}.$extension"

        val chooser = JFileChooser().apply {
            dialogTitle = "Export Recording"
            selectedFile = File(defaultName)
            fileFilter = FileNameExtensionFilter(
                if (format == ExportFormat.MARKDOWN) "Markdown files" else "Text files",
                extension
            )
        }

        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.endsWith(".$extension")) {
                file = File(file.absolutePath + ".$extension")
            }
            file.writeText(content)
            true
        } else {
            false
        }
    }

    private fun formatAsMarkdown(
        recording: Recording,
        transcription: TranscriptionResult?,
        summary: SummaryResult?
    ): String {
        return buildString {
            appendLine("# ${recording.title}")
            appendLine()
            appendLine("**Date:** ${formatDateTime(recording.createdAt)}")
            appendLine("**Duration:** ${formatDuration(recording.durationMs)}")
            appendLine()

            if (summary != null) {
                appendLine("## Summary")
                appendLine()
                appendLine(summary.summary)
                appendLine()

                if (summary.keyPoints.isNotEmpty()) {
                    appendLine("## Key Points")
                    appendLine()
                    summary.keyPoints.forEach { appendLine("- $it") }
                    appendLine()
                }

                if (summary.actionItems.isNotEmpty()) {
                    appendLine("## Action Items")
                    appendLine()
                    summary.actionItems.forEach { appendLine("- [ ] $it") }
                    appendLine()
                }
            }

            if (recording.postRecordingNote.isNotBlank()) {
                appendLine("## Notes")
                appendLine()
                appendLine(recording.postRecordingNote)
                appendLine()
            }

            if (transcription != null) {
                appendLine("## Transcription")
                appendLine()
                if (transcription.segments.isNotEmpty()) {
                    transcription.segments.forEach { segment ->
                        val timestamp = formatTimestamp(segment.startMs)
                        val speaker = segment.speaker?.let { "**$it:** " } ?: ""
                        appendLine("[$timestamp] $speaker${segment.text}")
                    }
                } else {
                    appendLine(transcription.text)
                }
            }
        }
    }

    private fun formatAsText(
        recording: Recording,
        transcription: TranscriptionResult?,
        summary: SummaryResult?
    ): String {
        return buildString {
            appendLine(recording.title)
            appendLine("=".repeat(recording.title.length))
            appendLine()
            appendLine("Date: ${formatDateTime(recording.createdAt)}")
            appendLine("Duration: ${formatDuration(recording.durationMs)}")
            appendLine()

            if (summary != null) {
                appendLine("SUMMARY")
                appendLine("-".repeat(7))
                appendLine(summary.summary)
                appendLine()

                if (summary.keyPoints.isNotEmpty()) {
                    appendLine("KEY POINTS")
                    appendLine("-".repeat(10))
                    summary.keyPoints.forEach { appendLine("• $it") }
                    appendLine()
                }

                if (summary.actionItems.isNotEmpty()) {
                    appendLine("ACTION ITEMS")
                    appendLine("-".repeat(12))
                    summary.actionItems.forEach { appendLine("☐ $it") }
                    appendLine()
                }
            }

            if (recording.postRecordingNote.isNotBlank()) {
                appendLine("NOTES")
                appendLine("-".repeat(5))
                appendLine(recording.postRecordingNote)
                appendLine()
            }

            if (transcription != null) {
                appendLine("TRANSCRIPTION")
                appendLine("-".repeat(13))
                if (transcription.segments.isNotEmpty()) {
                    transcription.segments.forEach { segment ->
                        val timestamp = formatTimestamp(segment.startMs)
                        val speaker = segment.speaker?.let { "$it: " } ?: ""
                        appendLine("[$timestamp] $speaker${segment.text}")
                    }
                } else {
                    appendLine(transcription.text)
                }
            }
        }
    }

    private fun formatDate(timestamp: Long): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    private fun formatDateTime(timestamp: Long): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return date.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) "${hours}h ${minutes % 60}m"
        else if (minutes > 0) "${minutes}m ${seconds % 60}s"
        else "${seconds}s"
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
