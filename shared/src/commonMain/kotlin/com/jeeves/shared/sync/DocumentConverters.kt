package com.jeeves.shared.sync

import com.jeeves.shared.domain.*
import com.jeeves.shared.recording.currentTimeMillis
import kotlinx.serialization.json.*

/**
 * Conversion functions between domain models (Recording, TranscriptionResult, SummaryResult)
 * and CouchDB-compatible documents.
 *
 * Document ID conventions:
 * - Recording:          "recording:{id}"
 * - TranscriptionResult: "transcription:{recordingId}"
 * - SummaryResult:       "summary:{recordingId}"
 *
 * **Validates: Requirements 2.1, 2.4, 2.5**
 */

private val converterJson = Json { ignoreUnknownKeys = true }

// =============================================================================
// Domain → CouchDocument converters
// =============================================================================

/**
 * Converts a Recording to a CouchDocument.
 *
 * @param deviceId The originating device identifier.
 * @param modifiedAt Timestamp in epoch milliseconds. Defaults to current time if not provided.
 */
fun Recording.toCouchDocument(
    deviceId: String,
    modifiedAt: Long = currentTimeMillis()
): CouchDocument {
    val body = buildJsonObject {
        put("id", id)
        put("filePath", filePath)
        put("durationMs", durationMs)
        put("createdAt", createdAt)
        put("title", title)
        put("description", description)
        put("template", template.name)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put("folder", folder)
        put("highlights", JsonArray(highlights.map { JsonPrimitive(it) }))
        put("attachments", converterJson.encodeToJsonElement(attachments))
        put("postRecordingNote", postRecordingNote)
    }

    return CouchDocument(
        id = "recording:${id}",
        type = "recording",
        deviceId = deviceId,
        modifiedAt = modifiedAt,
        body = body
    )
}

/**
 * Converts a TranscriptionResult to a CouchDocument.
 *
 * @param deviceId The originating device identifier.
 * @param modifiedAt Timestamp in epoch milliseconds. Defaults to current time if not provided.
 */
fun TranscriptionResult.toCouchDocument(
    deviceId: String,
    modifiedAt: Long = currentTimeMillis()
): CouchDocument {
    val body = buildJsonObject {
        put("recordingId", recordingId)
        put("text", text)
        put("segments", converterJson.encodeToJsonElement(segments))
        put("language", language)
        put("durationMs", durationMs)
        put("diarizationUnavailable", diarizationUnavailable)
    }

    return CouchDocument(
        id = "transcription:${recordingId}",
        type = "transcription",
        deviceId = deviceId,
        modifiedAt = modifiedAt,
        body = body
    )
}

/**
 * Converts a SummaryResult to a CouchDocument.
 *
 * @param deviceId The originating device identifier.
 * @param modifiedAt Timestamp in epoch milliseconds. Defaults to current time if not provided.
 */
fun SummaryResult.toCouchDocument(
    deviceId: String,
    modifiedAt: Long = currentTimeMillis()
): CouchDocument {
    val body = buildJsonObject {
        put("recordingId", recordingId)
        put("summary", summary)
        put("keyPoints", JsonArray(keyPoints.map { JsonPrimitive(it) }))
        put("actionItems", JsonArray(actionItems.map { JsonPrimitive(it) }))
        put("questions", JsonArray(questions.map { JsonPrimitive(it) }))
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put("modelUsed", modelUsed)
        put("recommendedQuestions", JsonArray(recommendedQuestions.map { JsonPrimitive(it) }))
        if (qualityRating != null) {
            put("qualityRating", converterJson.encodeToJsonElement(qualityRating))
        } else {
            put("qualityRating", JsonNull)
        }
    }

    return CouchDocument(
        id = "summary:${recordingId}",
        type = "summary",
        deviceId = deviceId,
        modifiedAt = modifiedAt,
        body = body
    )
}

// =============================================================================
// CouchDocument → Domain converters
// =============================================================================

/**
 * Converts a CouchDocument with type "recording" back to a Recording domain object.
 *
 * @throws IllegalArgumentException if the document type is not "recording" or required fields are missing.
 */
fun CouchDocument.toRecording(): Recording {
    require(type == "recording") { "Document type must be 'recording', got '$type'" }

    val body = this.body
    return Recording(
        id = body.getString("id"),
        filePath = body.getString("filePath"),
        durationMs = body.getLong("durationMs"),
        createdAt = body.getLong("createdAt"),
        title = body.getStringOrDefault("title", "Untitled Meeting"),
        description = body.getStringOrDefault("description", ""),
        template = body.getEnumOrDefault("template", MeetingTemplate.GENERAL),
        tags = body.getStringList("tags"),
        folder = body.getStringOrDefault("folder", ""),
        highlights = body.getLongList("highlights"),
        attachments = body.getAttachments("attachments"),
        postRecordingNote = body.getStringOrDefault("postRecordingNote", "")
    )
}

/**
 * Converts a CouchDocument with type "transcription" back to a TranscriptionResult domain object.
 *
 * @throws IllegalArgumentException if the document type is not "transcription" or required fields are missing.
 */
fun CouchDocument.toTranscription(): TranscriptionResult {
    require(type == "transcription") { "Document type must be 'transcription', got '$type'" }

    val body = this.body
    return TranscriptionResult(
        recordingId = body.getString("recordingId"),
        text = body.getStringOrDefault("text", ""),
        segments = body.getSegments("segments"),
        language = body.getStringOrDefault("language", "en"),
        durationMs = body.getLongOrDefault("durationMs", 0L),
        diarizationUnavailable = body.getBooleanOrDefault("diarizationUnavailable", false)
    )
}

/**
 * Converts a CouchDocument with type "summary" back to a SummaryResult domain object.
 *
 * @throws IllegalArgumentException if the document type is not "summary" or required fields are missing.
 */
fun CouchDocument.toSummary(): SummaryResult {
    require(type == "summary") { "Document type must be 'summary', got '$type'" }

    val body = this.body
    return SummaryResult(
        recordingId = body.getString("recordingId"),
        summary = body.getStringOrDefault("summary", ""),
        keyPoints = body.getStringList("keyPoints"),
        actionItems = body.getStringList("actionItems"),
        questions = body.getStringList("questions"),
        tags = body.getStringList("tags"),
        modelUsed = body.getStringOrDefault("modelUsed", ""),
        recommendedQuestions = body.getStringList("recommendedQuestions"),
        qualityRating = body.getQualityRating("qualityRating")
    )
}

// =============================================================================
// Helper extension functions for JsonObject field extraction
// =============================================================================

private fun JsonObject.getString(key: String): String {
    val element = this[key] ?: throw IllegalArgumentException("Missing required field: $key")
    return element.jsonPrimitive.content
}

private fun JsonObject.getStringOrDefault(key: String, default: String): String {
    val element = this[key] ?: return default
    if (element is JsonNull) return default
    return element.jsonPrimitive.content
}

private fun JsonObject.getLong(key: String): Long {
    val element = this[key] ?: throw IllegalArgumentException("Missing required field: $key")
    return element.jsonPrimitive.long
}

private fun JsonObject.getLongOrDefault(key: String, default: Long): Long {
    val element = this[key] ?: return default
    if (element is JsonNull) return default
    return element.jsonPrimitive.long
}

private fun JsonObject.getBooleanOrDefault(key: String, default: Boolean): Boolean {
    val element = this[key] ?: return default
    if (element is JsonNull) return default
    return element.jsonPrimitive.boolean
}

private fun JsonObject.getStringList(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    return element.jsonArray.map { it.jsonPrimitive.content }
}

private fun JsonObject.getLongList(key: String): List<Long> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    return element.jsonArray.map { it.jsonPrimitive.long }
}

private inline fun <reified T : Enum<T>> JsonObject.getEnumOrDefault(key: String, default: T): T {
    val element = this[key] ?: return default
    if (element is JsonNull) return default
    val name = element.jsonPrimitive.content
    return enumValues<T>().firstOrNull { it.name == name } ?: default
}

private fun JsonObject.getAttachments(key: String): List<Attachment> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    return converterJson.decodeFromJsonElement(element)
}

private fun JsonObject.getSegments(key: String): List<TranscriptionSegment> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    return converterJson.decodeFromJsonElement(element)
}

private fun JsonObject.getQualityRating(key: String): QualityRating? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return converterJson.decodeFromJsonElement(element)
}


