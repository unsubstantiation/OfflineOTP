package com.fairuniversal.offlineotp.domain

/** Wire policy only. No crypto, password, filesystem, settings or session authority lives here. */
enum class ProtocolFailure { SIZE, FORMAT, VERSION, PARAMETERS }
enum class ProtocolKdf { ARGON2ID, PBKDF2_SHA1 }
enum class BackupRoute { VAULT_COPY, OFFLINEOTP, ANDOTP_PBKDF2, ANDOTP_PRE063, PLAIN, LINSCP }

/** Copies at both boundaries; contains public recovery metadata, never key material. */
class ProtocolHeader internal constructor(
    val kdf: ProtocolKdf, val memoryKiB: Int, val iterations: Int, val parallelism: Int,
    salt: ByteArray, bytes: ByteArray, val encryptedOffset: Int, val keyBytes: Int, val derivedBytes: Int
) {
    private val saltValue = salt.copyOf()
    private val headerValue = bytes.copyOf()
    fun salt(): ByteArray = saltValue.copyOf()
    /** Vault header is AAD; old backup headers deliberately are NOT AAD. */
    fun bytes(): ByteArray = headerValue.copyOf()
    override fun toString() = "ProtocolHeader(redacted)"
}
class ProtocolHeaderResult internal constructor(val header: ProtocolHeader?, val failure: ProtocolFailure?)
class ProtocolRouteResult internal constructor(val route: BackupRoute?, val failure: ProtocolFailure?)

/** Central KDF resource ceiling. Historical PBKDF2 vault and backup ceilings intentionally differ. */
object KdfPolicy {
    const val MAX_MEMORY_KIB = 262_144
    const val MAX_ARGON_ITERATIONS = 64
    const val MAX_PARALLELISM = 16
    const val MAX_BLOCK_PASSES = 1_048_576L
    const val MAX_VAULT_PBKDF2 = 20_000_000
    const val MAX_BACKUP_PBKDF2 = 1_000_000
    fun validArgon(saltBytes: Int, memoryKiB: Int, iterations: Int, parallelism: Int, tagBytes: Int): Boolean =
        saltBytes in 8..1024 && parallelism in 1..MAX_PARALLELISM &&
            memoryKiB in 8 * parallelism..MAX_MEMORY_KIB && iterations in 1..MAX_ARGON_ITERATIONS &&
            memoryKiB.toLong() * iterations <= MAX_BLOCK_PASSES && tagBytes in 4..1024
}

/** Nonthrowing entry points are safe to call over Kotlin/Native's ObjC boundary.
 * Parsers need only a bounded prefix (40/30 bytes) and the actual full size, avoiding large bridge
 * copies. Native adapters must supply the actual byte count, not a declared untrusted length.
 */
class VaultProtocol {
    fun isVault(prefix: ByteArray): Boolean = starts(prefix, VAULT_MAGIC)
    fun isBackup(prefix: ByteArray): Boolean = starts(prefix, BACKUP_MAGIC)
    fun isLinScp(prefix: ByteArray): Boolean = starts(prefix, LINSCP_MAGIC)

    fun vaultHeader(prefix: ByteArray, totalBytes: Int): ProtocolHeaderResult {
        if (totalBytes !in OVERHEAD_BYTES + 1..MAX_DOCUMENT_BYTES || prefix.size < HEADER_BYTES) return bad(ProtocolFailure.SIZE)
        return checkedVault(prefix.copyOfRange(0, HEADER_BYTES))
    }
    fun makeVaultHeader(kdf: String, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int): ProtocolHeaderResult {
        if (salt.size != 16) return bad(ProtocolFailure.PARAMETERS)
        val algorithm = when (kdf) { "argon2id" -> 1; "pbkdf2" -> 2; else -> return bad(ProtocolFailure.PARAMETERS) }
        val out = ByteArray(HEADER_BYTES)
        VAULT_MAGIC.copyInto(out); out[8] = 1; out[9] = 1; out[10] = algorithm.toByte()
        putInt(out, 12, if (algorithm == 1) memoryKiB else 0)
        putInt(out, 16, iterations); putInt(out, 20, if (algorithm == 1) parallelism else 0)
        salt.copyInto(out, 24)
        return checkedVault(out)
    }
    private fun checkedVault(bytes: ByteArray): ProtocolHeaderResult {
        if (!isVault(bytes)) return bad(ProtocolFailure.FORMAT)
        if (bytes[8].toInt() != 1 || bytes[9].toInt() != 1 || bytes[11].toInt() != 0) return bad(ProtocolFailure.VERSION)
        val memory = intAt(bytes, 12); val iterations = intAt(bytes, 16); val lanes = intAt(bytes, 20)
        val argon = when (bytes[10].toInt()) { 1 -> true; 2 -> false; else -> return bad(ProtocolFailure.PARAMETERS) }
        if (if (argon) !KdfPolicy.validArgon(16, memory, iterations, lanes, 64)
            else memory != 0 || lanes != 0 || iterations !in 1..KdfPolicy.MAX_VAULT_PBKDF2) return bad(ProtocolFailure.PARAMETERS)
        return good(argon, memory, iterations, lanes, bytes.copyOfRange(24, 40), bytes, 40, if (argon) 32 else 16, if (argon) 64 else 32)
    }
    fun backupHeader(prefix: ByteArray, totalBytes: Int): ProtocolHeaderResult {
        if (totalBytes !in 44..MAX_DOCUMENT_BYTES || prefix.size < 16) return bad(ProtocolFailure.SIZE)
        if (isBackup(prefix)) {
            if (totalBytes < 58 || prefix.size < 30) return bad(ProtocolFailure.SIZE)
            if (prefix[4].toInt() != 1) return bad(ProtocolFailure.VERSION)
            val memory = intAt(prefix, 5); val iterations = intAt(prefix, 9); val lanes = prefix[13].toInt() and 255
            if (!KdfPolicy.validArgon(16, memory, iterations, lanes, 32)) return bad(ProtocolFailure.PARAMETERS)
            return good(true, memory, iterations, lanes, prefix.copyOfRange(14, 30), prefix.copyOfRange(0, 30), 30, 32, 32)
        }
        val iterations = intAt(prefix, 0)
        if (iterations !in 1..KdfPolicy.MAX_BACKUP_PBKDF2) return bad(ProtocolFailure.PARAMETERS)
        return good(false, 0, iterations, 0, prefix.copyOfRange(4, 16), prefix.copyOfRange(0, 16), 16, 32, 32)
    }
    fun makeBackupHeader(salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int): ProtocolHeaderResult {
        if (salt.size != 16 || !KdfPolicy.validArgon(salt.size, memoryKiB, iterations, parallelism, 32)) return bad(ProtocolFailure.PARAMETERS)
        val bytes = ByteArray(30); BACKUP_MAGIC.copyInto(bytes); bytes[4] = 1
        putInt(bytes, 5, memoryKiB); putInt(bytes, 9, iterations); bytes[13] = parallelism.toByte(); salt.copyInto(bytes, 14)
        return backupHeader(bytes, 58)
    }
    /** Explicit selection never falls back after parse/authentication failure. Auto preserves the
     * existing legacy reader only; pre063 is NEVER auto-detected. Entry validation is the next layer. */
    fun select(prefix: ByteArray, totalBytes: Int, format: String, variant: String): ProtocolRouteResult {
        fun route(value: BackupRoute) = ProtocolRouteResult(value, null)
        fun fail(error: ProtocolFailure) = ProtocolRouteResult(null, error)
        if (totalBytes !in 1..MAX_DOCUMENT_BYTES) return fail(ProtocolFailure.SIZE)
        val vault = isVault(prefix); val linscp = isLinScp(prefix)
        if (format == "auto") return when {
            vault -> route(BackupRoute.VAULT_COPY)
            linscp -> route(BackupRoute.LINSCP)
            else -> backupHeader(prefix, totalBytes).let { if (it.header != null) route(BackupRoute.OFFLINEOTP) else fail(it.failure!!) }
        }
        if (format == "vault-copy") return if (vault) route(BackupRoute.VAULT_COPY) else fail(ProtocolFailure.FORMAT)
        if (vault || linscp) return fail(ProtocolFailure.FORMAT)
        if (format == "offlineotp" || (format == "andotp" && variant == "encrypted")) {
            val parsed = backupHeader(prefix, totalBytes)
            val header = parsed.header ?: return fail(parsed.failure!!)
            return if (format == "offlineotp") route(BackupRoute.OFFLINEOTP)
                else if (header.kdf == ProtocolKdf.PBKDF2_SHA1) route(BackupRoute.ANDOTP_PBKDF2) else fail(ProtocolFailure.FORMAT)
        }
        if (format == "andotp") return when (variant) {
            "plain" -> route(BackupRoute.PLAIN)
            "pre063" -> if (totalBytes > 28 && !isBackup(prefix)) route(BackupRoute.ANDOTP_PRE063) else fail(ProtocolFailure.FORMAT)
            else -> fail(ProtocolFailure.FORMAT)
        }
        return fail(ProtocolFailure.FORMAT)
    }
    private fun good(argon: Boolean, memory: Int, iterations: Int, lanes: Int, salt: ByteArray, bytes: ByteArray, offset: Int, key: Int, derived: Int) =
        ProtocolHeaderResult(ProtocolHeader(if (argon) ProtocolKdf.ARGON2ID else ProtocolKdf.PBKDF2_SHA1, memory, iterations, lanes, salt, bytes, offset, key, derived), null)
    private fun bad(error: ProtocolFailure) = ProtocolHeaderResult(null, error)
    companion object {
        const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
        const val HEADER_BYTES = 40
        const val OVERHEAD_BYTES = 68
        const val MAX_PLAINTEXT_BYTES = MAX_DOCUMENT_BYTES - OVERHEAD_BYTES
        private val VAULT_MAGIC = byteArrayOf(79, 84, 80, 86, 76, 84, 13, 10)
        private val BACKUP_MAGIC = byteArrayOf(79, 79, 84, 80)
        private val LINSCP_MAGIC = byteArrayOf(76, 83, 67, 80, 86, 76, 84, 50)
        private fun starts(bytes: ByteArray, magic: ByteArray) = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
        private fun intAt(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { value, i -> (value shl 8) or (bytes[offset + i].toInt() and 255) }
        private fun putInt(bytes: ByteArray, offset: Int, value: Int) { for (i in 0..3) bytes[offset + i] = (value ushr (24 - i * 8)).toByte() }
    }
}
