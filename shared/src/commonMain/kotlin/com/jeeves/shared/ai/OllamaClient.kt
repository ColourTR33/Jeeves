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
        attachmentCount: Int
    ): SummaryResult {
        val wordCount = transcription.text.split("\\s+".toRegex()).size

        // For long transcripts (>4000 words), use chunked summarization
        return if (wordCount > 4000) {
            summarizeChunked(transcription, config, description, attachmentCount)
        } else {
            summarizeDirect(transcription, config, description, attachmentCount)
        }
    }

    private suspend fun summarizeDirect(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String,
        attachmentCount: Int
    ): SummaryResult {
        val prompt = buildSummarizationPrompt(transcription, description, attachmentCount)

        val response = try {
            callOllamaApi(config, prompt)
        } catch (e: Exception) {
            callOpenAiCompatibleApi(config, prompt)
        }

        return parseSummaryResponse(transcription.recordingId, response, config.modelName)
    }

    /**
     * Chunked summarization for long transcripts.
     * 1. Split transcript into ~3000-word chunks
     * 2. Summarize each chunk independently (key points + actions only)
     * 3. Combine chunk summaries into a final pass that produces the full structured output
     */
    private suspend fun summarizeChunked(
        transcription: TranscriptionResult,
        config: AiEndpointConfig,
        description: String,
        attachmentCount: Int
    ): SummaryResult {
        val words = transcription.text.split("\\s+".toRegex())
        val chunkSize = 3000
        val chunks = words.chunked(chunkSize).map { it.joinToString(" ") }

        AppLogger.info("OllamaClient", "Chunked summarization: ${words.size} words -> ${chunks.size} chunks")

        // Phase 1: Summarize each chunk
        val chunkSummaries = mutableListOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            AppLogger.info("OllamaClient", "Summarizing chunk ${index + 1}/${chunks.size}")
            val chunkPrompt = """
                |Summarize this section of a meeting transcription (part ${index + 1} of ${chunks.size}).
                |Provide key points and any action items as bullet points. Be concise.
                |
                |TRANSCRIPTION SECTION:
                |$chunk
            """.trimMargin()

            val chunkResponse = try {
                callOllamaApi(config, chunkPrompt)
            } catch (e: Exception) {
                callOpenAiCompatibleApi(config, chunkPrompt)
            }
            chunkSummaries.add("--- Part ${index + 1} ---\n$chunkResponse")
        }

        // Phase 2: Final synthesis pass
        val combinedChunkText = chunkSummaries.joinToString("\n\n")
        val contextSection = if (description.isNotBlank()) "\nMeeting context/agenda: $description\n" else ""
        val attachmentNote = if (attachmentCount > 0) "\n$attachmentCount screenshot(s) were captured.\n" else ""

        val finalPrompt = """
            |Below are summaries of different parts of a meeting. Combine them into a single cohesive meeting summary.
            |$contextSection$attachmentNote
            |Provide:
            |1. A concise summary (2-3 paragraphs)
            |2. Key points discussed (bullet points)
            |3. Action items identified (bullet points)
            |4. Questions raised (bullet points)
            |5. At least 2 hashtag tags for categorizing this meeting (e.g., #project-name #sprint-review)
            |
            |Format your response as:
            |SUMMARY:
            |[your summary here]
            |
            |KEY POINTS:
            |- [point 1]
            |...
            |
            |ACTION ITEMS:
            |- [action 1]
            |...
            |
            |QUESTIONS:
            |- [question 1]
            |...
            |
            |TAGS:
            |#tag1 #tag2
            |
            |SECTION SUMMARIES:
            |$combinedChunkText
        """.trimMargin()

        val finalResponse = try {
            callOllamaApi(config, finalPrompt)
        } catch (e: Exception) {
            callOpenAiCompatibleApi(config, finalPrompt)
        }

        return parseSummaryResponse(transcription.recordingId, finalResponse, config.modelName)
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

    private fun buildSummarizationPrompt(
        transcription: TranscriptionResult,
        description: String = "",
        attachmentCount: Int = 0
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

        return """
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
            |$speakerInstruction$contextSection$attachmentNote
            |TRANSCRIPTION:
            |$formattedText
        """.trimMargin()
    }

    private fun parseSummaryResponse(
        recordingId: String,
        response: String,
        modelName: String
    ): SummaryResult {
        val summarySection = extractSection(response, "SUMMARY:", "KEY POINTS:")
        val keyPointsSection = extractSection(response, "KEY POINTS:", "ACTION ITEMS:")
        val actionItemsSection = extractSection(response, "ACTION ITEMS:", "QUESTIONS:")
        val questionsSection = extractSection(response, "QUESTIONS:", "TAGS:")
        val tagsSection = extractSection(response, "TAGS:", null)

        val keyPoints = parseBulletPoints(keyPointsSection)
        val actionItems = parseBulletPoints(actionItemsSection)
        val questions = parseBulletPoints(questionsSection)
        val tags = parseHashtags(tagsSection)

        return SummaryResult(
            recordingId = recordingId,
            summary = summarySection.ifBlank { response },
            keyPoints = keyPoints,
            actionItems = actionItems,
            questions = questions,
            tags = tags,
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

    private fun parseHashtags(text: String): List<String> {
        val regex = Regex("#[\\w-]+")
        return regex.findAll(text).map { it.value.removePrefix("#") }.toList().distinct()
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
