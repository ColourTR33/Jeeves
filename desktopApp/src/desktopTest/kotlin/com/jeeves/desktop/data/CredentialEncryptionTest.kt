package com.jeeves.desktop.data

import java.io.File
import kotlin.test.*

/**
 * Unit tests for CredentialEncryption.
 *
 * Validates: Requirements 1.2, 7.8
 * - Password is never stored in plaintext
 * - Decrypt only when constructing HTTP auth headers
 * - Round-trip encryption/decryption preserves the original password
 */
class CredentialEncryptionTest {

    private lateinit var tempDir: File
    private lateinit var encryption: CredentialEncryption

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cred-enc-test-${System.nanoTime()}")
        tempDir.mkdirs()
        val keyFile = File(tempDir, "test.key")
        encryption = CredentialEncryption(keyFile)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun encryptPassword_returnsNonPlaintextValue() {
        val plaintext = "my-secret-password"

        val encrypted = encryption.encryptPassword(plaintext)

        assertNotEquals(plaintext, encrypted)
        assertFalse(encrypted.contains(plaintext))
    }

    @Test
    fun decryptPassword_returnsOriginalPlaintext() {
        val plaintext = "my-secret-password"

        val encrypted = encryption.encryptPassword(plaintext)
        val decrypted = encryption.decryptPassword(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptPassword_emptyString_returnsEmptyString() {
        val encrypted = encryption.encryptPassword("")
        assertEquals("", encrypted)
    }

    @Test
    fun decryptPassword_emptyString_returnsEmptyString() {
        val decrypted = encryption.decryptPassword("")
        assertEquals("", decrypted)
    }

    @Test
    fun encryptPassword_sameInputProducesDifferentCiphertext() {
        val plaintext = "my-secret-password"

        val encrypted1 = encryption.encryptPassword(plaintext)
        val encrypted2 = encryption.encryptPassword(plaintext)

        // Due to random IV, same plaintext should produce different ciphertext
        assertNotEquals(encrypted1, encrypted2)
    }

    @Test
    fun decryptPassword_bothEncryptionsDecryptCorrectly() {
        val plaintext = "my-secret-password"

        val encrypted1 = encryption.encryptPassword(plaintext)
        val encrypted2 = encryption.encryptPassword(plaintext)

        assertEquals(plaintext, encryption.decryptPassword(encrypted1))
        assertEquals(plaintext, encryption.decryptPassword(encrypted2))
    }

    @Test
    fun encryptDecrypt_specialCharacters() {
        val plaintext = "p@\$\$w0rd!#%^&*()_+-=[]{}|;':\",./<>?"

        val encrypted = encryption.encryptPassword(plaintext)
        val decrypted = encryption.decryptPassword(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_unicodeCharacters() {
        val plaintext = "密码パスワード🔐"

        val encrypted = encryption.encryptPassword(plaintext)
        val decrypted = encryption.decryptPassword(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_longPassword() {
        val plaintext = "a".repeat(256) // Max password length per SyncConfiguration

        val encrypted = encryption.encryptPassword(plaintext)
        val decrypted = encryption.decryptPassword(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun isEncrypted_encryptedValue_returnsTrue() {
        val encrypted = encryption.encryptPassword("test-password")

        assertTrue(encryption.isEncrypted(encrypted))
    }

    @Test
    fun isEncrypted_plaintextValue_returnsFalse() {
        assertFalse(encryption.isEncrypted("plaintext-password"))
        assertFalse(encryption.isEncrypted(""))
        assertFalse(encryption.isEncrypted("not:valid:format"))
    }

    @Test
    fun keyPersistence_sameKeyFileProducesSameKey() {
        val keyFile = File(tempDir, "persistent.key")

        val encryption1 = CredentialEncryption(keyFile)
        val encrypted = encryption1.encryptPassword("my-password")

        // Create a new instance with the same key file
        val encryption2 = CredentialEncryption(keyFile)
        val decrypted = encryption2.decryptPassword(encrypted)

        assertEquals("my-password", decrypted)
    }

    @Test
    fun decryptPassword_invalidFormat_throwsException() {
        assertFailsWith<IllegalArgumentException> {
            encryption.decryptPassword("not-valid-encrypted-format")
        }
    }

    @Test
    fun decryptPassword_wrongKey_throwsException() {
        val keyFile1 = File(tempDir, "key1.key")
        val keyFile2 = File(tempDir, "key2.key")

        val encryption1 = CredentialEncryption(keyFile1)
        val encryption2 = CredentialEncryption(keyFile2)

        val encrypted = encryption1.encryptPassword("secret")

        // Decrypting with a different key should fail (AEADBadTagException)
        assertFailsWith<Exception> {
            encryption2.decryptPassword(encrypted)
        }
    }

    @Test
    fun encryptedFormat_containsSeparator() {
        val encrypted = encryption.encryptPassword("test")

        // Should be in format "base64iv:base64ciphertext"
        assertTrue(encrypted.contains(":"))
        val parts = encrypted.split(":", limit = 2)
        assertEquals(2, parts.size)
        assertTrue(parts[0].isNotEmpty())
        assertTrue(parts[1].isNotEmpty())
    }

    @Test
    fun keyFile_isCreatedOnFirstUse() {
        val newKeyFile = File(tempDir, "new.key")
        assertFalse(newKeyFile.exists())

        val enc = CredentialEncryption(newKeyFile)
        enc.encryptPassword("trigger-key-generation")

        assertTrue(newKeyFile.exists())
        assertTrue(newKeyFile.readText().isNotEmpty())
    }
}
