package com.fairuniversal.offlineotp.Utilities

import javax.crypto.SecretKey

typealias SessionLockReason = com.fairuniversal.offlineotp.domain.SessionLockReason
typealias SessionFailure = com.fairuniversal.offlineotp.domain.SessionFailure
typealias SessionRejection = com.fairuniversal.offlineotp.domain.SessionRejection

/** Diagnostic state only: never a key, credential, Parcelable or saved-state payload. */
sealed class SessionState(val generation: Long) {
    class Locked(generation: Long, val reason: SessionLockReason) : SessionState(generation)
    class Unlocking(generation: Long) : SessionState(generation)
    class Unlocked(generation: Long) : SessionState(generation)
    class Failed(generation: Long, val error: SessionFailure) : SessionState(generation)
    final override fun toString(): String = "SessionState.${javaClass.simpleName}(generation=$generation)"
}

sealed class SessionCommandResult {
    class Applied(val state: SessionState) : SessionCommandResult()
    data class Rejected(val reason: SessionRejection) : SessionCommandResult()
}

/** Owned transport bytes. No generated equality, copying, destructuring or secret diagnostics. */
class OwnedSecret private constructor(bytes: ByteArray) : AutoCloseable {
    private var material: ByteArray? = bytes.also { require(it.isNotEmpty() && it.size <= 64) }.copyOf()
    @Synchronized fun takeBytes(): ByteArray {
        val value = checkNotNull(material) { "Secret already consumed or closed" }
        material = null
        return value
    }
    @Synchronized override fun close() { material?.fill(0); material = null }
    override fun toString(): String = "OwnedSecret(redacted)"
    companion object {
        @JvmStatic fun copyOf(bytes: ByteArray): OwnedSecret = OwnedSecret(bytes)
    }
}

sealed class SessionHandoff {
    class Available(val secret: OwnedSecret) : SessionHandoff(), AutoCloseable {
        override fun close() = secret.close()
        override fun toString(): String = "SessionHandoff.Available(redacted)"
    }
    data class Unavailable(val reason: Reason) : SessionHandoff()
    enum class Reason { MISSING, STALE_GENERATION }
}

/** An owned backup-key copy tied to one unlocked session and credential revision. */
class LoginBackupLease(
    val key: LoginBackupKey,
    val generation: Long,
    val revision: Long
) : AutoCloseable {
    override fun close() = key.close()
    override fun toString(): String = "LoginBackupLease(redacted)"
}

/** Android-free process controller. Lock order: mutationLock -> monitor; callbacks outside monitor. */
class SessionController(private val mutationLock: Any) {
    private val monitor = Any()
    private val authority = com.fairuniversal.offlineotp.domain.SessionAuthority()
    private val state: SessionState get() = when (authority.phase) {
        com.fairuniversal.offlineotp.domain.SessionPhase.LOCKED -> SessionState.Locked(authority.generation, authority.reason)
        com.fairuniversal.offlineotp.domain.SessionPhase.UNLOCKING -> SessionState.Unlocking(authority.generation)
        com.fairuniversal.offlineotp.domain.SessionPhase.UNLOCKED -> SessionState.Unlocked(authority.generation)
        com.fairuniversal.offlineotp.domain.SessionPhase.FAILED -> SessionState.Failed(authority.generation, authority.failure ?: SessionFailure.VAULT_UNAVAILABLE)
    }
    private var key: SecretKey? = null
    private var handoff: OwnedSecret? = null
    private var backupHandoff: LoginBackupKey? = null
    private var backupKey: LoginBackupKey? = null
    private var backupRevision: Long = 0
    private val listeners = LinkedHashSet<Runnable>()
    private val changes = LinkedHashSet<Runnable>()
    private val backupChanges = LinkedHashSet<Runnable>()

    fun snapshot(): SessionState = synchronized(monitor) { state }
    fun currentKey(): SecretKey? = synchronized(monitor) { if (state is SessionState.Unlocked) key else null }
    fun isCurrent(expected: Long): Boolean = synchronized(monitor) { authority.isCurrent(expected) }

    fun activate(newKey: SecretKey, expected: Long): SessionCommandResult {
        var callbacks: List<Runnable> = emptyList()
        val result = synchronized(mutationLock) {
            synchronized(monitor) {
                val wasUnlocked = state is SessionState.Unlocked
                val changed = key !== newKey
                val rejection = authority.activate(expected, changed)
                if (rejection != null) SessionCommandResult.Rejected(rejection)
                else {
                    clearHandoff()
                    val incomingBackup = backupHandoff
                    backupHandoff = null
                    if (incomingBackup != null) { clearBackupKey(); backupKey = incomingBackup }
                    else if (!wasUnlocked) clearBackupKey()
                    key = newKey
                    if (changed) callbacks = changes.toList()
                    SessionCommandResult.Applied(state)
                }
            }
        }
        notifyListeners(callbacks)
        return result
    }

    fun beginUnlock(): SessionState.Unlocking {
        val transition = revoke { authority.beginUnlock() }
        notifyListeners(transition.second)
        return transition.first as SessionState.Unlocking
    }
    fun lock(reason: SessionLockReason) {
        val transition = revoke { authority.lock(reason) }
        notifyListeners(transition.second)
    }
    fun fail(error: SessionFailure) {
        val transition = revoke { authority.fail(error) }
        notifyListeners(transition.second)
    }
    private fun revoke(next: () -> Long): Pair<SessionState, List<Runnable>> = synchronized(mutationLock) {
        synchronized(monitor) {
            key = null
            clearHandoff()
            backupHandoff?.close()
            backupHandoff = null
            clearBackupKey()
            next()
            state to (listeners + changes + backupChanges).toList()
        }
    }
    private fun notifyListeners(callbacks: List<Runnable>) {
        callbacks.forEach { try { it.run() } catch (_: RuntimeException) { /* Do not skip other cleanup. */ } }
    }
    private fun clearHandoff() { handoff?.close(); handoff = null }
    private fun clearBackupKey() { backupKey?.close(); backupKey = null; backupRevision++ }

    /** Publishes a password-derived backup key only for the current pending unlock/setup. */
    internal fun offerBackupKey(candidate: LoginBackupKey, expected: Long): Boolean = synchronized(monitor) {
        if (!authority.canOfferBackup(expected))
            return@synchronized false
        val copy = candidate.copy() ?: return@synchronized false
        backupHandoff?.close()
        backupHandoff = copy
        true
    }

    /** Replaces the key inside a vault transaction; notify after releasing the outer mutation lock. */
    internal fun replaceBackupKeyWithoutNotification(candidate: LoginBackupKey?, expected: Long): Boolean =
        synchronized(mutationLock) {
            synchronized(monitor) inner@{
                if (!authority.isUnlocked(expected)) return@inner false
                val copy = candidate?.copy()
                if (candidate != null && copy == null) return@inner false
                clearBackupKey()
                backupKey = copy
                true
            }
        }

    internal fun notifyBackupKeyChanged() {
        val callbacks = synchronized(monitor) { backupChanges.toList() }
        notifyListeners(callbacks)
    }

    internal fun copyBackupKey(): LoginBackupLease? = synchronized(monitor) {
        if (state !is SessionState.Unlocked) return@synchronized null
        val copy = backupKey?.copy() ?: return@synchronized null
        LoginBackupLease(copy, state.generation, backupRevision)
    }

    internal fun isBackupCurrent(lease: LoginBackupLease): Boolean = synchronized(monitor) {
        state is SessionState.Unlocked && state.generation == lease.generation &&
            backupRevision == lease.revision && backupKey != null
    }

    fun publishDerived(bytes: ByteArray, expected: Long): SessionCommandResult = synchronized(monitor) {
        val rejection = authority.handoffRejection(expected, bytes.size)
        if (rejection != null) SessionCommandResult.Rejected(rejection)
        else {
            clearHandoff()
            handoff = OwnedSecret.copyOf(bytes)
            SessionCommandResult.Applied(state)
        }
    }
    fun consumeDerived(expected: Long): SessionHandoff = synchronized(monitor) {
        if (!authority.isCurrent(expected)) return@synchronized SessionHandoff.Unavailable(SessionHandoff.Reason.STALE_GENERATION)
        val value = handoff ?: return@synchronized SessionHandoff.Unavailable(SessionHandoff.Reason.MISSING)
        handoff = null
        SessionHandoff.Available(value)
    }
    internal fun addBackupChangeListener(listener: Runnable) { synchronized(monitor) { backupChanges.add(listener) } }
    internal fun removeBackupChangeListener(listener: Runnable) { synchronized(monitor) { backupChanges.remove(listener) } }
    fun addChangeListener(listener: Runnable) { synchronized(monitor) { changes.add(listener) } }
    fun removeChangeListener(listener: Runnable) { synchronized(monitor) { changes.remove(listener) } }
    fun addLockListener(listener: Runnable) { synchronized(monitor) { listeners.add(listener) } }
    fun removeLockListener(listener: Runnable) { synchronized(monitor) { listeners.remove(listener) } }
}
