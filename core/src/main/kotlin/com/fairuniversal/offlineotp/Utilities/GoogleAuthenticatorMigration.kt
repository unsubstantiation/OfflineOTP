package com.fairuniversal.offlineotp.Utilities

import java.util.TreeMap

/** Parsing failures never contain the scanned URI, account names or secrets. */
enum class MigrationError { INVALID_URI, TOO_LARGE, INVALID_DATA, UNSUPPORTED }

sealed class MigrationParseResult {
    class Success(val payload: MigrationPayload) : MigrationParseResult() {
        override fun toString(): String = "MigrationParseResult.Success(redacted)"
    }
    class Failure(val error: MigrationError) : MigrationParseResult()
}

/** Owns the validated accounts in one QR until transferred or closed. */
class MigrationPayload(
    val version: Int, val batchSize: Int, val batchIndex: Int, val batchId: Int,
    accounts: List<ValidatedOtp>
) : AutoCloseable {
    val count: Int = accounts.size
    private var owned: ArrayList<ValidatedOtp>? = ArrayList(accounts)

    @Synchronized
    fun takeAccounts(): ArrayList<ValidatedOtp> {
        val result = checkNotNull(owned) { "Migration payload already consumed" }
        owned = null
        return result
    }

    @Synchronized
    override fun close() {
        owned?.forEach { it.close() }
        owned = null
    }

    override fun toString(): String = "MigrationPayload(redacted)"
}

/** Native seed/payload ownership and synchronization stay in this adapter. */
object GoogleAuthenticatorMigration {
    fun isMigration(input: String?): Boolean = com.fairuniversal.offlineotp.domain.MigrationInterchangeCodec.isMigration(input)
    fun parse(input: String?): MigrationParseResult = when (val result = com.fairuniversal.offlineotp.domain.MigrationInterchangeCodec.parse(input)) {
        is com.fairuniversal.offlineotp.domain.PortableMigrationResult.Failure -> MigrationParseResult.Failure(MigrationError.valueOf(result.error.name))
        is com.fairuniversal.offlineotp.domain.PortableMigrationResult.Success -> {
            val payload = result.payload
            val portable = payload.takeAccounts()
            val adopted = ArrayList<ValidatedOtp>()
            try {
                for (value in portable) adopted.add(adoptPortableOtp(value))
                MigrationParseResult.Success(MigrationPayload(payload.version, payload.batchSize, payload.batchIndex, payload.batchId, adopted))
            } catch (error: Exception) { adopted.forEach { it.close() }; throw error }
            finally { portable.forEach { it.destroy() }; payload.destroy() }
        }
    }
}

enum class MigrationBatchStatus { ADDED, COMPLETE, DUPLICATE, DIFFERENT_EXPORT, TOO_MANY, STALE }

/** Holds a single, session-bound export in memory until every QR has been scanned. */
class MigrationBatchCollector(private val generation: Long) : AutoCloseable {
    private val parts = TreeMap<Int, MigrationPayload>()
    private val policy = com.fairuniversal.offlineotp.domain.MigrationBatchPolicy(generation)

    val received: Int @Synchronized get() = policy.received
    val expected: Int @Synchronized get() = policy.expected

    /** Always consumes payload; shared policy never owns a seed or native callback. */
    @Synchronized fun add(payload: MigrationPayload, currentGeneration: Long, sessionReady: Boolean): MigrationBatchStatus {
        val status = MigrationBatchStatus.valueOf(policy.add(payload.version, payload.batchSize, payload.batchIndex,
            payload.batchId, payload.count, currentGeneration, sessionReady).name)
        if (status == MigrationBatchStatus.ADDED || status == MigrationBatchStatus.COMPLETE) parts[payload.batchIndex] = payload
        else {
            payload.close()
            if (status == MigrationBatchStatus.STALE || status == MigrationBatchStatus.TOO_MANY) close()
        }
        return status
    }

    /** Transfers all accounts in QR order exactly once after the whole batch is present. */
    @Synchronized
    fun takeAccounts(currentGeneration: Long, sessionReady: Boolean): ArrayList<ValidatedOtp>? {
        if (!policy.take(currentGeneration, sessionReady)) { close(); return null }
        val result = ArrayList<ValidatedOtp>()
        try {
            for (payload in parts.values) result.addAll(payload.takeAccounts())
            parts.clear()
            return result
        } catch (error: RuntimeException) {
            result.forEach { it.close() }
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        parts.values.forEach { it.close() }
        parts.clear()
        policy.cancel()
    }

    override fun toString(): String = "MigrationBatchCollector(redacted)"
}
