package com.jeeves.shared.ai

/**
 * Simple application logger that writes to stdout/stderr.
 * Provides structured logging for debugging transcription and AI service calls.
 */
object AppLogger {
    private var enabled = true

    fun info(tag: String, message: String) {
        if (enabled) {
            println("[INFO][$tag] $message")
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            System.err.println("[ERROR][$tag] $message")
            throwable?.let {
                System.err.println("[ERROR][$tag] Exception: ${it::class.simpleName}: ${it.message}")
                it.cause?.let { cause ->
                    System.err.println("[ERROR][$tag] Caused by: ${cause::class.simpleName}: ${cause.message}")
                }
            }
        }
    }

    fun warn(tag: String, message: String) {
        if (enabled) {
            println("[WARN][$tag] $message")
        }
    }
}
