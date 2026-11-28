package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.VaultProtocol
import com.fairuniversal.offlineotp.domain.ProtocolHeader
import com.fairuniversal.offlineotp.domain.ProtocolKdf
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Self-contained password vault. The entire versioned header is AES-GCM associated data.
 * No device secrets, verifier, application preferences or automatic-backup credentials belong here.
 * Argon2id uses the existing vault credential derivation (64-byte output, lower 32-byte AES key).
 * PBKDF2 is read/migration compatibility only (32-byte output, lower 16-byte AES key).
 */
object VaultContainer {
    private val protocol = VaultProtocol()
    const val HEADER_BYTES = VaultProtocol.HEADER_BYTES
    const val OVERHEAD_BYTES = VaultProtocol.OVERHEAD_BYTES
    const val MAX_PLAINTEXT_BYTES = VaultProtocol.MAX_PLAINTEXT_BYTES

    class Header internal constructor(internal val descriptor: ProtocolHeader) {
        internal val bytes: ByteArray get() = descriptor.bytes()
        val salt: ByteArray get() = descriptor.salt()
        val kdf: String get() = if (descriptor.kdf == ProtocolKdf.ARGON2ID) Constants.KDF_ARGON2ID else Constants.KDF_PBKDF2
        val memoryKiB: Int get() = descriptor.memoryKiB
        val iterations: Int get() = descriptor.iterations
        val parallelism: Int get() = descriptor.parallelism
        override fun toString() = "VaultContainerHeader(redacted)"
    }

    /** Recognize the family even on an unsupported version; no fallback on failure. */
    fun isContainer(data: ByteArray): Boolean = protocol.isVault(data)
    fun header(data: ByteArray): Header = Header(requireNotNull(protocol.vaultHeader(data, data.size).header) { "Invalid vault framing" })

    fun fromSettings(settings: KeyValueSettings): Header {
        val kdf = settings.getString(VaultSettingsKeys.AUTH_KDF, Constants.KDF_PBKDF2).trim().lowercase()
            .ifEmpty { Constants.KDF_PBKDF2 }
        val salt = Base64.getUrlDecoder().decode(settings.getString(VaultSettingsKeys.AUTH_SALT, ""))
        return fromParameters(kdf, salt,
            settings.getInt(VaultSettingsKeys.AUTH_MEMORY_KIB, Constants.ARGON2_MEMORY_KIB),
            settings.getInt(VaultSettingsKeys.AUTH_ITERATIONS,
                if (kdf == Constants.KDF_ARGON2ID) Constants.ARGON2_ITERATIONS else Constants.PBKDF2_MIN_AUTH_ITERATIONS),
            settings.getInt(VaultSettingsKeys.AUTH_PARALLELISM, Constants.ARGON2_PARALLELISM))
    }

    /** For native transaction adapters that stage newly prepared credentials before committing settings. */
    fun fromParameters(kdf: String, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int): Header {
        return Header(requireNotNull(protocol.makeVaultHeader(kdf, salt, memoryKiB, iterations, parallelism).header) { "Invalid vault parameters" })
    }

    fun deriveKey(password: String, header: Header): SecretKey {
        require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(password)) { "Invalid recovery password" }
        val credentials = if (header.kdf == Constants.KDF_ARGON2ID)
            CryptoPrimitives.generateArgon2idCredentials(password, header.salt, header.memoryKiB, header.iterations, header.parallelism)
        else CryptoPrimitives.generatePBKDF2Credentials(password, header.salt, header.iterations)
        return try { SecretKeySpec(credentials.key, "AES") }
        finally { credentials.password.fill(0); credentials.key.fill(0) }
    }

    fun open(password: String, data: ByteArray): ByteArray {
        val descriptor = header(data) // All bounds are checked before any expensive derivation.
        return openWithKey(deriveKey(password, descriptor), data)
    }

    fun openWithKey(key: SecretKey, data: ByteArray): ByteArray {
        val descriptor = header(data)
        requireKeySize(key, descriptor)
        return CryptoPrimitives.decryptWithAad(key, data.copyOfRange(40, 52), data.copyOfRange(52, data.size), descriptor.bytes)
    }

    fun seal(key: SecretKey, header: Header, plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES) { "Vault too large or empty" }
        val descriptor = header
        requireKeySize(key, descriptor)
        val nonce = CryptoPrimitives.generateRandom(12)
        return descriptor.bytes + nonce + CryptoPrimitives.encryptWithAad(key, nonce, plaintext, descriptor.bytes)
    }

    private fun requireKeySize(key: SecretKey, header: Header) {
        val raw = requireNotNull(key.encoded) { "A portable vault cannot use a device-only key" }
        try { require(raw.size == if (header.kdf == Constants.KDF_ARGON2ID) 32 else 16) { "Wrong vault key size" } }
        finally { raw.fill(0) }
    }
}
