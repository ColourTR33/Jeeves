package com.jeeves.shared.ai

import com.jeeves.shared.domain.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for local LLM summarization via Ollama-compatible API.
 * Supports /api/generate (Ollama) and /v1/chat/completions (OpenAI-compatible).
 */
class OllamaClient(
    private val httpClient: HttpClient
) : SummarizationService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun summarize(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String,
        attachmentCount: Int,
        promptTemplate: String,
        meetingTemplate: MeetingTemplate,
        cloudLlmConfig: CloudLlmConfig?
    ): SummaryResult {
        val wordCount = transcription.text.split("\\s+".toRegex()).size
        AppLogger.info("OllamaClient", "Summarizing ${wordCount} words (model: ${config.modelName})")

        // For long transcripts (>2000 words), use chunked summarization
        // Threshold lowered from 4000 to 2000 to stay safely within qwen3:8b context window
        return if (wordCount > 2000) {
            summarizeChunked(transcription, config, description, attachmentCount, promptTemplate, meetingTemplate, cloudLlmConfig)
        } else {
            summarizeDirect(transcription, config, description, attachmentCount, promptTemplate, meetingTemplate, cloudLlmConfig)
        }
    }

    private suspend fun summarizeDirect(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String,
        attachmentCount: Int,
        promptTemplate: String,
        meetingTemplate: MeetingTemplate,
        cloudLlmConfig: CloudLlmConfig?
    ): SummaryResult {
        val prompt = buildSummarizationPrompt(transcription, description, attachmentCount, promptTemplate, meetingTemplate)

        val response = if (cloudLlmConfig?.enabled == true) {
            // Route to cloud LLM provider
            val cloudConfig = AiEndpointConfig(
                name = "Cloud LLM",
                baseUrl = cloudLlmConfig.baseUrl,
                modelName = cloudLlmConfig.modelName,
                type = AiEndpointType.LLM_SUMMARIZATION
            )
            callOpenAiCompatibleApi(cloudConfig, prompt, apiKey = cloudLlmConfig.apiKey)
        } else {
            // Use existing local Ollama flow with fallback
            try {
                callOllamaApi(config, prompt)
            } catch (e: Exception) {
                callOpenAiCompatibleApi(config, prompt)
            }
        }

        return parseSummaryResponse(transcription.recordingId, response, 
            if (cloudLlmConfig?.enabled == true) cloudLlmConfig.modelName else config.modelName)
    }

    /**
     * Chunked summarization for long transcripts (map-reduce).
     * 1. Split transcript into ~1500-word chunks (fits within qwen3:8b context)
     * 2. Summarize each chunk independently (key points + actions only)
     * 3. If combined chunk summaries exceed 2000 words, recursively reduce
     * 4. Final pass produces the full structured output
     */
    private suspend fun summarizeChunked(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String,
        attachmentCount: Int,
        promptTemplate: String,
        meetingTemplate: MeetingTemplate,
        cloudLlmConfig: CloudLlmConfig? = null
    ): SummaryResult {
        val words = transcription.text.split("\\s+".toRegex())
        // 1500 words per chunk keeps prompt well within 8K token context for qwen3:8b
        val chunkSize = 1500
        val chunks = words.chunked(chunkSize).map { it.joinToString(" ") }

        AppLogger.info("OllamaClient", "Chunked summarization: ${words.size} words -> ${chunks.size} chunks of ~$chunkSize words")

        // Resolve cloud config for routing
        val cloudEndpointConfig = if (cloudLlmConfig?.enabled == true) {
            AiEndpointConfig(
                name = "Cloud LLM",
                baseUrl = cloudLlmConfig.baseUrl,
                modelName = cloudLlmConfig.modelName,
                type = AiEndpointType.LLM_SUMMARIZATION
            )
        } else null

        // Phase 1: Map — summarize each chunk
        val chunkSummaries = mutableListOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            AppLogger.info("OllamaClient", "Summarizing chunk ${index + 1}/${chunks.size}")
            val chunkPrompt = """Summarize this section of a meeting transcription (part ${index + 1} of ${chunks.size}).
Provide key points and any action items as bullet points. Be concise (max 200 words).

TRANSCRIPTION SECTION:
$chunk"""

            val chunkResponse = callLlm(config, chunkPrompt, cloudEndpointConfig, cloudLlmConfig)
            if (chunkResponse.isBlank()) {
                AppLogger.warn("OllamaClient", "Chunk ${index + 1} returned empty response — skipping")
                continue
            }
            chunkSummaries.add("--- Part ${index + 1} ---\n$chunkResponse")
        }

        if (chunkSummaries.isEmpty()) {
            AppLogger.error("OllamaClient", "All chunk summarizations returned empty — summarization failed")
            throw RuntimeException("Summarization failed: LLM returned empty responses for all chunks")
        }

        // Phase 1.5: Reduce — if combined chunk summaries are still too long, reduce again
        var combinedChunkText = chunkSummaries.joinToString("\n\n")
        val combinedWordCount = combinedChunkText.split("\\s+".toRegex()).size
        if (combinedWordCount > 2000) {
            AppLogger.info("OllamaClient", "Chunk summaries still large (${combinedWordCount} words), running reduce pass")
            val reduceChunks = combinedChunkText.split("\\s+".toRegex()).chunked(1500).map { it.joinToString(" ") }
            val reducedSummaries = mutableListOf<String>()
            for ((i, rc) in reduceChunks.withIndex()) {
                val reducePrompt = """Condense these meeting notes into a shorter summary (max 300 words). Keep all action items and key decisions.

$rc"""
                val reduced = callLlm(config, reducePrompt, cloudEndpointConfig, cloudLlmConfig)
                if (reduced.isNotBlank()) reducedSummaries.add(reduced)
            }
            combinedChunkText = reducedSummaries.joinToString("\n\n")
        }

        // Phase 2: Final synthesis pass
        val contextSection = if (description.isNotBlank()) "\nMeeting context/agenda: $description\n" else ""
        val attachmentNote = if (attachmentCount > 0) "\n$attachmentCount screenshot(s) were captured.\n" else ""

        val finalPrompt = """Below are summaries of different parts of a meeting. Combine them into a single cohesive meeting summary.
$contextSection$attachmentNote
Provide:
1. A concise summary (2-3 paragraphs)
2. Key points discussed (bullet points)
3. Action items identified (bullet points)
4. Questions raised (bullet points)
5. At least 2 hashtag tags for categorizing this meeting (e.g., #project-name #sprint-review)

Format your response as:
SUMMARY:
[your summary here]

KEY POINTS:
- [point 1]
...

ACTION ITEMS:
- [action 1]
...

QUESTIONS:
- [question 1]
...

TAGS:
#tag1 #tag2

SECTION SUMMARIES:
$combinedChunkText"""

        val finalResponse = callLlm(config, finalPrompt, cloudEndpointConfig, cloudLlmConfig)

        if (finalResponse.isBlank()) {
            AppLogger.error("OllamaClient", "Final synthesis pass returned empty response")
            // Fall back to returning the combined chunk summaries as the summary
            return SummaryResult(
                recordingId = transcription.recordingId,
                summary = combinedChunkText,
                keyPoints = emptyList(),
                actionItems = emptyList(),
                questions = emptyList(),
                tags = emptyList(),
                modelUsed = if (cloudLlmConfig?.enabled == true) cloudLlmConfig.modelName else config.modelName,
                recommendedQuestions = emptyList(),
                qualityRating = null
            )
        }

        return parseSummaryResponse(transcription.recordingId, finalResponse,
            if (cloudLlmConfig?.enabled == true) cloudLlmConfig.modelName else config.modelName)
    }

    /**
     * Unified LLM call that routes to cloud or local with proper error handling.
     * Returns empty string on failure (never throws for individual calls during chunked processing).
     */
    private suspend fun callLlm(
        config: AiEndpointConfig,
        prompt: String,
        cloudEndpointConfig: AiEndpointConfig?,
        cloudLlmConfig: CloudLlmConfig?
    ): String {
        return try {
            if (cloudEndpointConfig != null) {
                callOpenAiCompatibleApi(cloudEndpointConfig, prompt, apiKey = cloudLlmConfig!!.apiKey)
            } else {
                try {
                    callOllamaApi(config, prompt)
                } catch (e: Exception) {
                    AppLogger.warn("OllamaClient", "Ollama /api/generate failed (${e.message}), trying OpenAI-compatible endpoint")
                    callOpenAiCompatibleApi(config, prompt)
                }
            }
        } catch (e: Exception) {
            AppLogger.error("OllamaClient", "LLM call failed: ${e.message}", e)
            ""
        }
    }

    private suspend fun callOllamaApi(config: AiEndpointConfig, prompt: String): String {
        AppLogger.debug("OllamaClient", "Calling Ollama: model=${config.modelName}, prompt_length=${prompt.length} chars (~${prompt.split("\\s+".toRegex()).size} words)")

        val requestBody = OllamaGenerateRequest(
            model = config.modelName,
            prompt = prompt,
            stream = false,
            options = OllamaOptions(num_ctx = 8192)  // Explicitly set context window
        )

        val response = httpClient.post("${config.baseUrl}/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OllamaGenerateRequest.serializer(), requestBody))
        }

        val statusCode = response.status.value
        val responseBody: String = response.body()

        if (statusCode != 200) {
            AppLogger.error("OllamaClient", "Ollama returned HTTP $statusCode: ${responseBody.take(200)}")
            throw RuntimeException("Ollama API error (HTTP $statusCode): ${responseBody.take(200)}")
        }

        val ollamaResponse = json.decodeFromString<OllamaGenerateResponse>(responseBody)

        if (ollamaResponse.response.isBlank()) {
            AppLogger.warn("OllamaClient", "Ollama returned empty response for prompt (${prompt.length} chars)")
        }

        return ollamaResponse.response
    }

    private suspend fun callOpenAiCompatibleApi(config: AiEndpointConfig, prompt: String, apiKey: String? = null): String {
        val requestBody = OpenAiChatRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage(role = "system", content = "You are a meeting summarisation assistant."),
                ChatMessage(role = "user", content = prompt)
            )
        )

        val response = httpClient.post("${config.baseUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            if (!apiKey.isNullOrBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(json.encodeToString(OpenAiChatRequest.serializer(), requestBody))
        }

        val statusCode = response.status.value
        if (statusCode == 401 || statusCode == 403) {
            throw CloudLlmAuthenticationException(
                "Cloud LLM API authentication failed. Please check your API key."
            )
        }

        val responseBody: String = response.body()
        val chatResponse = json.decodeFromString<OpenAiChatResponse>(responseBody)
        return chatResponse.choices.firstOrNull()?.message?.content ?: ""
    }

    internal fun buildSummarizationPrompt(
        transcription: TranscriptionResult,
        description: String = "",
        attachmentCount: Int = 0,
        promptTemplate: String = "",
        meetingTemplate: MeetingTemplate = MeetingTemplate.GENERAL
    ): String {
        val hasSpeakers = hasSpeakerLabels(transcription.segments)

        val formattedText = if (hasSpeakers) {
            formatWithSpeakers(transcription.segments)
        } else {
            transcription.text
        }

        val speakerInstruction = if (hasSpeakers) {
            "\nThe transcription includes speaker labels. When summarising, attribute key points and action items to the speaker who raised them where possible.\n"
        } else {
            ""
        }

        val contextSection = if (description.isNotBlank()) {
            "\nMEETING CONTEXT/AGENDA:\n$description\n"
        } else {
            ""
        }

        val attachmentNote = if (attachmentCount > 0) {
            "\nNote: $attachmentCount screenshot(s) were captured during this meeting for reference.\n"
        } else {
            ""
        }

        // Use custom prompt template as base instruction if non-empty, otherwise use hardcoded default
        val baseInstruction = if (promptTemplate.isNotBlank()) {
            promptTemplate
        } else {
            """
            |Please summarise the following meeting transcription. Provide:
            |1. A concise summary (2-3 paragraphs)
            |2. Key points discussed (bullet points)
            |3. Action items identified (bullet points)
            |4. Questions raised during the meeting (bullet points)
            |5. At least 2 hashtag tags for categorizing this meeting (e.g., #project-name #sprint-review #design-discussion)
            |
            |Format your response as:
            |SUMMARY:
            |[your summary here]
            |
            |KEY POINTS:
            |- [point 1]
            |- [point 2]
            |...
            |
            |ACTION ITEMS:
            |- [action 1]
            |- [action 2]
            |...
            |
            |QUESTIONS:
            |- [question 1]
            |- [question 2]
            |...
            |
            |TAGS:
            |#tag1 #tag2 #tag3
            """.trimMargin()
        }

        // Build follow-up questions section with interview-specific guidance
        val followUpQuestionsSection = buildString {
            append("\n\nFOLLOW_UP_QUESTIONS:\n")
            append("Based on this meeting transcription, suggest 1-5 follow-up questions about topics or gaps that were not fully addressed in the conversation. If no meaningful gaps exist, return an empty list.")
            if (meetingTemplate == MeetingTemplate.INTERVIEW) {
                append("\nFocus follow-up questions on candidate assessment gaps and unexplored competency areas.")
            }
            append("\n\nFormat as:\nFOLLOW_UP_QUESTIONS:\n- [question 1]\n- [question 2]\n...")
        }

        // Conditionally append rating section only if transcription is long enough
        val ratingSection = if (QualityRatingCalculator.shouldRate(transcription.text)) {
            """
            |
            |RATING:
            |Rate this meeting on the following criteria from 1 (poor) to 5 (excellent):
            |Pacing: [1-5]
            |Questions: [1-5]
            |Goal-Setting: [1-5]
            |Summary/Next Steps: [1-5]
            """.trimMargin()
        } else {
            ""
        }

        return buildString {
            append(baseInstruction)
            append(followUpQuestionsSection)
            append(ratingSection)
            append(speakerInstruction)
            append(contextSection)
            append(attachmentNote)
            append("\n\nTRANSCRIPTION:\n")
            append(formattedText)
        }
    }

    internal fun parseSummaryResponse(
        recordingId: String,
        response: String,
        modelName: String
    ): SummaryResult {
        val summarySection = extractSection(response, "SUMMARY:", "KEY POINTS:")
        val keyPointsSection = extractSection(response, "KEY POINTS:", "ACTION ITEMS:")
        val actionItemsSection = extractSection(response, "ACTION ITEMS:", "QUESTIONS:")
        val questionsSection = extractSection(response, "QUESTIONS:", "TAGS:")
        val tagsSection = extractSection(response, "TAGS:", "FOLLOW_UP_QUESTIONS:")
        val followUpSection = extractSection(response, "FOLLOW_UP_QUESTIONS:", "RATING:")
        val ratingSection = extractSection(response, "RATING:", null)

        val keyPoints = parseBulletPoints(keyPointsSection)
        val actionItems = parseBulletPoints(actionItemsSection)
        val questions = parseBulletPoints(questionsSection)
        val tags = parseHashtags(tagsSection)
        val recommendedQuestions = parseBulletPoints(followUpSection).take(5)
        val qualityRating = parseQualityRating(ratingSection)

        return SummaryResult(
            recordingId = recordingId,
            summary = summarySection.ifBlank { response },
            keyPoints = keyPoints,
            actionItems = actionItems,
            questions = questions,
            tags = tags,
            modelUsed = modelName,
            recommendedQuestions = recommendedQuestions,
            qualityRating = qualityRating
        )
    }

    internal fun parseQualityRating(ratingText: String): QualityRating? {
        if (ratingText.isBlank()) return null

        val lines = ratingText.lines().associate { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                parts[0].trim().lowercase() to parts[1].trim()
            } else {
                "" to ""
            }
        }

        val pacing = lines["pacing"]?.toIntOrNull()
        val questions = lines["questions"]?.toIntOrNull()
        val goalSetting = lines["goal-setting"]?.toIntOrNull()
        val nextSteps = lines["summary/next steps"]?.toIntOrNull()

        // If any criterion is missing or unparseable, produce null
        if (pacing == null || questions == null || goalSetting == null || nextSteps == null) {
            return null
        }

        val clampedPacing = pacing.coerceIn(1, 5)
        val clampedQuestions = questions.coerceIn(1, 5)
        val clampedGoalSetting = goalSetting.coerceIn(1, 5)
        val clampedNextSteps = nextSteps.coerceIn(1, 5)

        val overall = QualityRatingCalculator.calculateOverall(
            clampedPacing, clampedQuestions, clampedGoalSetting, clampedNextSteps
        )

        return QualityRating(
            pacing = clampedPacing,
            questions = clampedQuestions,
            goalSetting = clampedGoalSetting,
            nextSteps = clampedNextSteps,
            overall = overall
        )
    }

    private fun extractSection(text: String, startMarker: String, endMarker: String?): String {
        val startIndex = text.indexOf(startMarker)
        if (startIndex == -1) return ""

        val contentStart = startIndex + startMarker.length
        val contentEnd = if (endMarker != null) {
            val endIndex = text.indexOf(endMarker, contentStart)
            if (endIndex != -1) endIndex else text.length
        } else {
            text.length
        }

        return text.substring(contentStart, contentEnd).trim()
    }

    private fun parseBulletPoints(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }
            .map { it.removePrefix("-").removePrefix("•").removePrefix("*").trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseHashtags(text: String): List<String> {
        val regex = Regex("#[\\w-]+")
        return regex.findAll(text).map { it.value.removePrefix("#") }.toList().distinct()
    }
}

/**
 * Exception thrown when a cloud LLM API returns 401 or 403, indicating
 * the API key is invalid or expired.
 */
class CloudLlmAuthenticationException(message: String) : Exception(message)

@Serializable
data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val options: OllamaOptions? = null
)

@Serializable
data class OllamaOptions(
    val num_ctx: Int? = null,
    val temperature: Double? = null
)

@Serializable
data class OllamaGenerateResponse(
    val response: String = ""
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<ChatMessage>
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenAiChatResponse(
    val choices: List<ChatChoice>
)

@Serializable
data class ChatChoice(
    val message: ChatMessage
)
