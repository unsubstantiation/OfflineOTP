package com.fairuniversal.offlineotp.Utilities

import java.nio.charset.StandardCharsets
import com.fairuniversal.offlineotp.domain.VaultProtocol
import com.fairuniversal.offlineotp.domain.BackupRoute

/**
 * Password-encrypted backup framing, shared by the Android app and the desktop client so an
 * encrypted backup produced on either platform is byte-compatible.
 *
 * Current writer format (Argon2id v1.3 + AES-256-GCM):
 * `["OOTP"][0x01][4-byte BE memoryKiB][4-byte BE iterations][1-byte parallelism][16-byte salt][12-byte GCM nonce][ciphertext][16-byte tag]`.
 *
 * Readers also accept the legacy PBKDF2-HMAC-SHA1 format (`[4-byte BE iterations][12-byte salt][12-byte GCM nonce][ciphertext][16-byte tag]`)
 * parsed by [EncryptedBackupHeader] so existing backups continue to restore. The pre-0.6.3 andOTP
 * headerless SHA-256 format is available only through explicit [openSelected] opt-in.
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
        require(plain.toByteArray(StandardCharsets.UTF_8).size <= BoundedInputReader.MAX_DOCUMENT_BYTES - EncryptedBackupHeader.ARGON2_HEADER_BYTES - 28) { "Backup too large" }
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

    /** Explicit full-restore dispatch. Never retry another format after an authentication failure.
     * andOTP v0.9.0 uses BE32 iterations + 12 salt bytes + AES-256-GCM nonce/ciphertext/tag.
     * Its pre-0.6.3 format requires a separate explicit opt-in (SHA256 UTF-8 password key).
     */
    @JvmStatic
    @JvmOverloads
    fun openSelected(password: String, data: ByteArray, format: String, andOtpVariant: String = "encrypted"): String {
        require(data.isNotEmpty() && data.size <= BoundedInputReader.MAX_DOCUMENT_BYTES) { "Invalid backup size" }
        val route = requireNotNull(VaultProtocol().select(data, data.size, format, andOtpVariant).route) { "Wrong backup format" }
        val plaintext: ByteArray = when (route) {
            BackupRoute.VAULT_COPY -> VaultContainer.open(password, data)
            BackupRoute.OFFLINEOTP, BackupRoute.ANDOTP_PBKDF2 -> {
                val header = EncryptedBackupHeader.parse(data)
                require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS)
                CryptoPrimitives.decrypt(header.deriveKey(password), header.getEncrypted())
            }
            BackupRoute.PLAIN -> data.copyOf()
            BackupRoute.ANDOTP_PRE063 -> {
                require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS)
                val raw = password.toByteArray(StandardCharsets.UTF_8)
                val digest = try { java.security.MessageDigest.getInstance("SHA-256").digest(raw) } finally { raw.fill(0) }
                try { CryptoPrimitives.decrypt(javax.crypto.spec.SecretKeySpec(digest, "AES"), data) }
                finally { digest.fill(0) }
            }
            BackupRoute.LINSCP -> throw IllegalArgumentException("Unsupported restore format")
        }

        return try {
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(plaintext)).toString()
                .removePrefix("\uFEFF")
            // Reject unrelated authenticated documents/partial entry arrays before any vault mutation.
            val entries = com.fairuniversal.offlineotp.Database.VaultJson.stringToEntries(text)
            try { require(entries.toHashSet().size == entries.size) { "Duplicate backup accounts" } }
            finally { entries.forEach { it.destroy() } }
            text
        } finally { plaintext.fill(0) }
    }

    /**
     * Decrypts an Argon2id or legacy PBKDF2 password backup. A wrong password or tampered bytes
     * surface as the underlying JCA exception (for example `BadPaddingException`); truncated or
     * out-of-bounds framing throws `IllegalArgumentException`.
     */
    @JvmStatic
    fun open(password: String, data: ByteArray): String {
        val route = requireNotNull(VaultProtocol().select(data, data.size, "auto", "").route) { "Invalid backup framing" }
        if (route == BackupRoute.VAULT_COPY) {
            val plaintext = VaultContainer.open(password, data)
            return try { String(plaintext, StandardCharsets.UTF_8) } finally { plaintext.fill(0) }
        }
        if (route == BackupRoute.LINSCP) {
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
