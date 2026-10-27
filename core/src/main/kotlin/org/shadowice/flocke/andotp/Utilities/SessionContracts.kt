package org.shadowice.flocke.andotp.Utilities

import javax.crypto.SecretKey

enum class SessionLockReason { INITIAL, EXPLICIT, AUTHENTICATION, SCREEN_OFF, BACKGROUND, RESET }
enum class SessionFailure { VAULT_UNAVAILABLE, RECOVERY_REQUIRED }
enum class SessionRejection { STALE_GENERATION, FAILED_SESSION, INVALID_HANDOFF }

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
    private var state: SessionState = SessionState.Locked(0, SessionLockReason.INITIAL)
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
    fun isCurrent(expected: Long): Boolean = synchronized(monitor) { state.generation == expected }

    fun activate(newKey: SecretKey, expected: Long): SessionCommandResult {
        var callbacks: List<Runnable> = emptyList()
        val result = synchronized(mutationLock) {
            synchronized(monitor) {
                when {
                    state.generation != expected -> SessionCommandResult.Rejected(SessionRejection.STALE_GENERATION)
                    state is SessionState.Failed -> SessionCommandResult.Rejected(SessionRejection.FAILED_SESSION)
                    else -> {
                        val changed = key !== newKey
                        val generation = state.generation + if (changed) 1 else 0
                        clearHandoff()
                        val incomingBackup = backupHandoff
                        backupHandoff = null
                        if (incomingBackup != null) {
                            clearBackupKey()
                            backupKey = incomingBackup
                        } else if (state !is SessionState.Unlocked) {
                            clearBackupKey()
                        }
                        key = newKey
                        state = SessionState.Unlocked(generation)
                        if (changed) callbacks = changes.toList()
                        SessionCommandResult.Applied(state)
                    }
                }
            }
        }
        notifyListeners(callbacks)
        return result
    }

    fun beginUnlock(): SessionState.Unlocking {
        val transition = revoke { SessionState.Unlocking(it) }
        notifyListeners(transition.second)
        return transition.first as SessionState.Unlocking
    }
    fun lock(reason: SessionLockReason) {
        val transition = revoke { SessionState.Locked(it, reason) }
        notifyListeners(transition.second)
    }
    fun fail(error: SessionFailure) {
        val transition = revoke { SessionState.Failed(it, error) }
        notifyListeners(transition.second)
    }
    private fun revoke(next: (Long) -> SessionState): Pair<SessionState, List<Runnable>> = synchronized(mutationLock) {
        synchronized(monitor) {
            key = null
            clearHandoff()
            backupHandoff?.close()
            backupHandoff = null
            clearBackupKey()
            state = next(state.generation + 1)
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
        if (state.generation != expected || state is SessionState.Unlocked || state is SessionState.Failed)
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
                if (state.generation != expected || state !is SessionState.Unlocked) return@inner false
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
        when {
            state.generation != expected -> SessionCommandResult.Rejected(SessionRejection.STALE_GENERATION)
            state is SessionState.Failed -> SessionCommandResult.Rejected(SessionRejection.FAILED_SESSION)
            bytes.isEmpty() || bytes.size > 64 -> SessionCommandResult.Rejected(SessionRejection.INVALID_HANDOFF)
            else -> {
                clearHandoff()
                handoff = OwnedSecret.copyOf(bytes)
                SessionCommandResult.Applied(state)
            }
        }
    }
    fun consumeDerived(expected: Long): SessionHandoff = synchronized(monitor) {
        if (state.generation != expected) return@synchronized SessionHandoff.Unavailable(SessionHandoff.Reason.STALE_GENERATION)
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
