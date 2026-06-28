package com.jeeves.shared.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SyncConfiguration validation logic.
 *
 * **Validates: Requirements 1.1, 1.4, 1.8**
 */
class SyncConfigurationTest {

    private val validConfig = SyncConfiguration(
        remoteUrl = "https://couch.example.com/jeeves",
        username = "admin",
        encryptedPassword = "encrypted-pass-123"
    )

    // --- Empty field checks (Requirement 1.8) ---

    @Test
    fun findEmptyFields_allPopulated_returnsEmptyList() {
        assertEquals(emptyList(), validConfig.findEmptyFields())
    }

    @Test
    fun findEmptyFields_emptyUrl_returnsUrl() {
        val config = validConfig.copy(remoteUrl = "")
        assertEquals(listOf("URL"), config.findEmptyFields())
    }

    @Test
    fun findEmptyFields_blankUsername_returnsUsername() {
        val config = validConfig.copy(username = "   ")
        assertEquals(listOf("username"), config.findEmptyFields())
    }

    @Test
    fun findEmptyFields_emptyPassword_returnsPassword() {
        val config = validConfig.copy(encryptedPassword = "")
        assertEquals(listOf("password"), config.findEmptyFields())
    }

    @Test
    fun findEmptyFields_allEmpty_returnsAllThreeFields() {
        val config = SyncConfiguration(remoteUrl = "", username = "", encryptedPassword = "")
        assertEquals(listOf("URL", "username", "password"), config.findEmptyFields())
    }

    // --- URL validation (Requirements 1.1, 1.4) ---

    @Test
    fun validateUrl_validHttpsUrl_returnsNull() {
        assertNull(validConfig.validateUrl())
    }

    @Test
    fun validateUrl_validUrlWithPort_returnsNull() {
        val config = validConfig.copy(remoteUrl = "https://couch.example.com:5984/jeeves")
        assertNull(config.validateUrl())
    }

    @Test
    fun validateUrl_validUrlWithPath_returnsNull() {
        val config = validConfig.copy(remoteUrl = "https://my-server.org/db/recordings")
        assertNull(config.validateUrl())
    }

    @Test
    fun validateUrl_httpNotHttps_returnsError() {
        val config = validConfig.copy(remoteUrl = "http://couch.example.com/jeeves")
        val error = config.validateUrl()
        assertTrue(error != null && error.contains("HTTPS"))
    }

    @Test
    fun validateUrl_blankUrl_returnsRequired() {
        val config = validConfig.copy(remoteUrl = "")
        val error = config.validateUrl()
        assertTrue(error != null && error.contains("required"))
    }

    @Test
    fun validateUrl_exceedsMaxLength_returnsError() {
        val longUrl = "https://example.com/" + "a".repeat(2030)
        val config = validConfig.copy(remoteUrl = longUrl)
        val error = config.validateUrl()
        assertTrue(error != null && error.contains("2048"))
    }

    @Test
    fun validateUrl_malformedUrl_returnsInvalid() {
        val config = validConfig.copy(remoteUrl = "https://")
        val error = config.validateUrl()
        assertTrue(error != null && error.contains("invalid"))
    }

    @Test
    fun validateUrl_noScheme_returnsError() {
        val config = validConfig.copy(remoteUrl = "couch.example.com/jeeves")
        val error = config.validateUrl()
        assertTrue(error != null && error.contains("HTTPS"))
    }

    @Test
    fun validateUrl_ftpScheme_returnsError() {
        val config = validConfig.copy(remoteUrl = "ftp://couch.example.com/jeeves")
        val error = config.validateUrl()
        assertTrue(error != null && error.contains("HTTPS"))
    }

    // --- Full validation ---

    @Test
    fun validate_validConfig_returnsEmptyList() {
        assertEquals(emptyList(), validConfig.validate())
    }

    @Test
    fun validate_usernameTooLong_returnsError() {
        val config = validConfig.copy(username = "a".repeat(257))
        val errors = config.validate()
        assertTrue(errors.any { it.contains("Username") && it.contains("256") })
    }

    @Test
    fun validate_passwordTooLong_returnsError() {
        val config = validConfig.copy(encryptedPassword = "x".repeat(257))
        val errors = config.validate()
        assertTrue(errors.any { it.contains("Password") && it.contains("256") })
    }

    @Test
    fun validate_multipleErrors_returnsAll() {
        val config = SyncConfiguration(
            remoteUrl = "http://bad",
            username = "a".repeat(257),
            encryptedPassword = "x".repeat(257)
        )
        val errors = config.validate()
        assertTrue(errors.size >= 3)
    }

    // --- isValid ---

    @Test
    fun isValid_validConfig_returnsTrue() {
        assertTrue(validConfig.isValid())
    }

    @Test
    fun isValid_emptyFields_returnsFalse() {
        val config = validConfig.copy(username = "")
        assertFalse(config.isValid())
    }

    @Test
    fun isValid_invalidUrl_returnsFalse() {
        val config = validConfig.copy(remoteUrl = "http://not-https.com/db")
        assertFalse(config.isValid())
    }

    // --- Serialization ---

    @Test
    fun serialization_roundTrip_preservesAllFields() {
        val json = Json { ignoreUnknownKeys = true }
        val config = SyncConfiguration(
            remoteUrl = "https://couch.example.com/jeeves",
            username = "admin",
            encryptedPassword = "enc123",
            enabled = true,
            audioDownloadPolicy = AudioDownloadPolicy.WIFI_ONLY,
            deviceId = "device-uuid-123"
        )
        val serialized = json.encodeToString(SyncConfiguration.serializer(), config)
        val deserialized = json.decodeFromString(SyncConfiguration.serializer(), serialized)
        assertEquals(config, deserialized)
    }

    @Test
    fun serialization_defaultValues_areApplied() {
        val json = Json { ignoreUnknownKeys = true }
        val minimal = """{"remoteUrl":"https://x.com/db","username":"u","encryptedPassword":"p"}"""
        val config = json.decodeFromString(SyncConfiguration.serializer(), minimal)
        assertFalse(config.enabled)
        assertEquals(AudioDownloadPolicy.ON_DEMAND, config.audioDownloadPolicy)
        assertEquals("", config.deviceId)
    }
}
