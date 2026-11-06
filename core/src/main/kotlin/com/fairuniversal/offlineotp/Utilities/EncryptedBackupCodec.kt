package com.fairuniversal.offlineotp.Utilities

import java.nio.charset.StandardCharsets

/**
 * Password-encrypted backup framing, shared by the Android app and the desktop client so an
 * encrypted backup produced on either platform is byte-compatible.
 *
 * Current writer format (Argon2id v1.3 + AES-256-GCM):
 * `["OOTP"][0x01][4-byte BE memoryKiB][4-byte BE iterations][1-byte parallelism][16-byte salt][12-byte GCM nonce][ciphertext][16-byte tag]`.
 *
 * Readers also accept the legacy PBKDF2-HMAC-SHA1 format (`[4-byte BE iterations][12-byte salt][12-byte GCM nonce][ciphertext][16-byte tag]`)
 * parsed by [EncryptedBackupHeader] so existing backups continue to restore. The pre-0.6.3 andOTP
 * headerless SHA-256 format is not supported.
 */
object EncryptedBackupCodec {

    /**
     * Encrypts [plain] under Argon2id (`64 MiB, t=3, p=4`) with a fresh 16-byte salt and 12-byte
     * AES-GCM nonce. Throws on an empty password or a crypto failure.
     */
    @JvmStatic
    fun seal(password: String, plain: String): ByteArray {
        require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS) {
            "Backup password must not be empty"
        }
        val salt = CryptoPrimitives.generateRandom(Constants.ARGON2_SALT_LENGTH)
        val key = CryptoPrimitives.generateSymmetricKeyArgon2id(
            password = password,
            salt = salt,
            memoryKiB = Constants.ARGON2_MEMORY_KIB,
            iterations = Constants.ARGON2_ITERATIONS,
            parallelism = Constants.ARGON2_PARALLELISM
        )
        val encrypted = CryptoPrimitives.encrypt(key, plain.toByteArray(StandardCharsets.UTF_8))
        return EncryptedBackupHeader.frameArgon2id(
            memoryKiB = Constants.ARGON2_MEMORY_KIB,
            iterations = Constants.ARGON2_ITERATIONS,
            parallelism = Constants.ARGON2_PARALLELISM,
            salt = salt,
            encrypted = encrypted
        )
    }

    /**
     * Decrypts an Argon2id or legacy PBKDF2 password backup. A wrong password or tampered bytes
     * surface as the underlying JCA exception (for example `BadPaddingException`); truncated or
     * out-of-bounds framing throws `IllegalArgumentException`.
     */
    @JvmStatic
    fun open(password: String, data: ByteArray): String {
        if (LinScpCredentials.isEncryptedVault(data)) {
            return LinScpCredentials.openVaultToJson(data, password)
        }
        val header = EncryptedBackupHeader.parse(data)
        val key = header.deriveKey(password)
        val decrypted = CryptoPrimitives.decrypt(key, header.getEncrypted())
        try {
            return String(decrypted, StandardCharsets.UTF_8)
        } finally {
            decrypted.fill(0)
        }
    }
}
