package com.fairuniversal.offlineotp.Utilities

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Self-contained password vault. The entire versioned header is AES-GCM associated data.
 * No device secrets, verifier, application preferences or automatic-backup credentials belong here.
 * Argon2id uses the existing vault credential derivation (64-byte output, lower 32-byte AES key).
 * PBKDF2 is read/migration compatibility only (32-byte output, lower 16-byte AES key).
 */
object VaultContainer {
    private val magic = byteArrayOf(79, 84, 80, 86, 76, 84, 13, 10) // OTPVLT CR LF
    const val HEADER_BYTES = 40
    const val OVERHEAD_BYTES = HEADER_BYTES + 12 + 16
    const val MAX_PLAINTEXT_BYTES = BoundedInputReader.MAX_DOCUMENT_BYTES - OVERHEAD_BYTES
    private const val ARGON2 = 1
    private const val PBKDF2 = 2

    class Header internal constructor(internal val bytes: ByteArray) {
        val salt: ByteArray get() = bytes.copyOfRange(24, 40)
        val kdf: String get() = if (bytes[10].toInt() == ARGON2) Constants.KDF_ARGON2ID else Constants.KDF_PBKDF2
        val memoryKiB: Int get() = intAt(bytes, 12)
        val iterations: Int get() = intAt(bytes, 16)
        val parallelism: Int get() = intAt(bytes, 20)
        override fun toString() = "VaultContainerHeader(redacted)"
    }

    /** Recognize the family, even if its version is unsupported; never fall back on parse failure. */
    fun isContainer(data: ByteArray): Boolean = data.size >= magic.size && magic.indices.all { data[it] == magic[it] }

    fun header(data: ByteArray): Header {
        require(data.size in OVERHEAD_BYTES + 1..BoundedInputReader.MAX_DOCUMENT_BYTES) { "Invalid vault size" }
        require(isContainer(data)) { "Not a vault container" }
        return checkedHeader(data.copyOfRange(0, HEADER_BYTES))
    }

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
        require(salt.size == 16) { "Unsupported legacy salt size" }
        val algorithm = when (kdf) { Constants.KDF_ARGON2ID -> ARGON2; Constants.KDF_PBKDF2 -> PBKDF2; else -> error("Unsupported KDF") }
        val bytes = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(magic).put(1.toByte()).put(1.toByte()).put(algorithm.toByte()).put(0.toByte())
            .putInt(if (algorithm == ARGON2) memoryKiB else 0)
            .putInt(iterations)
            .putInt(if (algorithm == ARGON2) parallelism else 0)
            .put(salt).array()
        return checkedHeader(bytes)
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
        val descriptor = checkedHeader(header.bytes.copyOf())
        requireKeySize(key, descriptor)
        val nonce = CryptoPrimitives.generateRandom(12)
        return descriptor.bytes + nonce + CryptoPrimitives.encryptWithAad(key, nonce, plaintext, descriptor.bytes)
    }

    private fun checkedHeader(bytes: ByteArray): Header {
        require(bytes.size == HEADER_BYTES && isContainer(bytes) && bytes[8].toInt() == 1 &&
            bytes[9].toInt() == 1 && bytes[11].toInt() == 0) { "Unsupported vault format" }
        val memory = intAt(bytes, 12); val iterations = intAt(bytes, 16); val parallelism = intAt(bytes, 20)
        when (bytes[10].toInt()) {
            ARGON2 -> require(parallelism in 1..Argon2id.MAX_PARALLELISM && memory in 8 * parallelism..Argon2id.MAX_MEMORY_KIB &&
                iterations in 1..Argon2id.MAX_ITERATIONS && memory.toLong() * iterations <= Argon2id.MAX_BLOCK_PASSES) { "Invalid KDF bounds" }
            PBKDF2 -> require(memory == 0 && parallelism == 0 && iterations in 1..20_000_000) { "Invalid KDF bounds" }
            else -> throw IllegalArgumentException("Unsupported vault KDF")
        }
        return Header(bytes)
    }
    private fun requireKeySize(key: SecretKey, header: Header) {
        val raw = requireNotNull(key.encoded) { "A portable vault cannot use a device-only key" }
        try { require(raw.size == if (header.kdf == Constants.KDF_ARGON2ID) 32 else 16) { "Wrong vault key size" } }
        finally { raw.fill(0) }
    }
    private fun intAt(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
}
