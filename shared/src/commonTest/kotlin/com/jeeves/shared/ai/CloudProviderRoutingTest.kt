package com.jeeves.shared.ai

import com.jeeves.shared.domain.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Unit tests for cloud LLM provider routing in OllamaClient.
 *
 * Validates Requirements: 5.4, 5.5
 */
class CloudProviderRoutingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val localConfig = AiEndpointConfig(
        name = "Local Ollama",
        baseUrl = "http://localhost:11434",
        modelName = "qwen3:8b",
        type = AiEndpointType.LLM_SUMMARIZATION
    )

    private val cloudLlmConfig = CloudLlmConfig(
        baseUrl = "https://api.openai.com",
        apiKey = "sk-test-key-12345",
        modelName = "gpt-4o",
        enabled = true
    )

    private val disabledCloudConfig = CloudLlmConfig(
        baseUrl = "https://api.openai.com",
        apiKey = "sk-test-key-12345",
        modelName = "gpt-4o",
        enabled = false
    )

    private val sampleTranscription = TranscriptionResult(
        recordingId = "test-rec-1",
        text = "Hello this is a test meeting transcription with enough words to proceed."
    )

    private fun buildSuccessfulChatResponse(): String {
        return json.encodeToString(
            OpenAiChatResponse.serializer(),
            OpenAiChatResponse(
                choices = listOf(
                    ChatChoice(
                        message = ChatMessage(
                            role = "assistant",
                            content = """
                                SUMMARY:
                                Brief test summary.

                                KEY POINTS:
                                - Point 1

                                ACTION ITEMS:
                                - Action 1

                                QUESTIONS:
                                - Question 1

                                TAGS:
                                #test
                            """.trimIndent()
                        )
                    )
                )
            )
        )
    }

    private fun buildOllamaGenerateResponse(): String {
        return json.encodeToString(
            OllamaGenerateResponse.serializer(),
            OllamaGenerateResponse(
                response = """
                    SUMMARY:
                    Brief local summary.

                    KEY POINTS:
                    - Local point 1

                    ACTION ITEMS:
                    - Local action 1

                    QUESTIONS:
                    - Local question 1

                    TAGS:
                    #local
                """.trimIndent()
            )
        )
    }

    // --- Test: Enabled cloud config routes to OpenAI-compatible endpoint ---

    @Test
    fun summarize_withEnabledCloudConfig_routesToCloudEndpoint() = runTest {
        var capturedRequestUrl: String? = null
        var capturedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            capturedRequestUrl = request.url.toString()
            capturedAuthHeader = request.headers["Authorization"]
            respond(
                content = ByteReadChannel(buildSuccessfulChatResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        client.summarize(
            transcription = sampleTranscription,
            config = localConfig,
            description = "",
            attachmentCount = 0,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL,
            cloudLlmConfig = cloudLlmConfig
        )

        // Verify the request went to the cloud endpoint, not local
        assertTrue(
            capturedRequestUrl!!.startsWith("https://api.openai.com/v1/chat/completions"),
            "Expected request to cloud URL, got: $capturedRequestUrl"
        )
        assertEquals("Bearer sk-test-key-12345", capturedAuthHeader)
    }

    @Test
    fun summarize_withEnabledCloudConfig_sendsAuthorizationBearerHeader() = runTest {
        var capturedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            capturedAuthHeader = request.headers["Authorization"]
            respond(
                content = ByteReadChannel(buildSuccessfulChatResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        client.summarize(
            transcription = sampleTranscription,
            config = localConfig,
            description = "",
            attachmentCount = 0,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL,
            cloudLlmConfig = cloudLlmConfig
        )

        assertEquals("Bearer sk-test-key-12345", capturedAuthHeader)
    }

    @Test
    fun summarize_withEnabledCloudConfig_usesCloudModelName() = runTest {
        var capturedBody: String? = null

        val mockEngine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = ByteReadChannel(buildSuccessfulChatResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        client.summarize(
            transcription = sampleTranscription,
            config = localConfig,
            description = "",
            attachmentCount = 0,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL,
            cloudLlmConfig = cloudLlmConfig
        )

        // Verify the cloud model name is used in the request body
        assertTrue(
            capturedBody!!.contains("gpt-4o"),
            "Expected cloud model name 'gpt-4o' in request body, got: $capturedBody"
        )
    }

    // --- Test: Disabled cloud config falls back to local Ollama ---

    @Test
    fun summarize_withDisabledCloudConfig_routesToLocalOllama() = runTest {
        var capturedRequestUrl: String? = null
        var capturedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            capturedRequestUrl = request.url.toString()
            capturedAuthHeader = request.headers["Authorization"]
            respond(
                content = ByteReadChannel(buildOllamaGenerateResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        client.summarize(
            transcription = sampleTranscription,
            config = localConfig,
            description = "",
            attachmentCount = 0,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL,
            cloudLlmConfig = disabledCloudConfig
        )

        // Verify the request went to the local Ollama endpoint
        assertTrue(
            capturedRequestUrl!!.startsWith("http://localhost:11434/api/generate"),
            "Expected request to local Ollama URL, got: $capturedRequestUrl"
        )
        // No Authorization header for local
        assertNull(capturedAuthHeader)
    }

    @Test
    fun summarize_withNullCloudConfig_routesToLocalOllama() = runTest {
        var capturedRequestUrl: String? = null
        var capturedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            capturedRequestUrl = request.url.toString()
            capturedAuthHeader = request.headers["Authorization"]
            respond(
                content = ByteReadChannel(buildOllamaGenerateResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        client.summarize(
            transcription = sampleTranscription,
            config = localConfig,
            description = "",
            attachmentCount = 0,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL,
            cloudLlmConfig = null
        )

        // Verify the request went to the local Ollama endpoint
        assertTrue(
            capturedRequestUrl!!.startsWith("http://localhost:11434/api/generate"),
            "Expected request to local Ollama URL, got: $capturedRequestUrl"
        )
        assertNull(capturedAuthHeader)
    }

    @Test
    fun summarize_withDisabledCloudConfig_doesNotSendAuthHeader() = runTest {
        var capturedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            capturedAuthHeader = request.headers["Authorization"]
            respond(
                content = ByteReadChannel(buildOllamaGenerateResponse()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        client.summarize(
            transcription = sampleTranscription,
            config = localConfig,
            description = "",
            attachmentCount = 0,
            promptTemplate = "",
            meetingTemplate = MeetingTemplate.GENERAL,
            cloudLlmConfig = disabledCloudConfig
        )

        assertNull(capturedAuthHeader)
    }

    // --- Test: 401 response produces meaningful error message ---

    @Test
    fun summarize_cloudReturns401_throwsCloudLlmAuthenticationException() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"error": {"message": "Invalid API key"}}"""),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        val exception = assertFailsWith<CloudLlmAuthenticationException> {
            client.summarize(
                transcription = sampleTranscription,
                config = localConfig,
                description = "",
                attachmentCount = 0,
                promptTemplate = "",
                meetingTemplate = MeetingTemplate.GENERAL,
                cloudLlmConfig = cloudLlmConfig
            )
        }

        assertTrue(
            exception.message!!.contains("authentication failed", ignoreCase = true) ||
                exception.message!!.contains("API key", ignoreCase = true),
            "Expected meaningful auth error message, got: ${exception.message}"
        )
    }

    @Test
    fun summarize_cloudReturns403_throwsCloudLlmAuthenticationException() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"error": {"message": "Forbidden"}}"""),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        val exception = assertFailsWith<CloudLlmAuthenticationException> {
            client.summarize(
                transcription = sampleTranscription,
                config = localConfig,
                description = "",
                attachmentCount = 0,
                promptTemplate = "",
                meetingTemplate = MeetingTemplate.GENERAL,
                cloudLlmConfig = cloudLlmConfig
            )
        }

        assertTrue(
            exception.message!!.contains("authentication failed", ignoreCase = true) ||
                exception.message!!.contains("API key", ignoreCase = true),
            "Expected meaningful auth error message, got: ${exception.message}"
        )
    }

    @Test
    fun summarize_cloudReturns401_errorMessageMentionsApiKey() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("Unauthorized"),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val client = OllamaClient(HttpClient(mockEngine))

        val exception = assertFailsWith<CloudLlmAuthenticationException> {
            client.summarize(
                transcription = sampleTranscription,
                config = localConfig,
                description = "",
                attachmentCount = 0,
                promptTemplate = "",
                meetingTemplate = MeetingTemplate.GENERAL,
                cloudLlmConfig = cloudLlmConfig
            )
        }

        // The error message should mention "API key" so users know what to fix
        assertTrue(
            exception.message!!.contains("API key"),
            "Error message should mention 'API key' to guide the user, got: ${exception.message}"
        )
    }
}
