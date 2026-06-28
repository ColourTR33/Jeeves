package com.jeeves.desktop.data

import com.jeeves.shared.sync.PasswordDecryptor
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encrypts and decrypts sync credentials using AES-256-GCM.
 *
 * The encryption key is stored in a separate keyfile (not alongside settings)
 * so that the settings file alone cannot reveal the password. The keyfile
 * is generated on first use and persisted in the application data directory.
 *
 * **Security model:**
 * - Password is never stored in plaintext in settings files
 * - Password is never logged or displayed (only masked asterisks in UI)
 * - Decryption happens only when constructing HTTP auth headers
 *
 * **Validates: Requirements 1.2, 7.8**
 */
class CredentialEncryption(private val keyFile: File) : PasswordDecryptor {

    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        /** Separator between IV and ciphertext in the stored string. */
        private const val SEPARATOR = ":"

        /**
         * Creates a CredentialEncryption instance using the default key location.
         * The key is stored in ~/Jeeves/.keys/sync.key, separate from settings.
         */
        fun createDefault(): CredentialEncryption {
            val keyDir = File(System.getProperty("user.home"), "Jeeves/.keys")
            keyDir.mkdirs()
            val keyFile = File(keyDir, "sync.key")
            return CredentialEncryption(keyFile)
        }
    }

    private val secretKey: SecretKey by lazy { loadOrGenerateKey() }

    /**
     * Encrypts a plaintext password for storage.
     *
     * @param plaintext The password in plaintext.
     * @return A Base64-encoded string containing the IV and ciphertext,
     *         suitable for storing in a settings file.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun encryptPassword(plaintext: String): String {
        if (plaintext.isEmpty()) return ""

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)

        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Store as base64(iv):base64(ciphertext)
        val ivBase64 = Base64.encode(iv)
        val ciphertextBase64 = Base64.encode(ciphertext)
        return "$ivBase64$SEPARATOR$ciphertextBase64"
    }

    /**
     * Decrypts an encrypted password for use in HTTP auth headers.
     *
     * @param encrypted The encrypted string as produced by [encryptPassword].
     * @return The original plaintext password.
     * @throws IllegalArgumentException if the encrypted format is invalid.
     * @throws javax.crypto.AEADBadTagException if the key doesn't match or data is corrupted.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decryptPassword(encrypted: String): String {
        if (encrypted.isEmpty()) return ""

        val parts = encrypted.split(SEPARATOR, limit = 2)
        require(parts.size == 2) {
            "Invalid encrypted password format. Expected 'iv:ciphertext' in Base64."
        }

        val iv = Base64.decode(parts[0])
        val ciphertext = Base64.decode(parts[1])

        require(iv.size == GCM_IV_LENGTH_BYTES) {
            "Invalid IV length: expected $GCM_IV_LENGTH_BYTES bytes, got ${iv.size}"
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * Implementation of [PasswordDecryptor.decrypt].
     * Delegates to [decryptPassword] for use by the SyncEngine and CouchDbReplicator.
     */
    override fun decrypt(encryptedPassword: String): String = decryptPassword(encryptedPassword)

    /**
     * Checks whether a string appears to be in encrypted format.
     * This is a heuristic check based on the expected structure.
     *
     * @param value The string to check.
     * @return true if the value looks like it was produced by [encryptPassword].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun isEncrypted(value: String): Boolean {
        if (value.isEmpty()) return false
        val parts = value.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return false
        return try {
            val iv = Base64.decode(parts[0])
            iv.size == GCM_IV_LENGTH_BYTES
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Loads the AES key from the keyfile, or generates and persists a new one.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun loadOrGenerateKey(): SecretKey {
        if (keyFile.exists()) {
            val keyBytes = Base64.decode(keyFile.readText().trim())
            return SecretKeySpec(keyBytes, ALGORITHM)
        }

        // Generate a new AES-256 key
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_SIZE_BITS, SecureRandom())
        val key = keyGen.generateKey()

        // Persist it
        keyFile.parentFile?.mkdirs()
        keyFile.writeText(Base64.encode(key.encoded))

        return key
    }
}
