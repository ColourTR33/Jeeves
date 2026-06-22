package com.jeeves.shared.ai

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Creates a configured Ktor HTTP client for AI API calls.
 */
fun createHttpClient(): HttpClient {
    return HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }

        install(Logging) {
            level = LogLevel.NONE
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 1_800_000 // 30 min for long chunked transcriptions on CPU
            connectTimeoutMillis = 10_000
        }
    }
}
