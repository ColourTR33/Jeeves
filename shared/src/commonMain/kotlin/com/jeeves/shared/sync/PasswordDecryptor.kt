package com.jeeves.shared.sync

/**
 * Interface for decrypting stored sync passwords.
 *
 * The password stored in [SyncConfiguration.encryptedPassword] is encrypted at rest.
 * This interface provides the decryption capability needed only when constructing
 * HTTP Basic Auth headers for CouchDB communication.
 *
 * Platform-specific implementations provide the actual decryption (e.g., AES-256-GCM on JVM).
 *
 * **Validates: Requirements 1.2, 7.8**
 */
interface PasswordDecryptor {
    /**
     * Decrypt the stored encrypted password to its plaintext form.
     *
     * @param encryptedPassword The encrypted password string from SyncConfiguration.
     * @return The plaintext password for use in HTTP auth headers.
     */
    fun decrypt(encryptedPassword: String): String
}

/**
 * A no-op decryptor that returns the password as-is.
 * Used when passwords are not encrypted (e.g., during testing or when encryption is not configured).
 */
object NoOpPasswordDecryptor : PasswordDecryptor {
    override fun decrypt(encryptedPassword: String): String = encryptedPassword
}
