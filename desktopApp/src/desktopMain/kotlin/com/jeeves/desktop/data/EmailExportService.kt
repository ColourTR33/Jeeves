package com.jeeves.desktop.data

import com.jeeves.shared.domain.Recording
import com.jeeves.shared.domain.SummaryResult
import com.jeeves.shared.domain.TranscriptionResult
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Opens the user's default email client with a pre-populated meeting recap email.
 */
class EmailExportService {

    fun sendRecap(recording: Recording, transcription: TranscriptionResult?, summary: SummaryResult?): Boolean {
        val subject = "Meeting Recap: ${recording.title}"
        val body = buildEmailBody(recording, summary)

        val encodedSubject = URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
        val encodedBody = URLEncoder.encode(body, "UTF-8").replace("+", "%20")

        val mailto = URI("mailto:?subject=$encodedSubject&body=$encodedBody")

        return try {
            Desktop.getDesktop().mail(mailto)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildEmailBody(recording: Recording, summary: SummaryResult?): String {
        return buildString {
            appendLine("Meeting: ${recording.title}")
            appendLine("Date: ${formatDateTime(recording.createdAt)}")
            appendLine()
            if (summary != null) {
                appendLine("SUMMARY")
                appendLine(summary.summary)
                appendLine()
                if (summary.keyPoints.isNotEmpty()) {
                    appendLine("KEY POINTS")
                    summary.keyPoints.forEach { appendLine("• $it") }
                    appendLine()
                }
                if (summary.actionItems.isNotEmpty()) {
                    appendLine("ACTION ITEMS")
                    summary.actionItems.forEach { appendLine("☐ $it") }
                }
            } else {
                appendLine("(No summary available)")
            }
        }
    }

    private fun formatDateTime(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
    }
}
