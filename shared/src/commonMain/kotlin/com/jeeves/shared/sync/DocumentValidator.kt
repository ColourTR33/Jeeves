package com.jeeves.shared.sync

import kotlinx.serialization.json.*

/**
 * Validates CouchDocument body content against known schemas for
 * Recording, TranscriptionResult, and SummaryResult document types.
 *
 * Used during replication writes (newEdits = false) to reject malformed
 * documents from remote sources while allowing them to be logged and skipped.
 *
 * **Validates: Requirements 7.5, 7.6**
 */
object DocumentValidator {

    /**
     * Result of validating a document.
     */
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * Validates a CouchDocument against its type schema.
     * Returns [ValidationResult.Valid] if the document passes validation,
     * or [ValidationResult.Invalid] with a reason string if it fails.
     *
     * Documents with unknown types are considered valid (pass-through)
     * to allow forward compatibility.
     */
    fun validate(doc: CouchDocument): ValidationResult {
        return when (doc.type) {
            "recording" -> validateRecording(doc.body)
            "transcription" -> validateTranscription(doc.body)
            "summary" -> validateSummary(doc.body)
            else -> ValidationResult.Valid // Unknown types pass through
        }
    }

    /**
     * Validates a Recording document body.
     *
     * Required fields:
     * - id: String
     * - filePath: String
     * - durationMs: number (Long)
     * - createdAt: number (Long)
     */
    private fun validateRecording(body: JsonObject): ValidationResult {
        return validateFields(body, listOf(
            FieldSpec("id", FieldType.STRING),
            FieldSpec("filePath", FieldType.STRING),
            FieldSpec("durationMs", FieldType.NUMBER),
            FieldSpec("createdAt", FieldType.NUMBER)
        ))
    }

    /**
     * Validates a TranscriptionResult document body.
     *
     * Required fields:
     * - recordingId: String
     * - text: String
     */
    private fun validateTranscription(body: JsonObject): ValidationResult {
        return validateFields(body, listOf(
            FieldSpec("recordingId", FieldType.STRING),
            FieldSpec("text", FieldType.STRING)
        ))
    }

    /**
     * Validates a SummaryResult document body.
     *
     * Required fields:
     * - recordingId: String
     * - summary: String
     */
    private fun validateSummary(body: JsonObject): ValidationResult {
        return validateFields(body, listOf(
            FieldSpec("recordingId", FieldType.STRING),
            FieldSpec("summary", FieldType.STRING)
        ))
    }

    private fun validateFields(body: JsonObject, specs: List<FieldSpec>): ValidationResult {
        for (spec in specs) {
            val element = body[spec.name]
            if (element == null || element is JsonNull) {
                return ValidationResult.Invalid("Missing required field '${spec.name}'")
            }
            val typeError = checkFieldType(element, spec)
            if (typeError != null) {
                return ValidationResult.Invalid(typeError)
            }
        }
        return ValidationResult.Valid
    }

    private fun checkFieldType(element: JsonElement, spec: FieldSpec): String? {
        return when (spec.type) {
            FieldType.STRING -> {
                if (element !is JsonPrimitive || !element.isString) {
                    "Field '${spec.name}' must be a string, got ${describeType(element)}"
                } else null
            }
            FieldType.NUMBER -> {
                if (element !is JsonPrimitive) {
                    "Field '${spec.name}' must be a number, got ${describeType(element)}"
                } else {
                    // Check it's actually a number (not a string or boolean)
                    val content = element.content
                    if (element.isString) {
                        "Field '${spec.name}' must be a number, got string"
                    } else if (content == "true" || content == "false") {
                        "Field '${spec.name}' must be a number, got boolean"
                    } else if (content.toLongOrNull() == null && content.toDoubleOrNull() == null) {
                        "Field '${spec.name}' must be a number, got non-numeric value"
                    } else null
                }
            }
        }
    }

    private fun describeType(element: JsonElement): String {
        return when (element) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> when {
                element.isString -> "string"
                element.content == "true" || element.content == "false" -> "boolean"
                else -> "number"
            }
            else -> "unknown"
        }
    }

    private data class FieldSpec(val name: String, val type: FieldType)

    private enum class FieldType {
        STRING,
        NUMBER
    }
}
