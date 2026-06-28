package com.jeeves.shared.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for new data model serialization.
 *
 * Validates Requirements: 3.2, 4.1, 5.2
 */
class ModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- QualityRating serialization round-trip ---

    @Test
    fun qualityRating_serializationRoundTrip_producesIdenticalObject() {
        val rating = QualityRating(
            pacing = 4,
            questions = 3,
            goalSetting = 5,
            nextSteps = 2,
            overall = 3.5
        )

        val encoded = json.encodeToString(rating)
        val decoded = json.decodeFromString<QualityRating>(encoded)

        assertEquals(rating, decoded)
    }

    @Test
    fun qualityRating_serializationRoundTrip_boundaryValues() {
        val rating = QualityRating(
            pacing = 1,
            questions = 1,
            goalSetting = 1,
            nextSteps = 1,
            overall = 1.0
        )

        val encoded = json.encodeToString(rating)
        val decoded = json.decodeFromString<QualityRating>(encoded)

        assertEquals(rating, decoded)
    }

    @Test
    fun qualityRating_serializationRoundTrip_maxValues() {
        val rating = QualityRating(
            pacing = 5,
            questions = 5,
            goalSetting = 5,
            nextSteps = 5,
            overall = 5.0
        )

        val encoded = json.encodeToString(rating)
        val decoded = json.decodeFromString<QualityRating>(encoded)

        assertEquals(rating, decoded)
    }

    // --- Backward compatibility: Recording without postRecordingNote ---

    @Test
    fun recording_deserializeOldJson_missingPostRecordingNote_defaultsToEmptyString() {
        // Simulate old Recording JSON that was persisted before postRecordingNote was added
        val oldJson = """
            {
                "id": "rec-001",
                "filePath": "/recordings/test.wav",
                "durationMs": 60000,
                "createdAt": 1700000000000,
                "title": "Team Standup",
                "description": "",
                "template": "GENERAL",
                "tags": [],
                "folder": "",
                "highlights": [],
                "attachments": []
            }
        """.trimIndent()

        val recording = json.decodeFromString<Recording>(oldJson)

        assertEquals("rec-001", recording.id)
        assertEquals("", recording.postRecordingNote)
    }

    @Test
    fun recording_withPostRecordingNote_serializesCorrectly() {
        val recording = Recording(
            id = "rec-002",
            filePath = "/recordings/meeting.wav",
            durationMs = 120000,
            createdAt = 1700000000000,
            title = "Design Review",
            postRecordingNote = "Discussed architecture changes offline after the call"
        )

        val encoded = json.encodeToString(recording)
        val decoded = json.decodeFromString<Recording>(encoded)

        assertEquals(recording.postRecordingNote, decoded.postRecordingNote)
        assertEquals("Discussed architecture changes offline after the call", decoded.postRecordingNote)
    }

    // --- Backward compatibility: SummaryResult without new fields ---

    @Test
    fun summaryResult_deserializeOldJson_missingNewFields_producesDefaults() {
        // Simulate old SummaryResult JSON without recommendedQuestions and qualityRating
        val oldJson = """
            {
                "recordingId": "rec-001",
                "summary": "The team discussed sprint progress.",
                "keyPoints": ["Sprint is on track", "No blockers"],
                "actionItems": ["Review PR #42"],
                "questions": ["When is the deadline?"],
                "tags": ["sprint", "planning"],
                "modelUsed": "qwen3:8b"
            }
        """.trimIndent()

        val result = json.decodeFromString<SummaryResult>(oldJson)

        assertEquals("rec-001", result.recordingId)
        assertEquals("The team discussed sprint progress.", result.summary)
        assertEquals(emptyList<String>(), result.recommendedQuestions)
        assertNull(result.qualityRating)
    }

    @Test
    fun summaryResult_withNewFields_serializesCorrectly() {
        val rating = QualityRating(
            pacing = 4,
            questions = 3,
            goalSetting = 5,
            nextSteps = 4,
            overall = 4.0
        )
        val summaryResult = SummaryResult(
            recordingId = "rec-003",
            summary = "Productive meeting with clear outcomes.",
            keyPoints = listOf("Agreed on timeline"),
            actionItems = listOf("Send follow-up email"),
            questions = listOf("Budget approval?"),
            tags = listOf("planning"),
            modelUsed = "gpt-4o",
            recommendedQuestions = listOf(
                "What are the risk mitigation strategies?",
                "Who owns the deployment plan?"
            ),
            qualityRating = rating
        )

        val encoded = json.encodeToString(summaryResult)
        val decoded = json.decodeFromString<SummaryResult>(encoded)

        assertEquals(summaryResult, decoded)
        assertEquals(2, decoded.recommendedQuestions.size)
        assertEquals(rating, decoded.qualityRating)
    }

    // --- CloudLlmConfig serialization ---

    @Test
    fun cloudLlmConfig_serializationRoundTrip_producesIdenticalObject() {
        val config = CloudLlmConfig(
            baseUrl = "https://api.openai.com",
            apiKey = "sk-test-key-12345",
            modelName = "gpt-4o",
            enabled = true
        )

        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<CloudLlmConfig>(encoded)

        assertEquals(config, decoded)
    }

    @Test
    fun cloudLlmConfig_disabledByDefault() {
        val configJson = """
            {
                "baseUrl": "https://api.anthropic.com",
                "apiKey": "sk-ant-test",
                "modelName": "claude-3-sonnet"
            }
        """.trimIndent()

        val config = json.decodeFromString<CloudLlmConfig>(configJson)

        assertEquals("https://api.anthropic.com", config.baseUrl)
        assertEquals("sk-ant-test", config.apiKey)
        assertEquals("claude-3-sonnet", config.modelName)
        assertEquals(false, config.enabled)
    }

    @Test
    fun cloudLlmConfig_enabledTrue_serializesCorrectly() {
        val config = CloudLlmConfig(
            baseUrl = "https://api.groq.com/openai",
            apiKey = "gsk_test_key",
            modelName = "llama3-70b-8192",
            enabled = true
        )

        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<CloudLlmConfig>(encoded)

        assertEquals(true, decoded.enabled)
        assertEquals(config, decoded)
    }
}
