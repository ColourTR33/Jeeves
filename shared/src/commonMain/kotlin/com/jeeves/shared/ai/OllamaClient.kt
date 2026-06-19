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
        config: AiEndpointConfig
    ): SummaryResult {
        val prompt = buildSummarizationPrompt(transcription.text)

        // Try Ollama native API first
        val response = try {
            callOllamaApi(config, prompt)
        } catch (e: Exception) {
            // Fall back to OpenAI-compatible endpoint
            callOpenAiCompatibleApi(config, prompt)
        }

        return parseSummaryResponse(transcription.recordingId, response, config.modelName)
    }

    private suspend fun callOllamaApi(config: AiEndpointConfig, prompt: String): String {
        val requestBody = OllamaGenerateRequest(
            model = config.modelName,
            prompt = prompt,
            stream = false
        )

        val response = httpClient.post("${config.baseUrl}/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OllamaGenerateRequest.serializer(), requestBody))
        }

        val responseBody: String = response.body()
        val ollamaResponse = json.decodeFromString<OllamaGenerateResponse>(responseBody)
        return ollamaResponse.response
    }

    private suspend fun callOpenAiCompatibleApi(config: AiEndpointConfig, prompt: String): String {
        val requestBody = OpenAiChatRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage(role = "system", content = "You are a meeting summarisation assistant."),
                ChatMessage(role = "user", content = prompt)
            )
        )

        val response = httpClient.post("${config.baseUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpenAiChatRequest.serializer(), requestBody))
        }

        val responseBody: String = response.body()
        val chatResponse = json.decodeFromString<OpenAiChatResponse>(responseBody)
        return chatResponse.choices.firstOrNull()?.message?.content ?: ""
    }

    private fun buildSummarizationPrompt(transcription: String): String {
        return """
            |Please summarise the following meeting transcription. Provide:
            |1. A concise summary (2-3 paragraphs)
            |2. Key points discussed (bullet points)
            |3. Action items identified (bullet points)
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
            |TRANSCRIPTION:
            |$transcription
        """.trimMargin()
    }

    private fun parseSummaryResponse(
        recordingId: String,
        response: String,
        modelName: String
    ): SummaryResult {
        val summarySection = extractSection(response, "SUMMARY:", "KEY POINTS:")
        val keyPointsSection = extractSection(response, "KEY POINTS:", "ACTION ITEMS:")
        val actionItemsSection = extractSection(response, "ACTION ITEMS:", null)

        val keyPoints = parseBulletPoints(keyPointsSection)
        val actionItems = parseBulletPoints(actionItemsSection)

        return SummaryResult(
            recordingId = recordingId,
            summary = summarySection.ifBlank { response },
            keyPoints = keyPoints,
            actionItems = actionItems,
            modelUsed = modelName
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
}

@Serializable
data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false
)

@Serializable
data class OllamaGenerateResponse(
    val response: String
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
