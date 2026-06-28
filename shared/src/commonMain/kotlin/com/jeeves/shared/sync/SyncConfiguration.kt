package com.jeeves.shared.sync

import kotlinx.serialization.Serializable

/**
 * Audio download policy for synced recordings.
 */
@Serializable
enum class AudioDownloadPolicy {
    ALWAYS,
    WIFI_ONLY,
    ON_DEMAND
}

/**
 * Configuration for CouchDB sync connection.
 */
@Serializable
data class SyncConfiguration(
    val remoteUrl: String,
    val username: String,
    val encryptedPassword: String,
    val enabled: Boolean = false,
    val audioDownloadPolicy: AudioDownloadPolicy = AudioDownloadPolicy.ON_DEMAND,
    val deviceId: String = ""
) {
    companion object {
        const val MAX_URL_LENGTH = 2048
        const val MAX_USERNAME_LENGTH = 256
        const val MAX_PASSWORD_LENGTH = 256

        private val HTTPS_URL_REGEX = Regex(
            "^https://[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?" +
                "(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)*" +
                "(:\\d{1,5})?" +
                "(/[a-zA-Z0-9._~:@!$&'()*+,;=\\-]*)*" +
                "/?$"
        )
    }

    /**
     * Returns a list of field names that are empty/blank.
     * Fields checked: "URL", "username", "password".
     */
    fun findEmptyFields(): List<String> {
        val missing = mutableListOf<String>()
        if (remoteUrl.isBlank()) missing.add("URL")
        if (username.isBlank()) missing.add("username")
        if (encryptedPassword.isBlank()) missing.add("password")
        return missing
    }

    /**
     * Validates the remote URL format.
     * Returns null if the URL is valid, or an error message describing the issue.
     */
    fun validateUrl(): String? {
        if (remoteUrl.isBlank()) {
            return "URL is required"
        }
        if (remoteUrl.length > MAX_URL_LENGTH) {
            return "URL exceeds maximum length of $MAX_URL_LENGTH characters"
        }
        if (!remoteUrl.startsWith("https://")) {
            return "URL must use HTTPS protocol"
        }
        if (!HTTPS_URL_REGEX.matches(remoteUrl)) {
            return "URL format is invalid"
        }
        return null
    }

    /**
     * Validates all fields and returns a list of validation errors.
     * An empty list means the configuration is valid.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        val urlError = validateUrl()
        if (urlError != null) {
            errors.add(urlError)
        }

        if (username.length > MAX_USERNAME_LENGTH) {
            errors.add("Username exceeds maximum length of $MAX_USERNAME_LENGTH characters")
        }

        if (encryptedPassword.length > MAX_PASSWORD_LENGTH) {
            errors.add("Password exceeds maximum length of $MAX_PASSWORD_LENGTH characters")
        }

        return errors
    }

    /**
     * Returns true if the configuration has all required fields populated
     * and passes all validation checks.
     */
    fun isValid(): Boolean = findEmptyFields().isEmpty() && validate().isEmpty()
}
