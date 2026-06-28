package com.jeeves.desktop.data

import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports recordings to an Obsidian vault as Markdown notes with YAML frontmatter and tags.
 * Default vault path: ~/Obsidian/Jeeves/ (configurable)
 */
class ObsidianExportService {

    var vaultPath: String = System.getProperty("user.home") + "/Obsidian/Jeeves"

    fun exportToVault(
        recording: Recording,
        transcription: TranscriptionResult?,
        summary: SummaryResult?
    ): File? {
        val dir = File(vaultPath)
        dir.mkdirs()

        val dateStr = formatDate(recording.createdAt)
        val fileName = "${dateStr}_${sanitizeFilename(recording.title)}.md"
        val file = File(dir, fileName)

        val content = buildMarkdownNote(recording, transcription, summary)
        file.writeText(content)
        return file
    }

    private fun buildMarkdownNote(
        recording: Recording,
        transcription: TranscriptionResult?,
        summary: SummaryResult?
    ): String {
        val tags = recording.tags.ifEmpty { listOf("meeting") }
        val tagYaml = tags.joinToString("\n") { "  - $it" }
        val date = formatDateTime(recording.createdAt)

        return buildString {
            // YAML frontmatter
            appendLine("---")
            appendLine("title: \"${recording.title}\"")
            appendLine("date: $date")
            appendLine("type: meeting")
            appendLine("template: ${recording.template.name.lowercase()}")
            if (recording.folder.isNotBlank()) appendLine("folder: ${recording.folder}")
            appendLine("duration: ${formatDuration(recording.durationMs)}")
            appendLine("tags:")
            appendLine(tagYaml)
            appendLine("---")
            appendLine()

            // Content
            appendLine("# ${recording.title}")
            appendLine()
            appendLine("📅 $date · ⏱ ${formatDuration(recording.durationMs)}")
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
                    transcription.segments.forEach { seg ->
                        val ts = formatTimestamp(seg.startMs)
                        val speaker = seg.speaker?.let { "**$it:** " } ?: ""
                        appendLine("> [$ts] $speaker${seg.text}")
                    }
                } else {
                    appendLine(transcription.text)
                }
            }

            appendLine()
            appendLine("---")
            appendLine("*Exported from Jeeves*")
        }
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").replace(" ", "_").take(50)

    private fun formatDate(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    private fun formatDateTime(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val h = m / 60
        return if (h > 0) "${h}h ${m % 60}m" else if (m > 0) "${m}m ${s % 60}s" else "${s}s"
    }

    private fun formatTimestamp(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return String.format("%d:%02d", m, s % 60)
    }
}
