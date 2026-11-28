package com.fairuniversal.offlineotp.domain

enum class SessionLockReason { INITIAL, EXPLICIT, AUTHENTICATION, SCREEN_OFF, BACKGROUND, RESET }
enum class SessionFailure { VAULT_UNAVAILABLE, RECOVERY_REQUIRED }
enum class SessionRejection { STALE_GENERATION, FAILED_SESSION, INVALID_HANDOFF }
enum class SessionPhase { LOCKED, UNLOCKING, UNLOCKED, FAILED }

/** Serialized, secret-free session authority. Native owners hold keys, locks, listeners and cleanup.
 * A generation is never reused. Exhaustion is terminal/fail-closed rather than wrapping to an old
 * authorization. `activate` is called ONLY after the native vault has authenticated the supplied key.
 */
class SessionAuthority {
    var generation: Long = 0
        private set
    var phase: SessionPhase = SessionPhase.LOCKED
        private set
    var reason: SessionLockReason = SessionLockReason.INITIAL
        private set
    var failure: SessionFailure? = null
        private set
    private var exhausted = false

    fun isCurrent(expected: Long): Boolean = !exhausted && generation == expected
    fun isUnlocked(expected: Long): Boolean = isCurrent(expected) && phase == SessionPhase.UNLOCKED
    fun beginUnlock(): Long { advance(); if (!exhausted) { phase = SessionPhase.UNLOCKING; failure = null }; return generation }
    fun lock(reason: SessionLockReason): Long { advance(); if (!exhausted) { phase = SessionPhase.LOCKED; this.reason = reason; failure = null }; return generation }
    fun fail(error: SessionFailure): Long { advance(); phase = SessionPhase.FAILED; failure = error; return generation }
    /** Null means applied; replacing a native key invalidates old operations even while unlocked.
     * Same-key reactivation is intentionally idempotent for existing JVM session callers. */
    fun activate(expected: Long, keyChanged: Boolean): SessionRejection? {
        if (!isCurrent(expected)) return SessionRejection.STALE_GENERATION
        if (phase == SessionPhase.FAILED) return SessionRejection.FAILED_SESSION
        if (keyChanged) advance()
        if (exhausted) return SessionRejection.FAILED_SESSION
        phase = SessionPhase.UNLOCKED; failure = null
        return null
    }
    fun handoffRejection(expected: Long, size: Int): SessionRejection? = when {
        !isCurrent(expected) -> SessionRejection.STALE_GENERATION
        phase == SessionPhase.FAILED -> SessionRejection.FAILED_SESSION
        size !in 1..64 -> SessionRejection.INVALID_HANDOFF
        else -> null
    }
    fun canOfferBackup(expected: Long): Boolean = isCurrent(expected) && (phase == SessionPhase.LOCKED || phase == SessionPhase.UNLOCKING)
    private fun advance() {
        if (generation == Long.MAX_VALUE) { exhausted = true; phase = SessionPhase.FAILED; failure = SessionFailure.VAULT_UNAVAILABLE }
        else generation++
    }
    override fun toString() = "SessionAuthority(redacted)"
}

/** One native operation's result-delivery authority. No provider/key/credential is stored here.
 * `current` is a non-consuming preflight and `complete` consumes result delivery once. A successful
 * provider handoff is NOT unlocked-session authority: native file authentication must still precede
 * SessionAuthority.activate. Close is terminal; cancel permits an explicit retry.
 */
class UnlockAttemptPolicy {
    private var serial = 0L
    private var authority = 0L
    private var pending = false
    private var closed = false
    fun start(generation: Long, permitted: Boolean): Long {
        if (closed || pending || !permitted || serial == Long.MAX_VALUE) return 0
        serial++; authority = generation; pending = true; return serial
    }
    fun current(ticket: Long, generation: Long, permitted: Boolean): Boolean =
        !closed && pending && ticket != 0L && ticket == serial && generation == authority && permitted
    fun complete(ticket: Long, generation: Long, permitted: Boolean, authenticated: Boolean): Boolean {
        if (ticket == 0L || ticket != serial || !pending || closed) return false
        val accept = current(ticket, generation, permitted) && authenticated
        pending = false
        return accept
    }
    fun cancel() { pending = false }
    fun close() { closed = true; pending = false }
    override fun toString() = "UnlockAttemptPolicy(redacted)"
}

object SessionResultPolicy {
    fun accepts(resultOk: Boolean, requestPending: Boolean, sessionGenerationCurrent: Boolean): Boolean =
        resultOk && requestPending && sessionGenerationCurrent
}

/** Monotonic-time decision only. Scheduler callbacks remain native; stale runnables must also
 * match their native owner/token. Saturating addition avoids overflow reviving an old deadline.
 * A clock moving backwards is fail-closed. Suspend must not cancel the deadline; lock/close does.
 */
class InactivityPolicy {
    private var started: Long? = null
    private var deadline: Long? = null
    fun arm(nowMillis: Long, timeoutMillis: Long) {
        cancel()
        if (timeoutMillis <= 0) return
        started = nowMillis
        deadline = if (nowMillis > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else nowMillis + timeoutMillis
    }
    fun expired(nowMillis: Long): Boolean {
        val start = started ?: return false
        val end = deadline ?: return false
        return nowMillis < start || nowMillis >= end
    }
    fun remaining(nowMillis: Long): Long {
        val end = deadline ?: return 0
        if (expired(nowMillis)) return 0
        return if (nowMillis < 0 && end > Long.MAX_VALUE + nowMillis) Long.MAX_VALUE else end - nowMillis
    }
    fun cancel() { started = null; deadline = null }
    override fun toString() = "InactivityPolicy(redacted)"
}
