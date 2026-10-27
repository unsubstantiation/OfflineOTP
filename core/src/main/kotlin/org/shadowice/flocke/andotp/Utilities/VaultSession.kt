package org.shadowice.flocke.andotp.Utilities

import javax.crypto.SecretKey

/** Process-wide facade over [SessionController] for the active vault session and derived-key handoff. */
object VaultSession {
    @JvmField val MutationLock: Any = Any()
    private val controller: SessionController = SessionController(MutationLock)

    @JvmStatic fun snapshot(): SessionState = controller.snapshot()
    @JvmStatic fun getKey(): SecretKey? = controller.currentKey()
    @JvmStatic fun generation(): Long = snapshot().generation
    @JvmStatic fun isCurrent(expected: Long): Boolean = controller.isCurrent(expected)
    @JvmStatic fun beginUnlock(): SessionState.Unlocking = controller.beginUnlock()
    @JvmStatic fun activate(key: SecretKey, expected: Long): SessionCommandResult = controller.activate(key, expected)
    @JvmStatic fun fail(error: SessionFailure) = controller.fail(error)
    @JvmStatic fun lock() = lock(SessionLockReason.EXPLICIT)
    @JvmStatic fun lock(reason: SessionLockReason) = controller.lock(reason)

    /** Legacy bootstrap adapter; new async callers must pass their captured generation to activate. */
    @JvmStatic
    fun open(key: SecretKey) {
        synchronized(MutationLock) {
            if (activate(key, generation()) !is SessionCommandResult.Applied)
                throw IllegalStateException("Session activation rejected")
        }
    }
    /** Password-derived backup material is never persisted and is revoked with the session. */
    fun offerBackupKey(key: LoginBackupKey, expected: Long): Boolean = controller.offerBackupKey(key, expected)
    fun replaceBackupKeyWithoutNotification(key: LoginBackupKey?, expected: Long): Boolean =
        controller.replaceBackupKeyWithoutNotification(key, expected)
    fun notifyBackupKeyChanged() = controller.notifyBackupKeyChanged()
    fun copyBackupKey(): LoginBackupLease? = controller.copyBackupKey()
    fun isBackupCurrent(lease: LoginBackupLease): Boolean = controller.isBackupCurrent(lease)
    fun addBackupChangeListener(listener: Runnable) = controller.addBackupChangeListener(listener)
    fun removeBackupChangeListener(listener: Runnable) = controller.removeBackupChangeListener(listener)

    @JvmStatic fun publishDerived(bytes: ByteArray, expected: Long): SessionCommandResult = controller.publishDerived(bytes, expected)
    @JvmStatic fun consumeDerived(expected: Long): SessionHandoff = controller.consumeDerived(expected)
    @JvmStatic
    fun offerDerivedKey(bytes: ByteArray?, expected: Long): Boolean {
        return bytes != null && publishDerived(bytes, expected) is SessionCommandResult.Applied
    }
    /** Legacy one-use transport adapter; callers own and must wipe the returned array. */
    @JvmStatic fun takeDerivedKey(): ByteArray? = takeDerivedKey(generation())
    @JvmStatic
    fun takeDerivedKey(expected: Long): ByteArray? {
        val result = consumeDerived(expected)
        if (result !is SessionHandoff.Available) return null
        (result as SessionHandoff.Available).use { available ->
            return available.secret.takeBytes()
        }
    }
    @JvmStatic fun addChangeListener(listener: Runnable) = controller.addChangeListener(listener)
    @JvmStatic fun removeChangeListener(listener: Runnable) = controller.removeChangeListener(listener)
    @JvmStatic fun addLockListener(listener: Runnable) = controller.addLockListener(listener)
    @JvmStatic fun removeLockListener(listener: Runnable) = controller.removeLockListener(listener)
}
