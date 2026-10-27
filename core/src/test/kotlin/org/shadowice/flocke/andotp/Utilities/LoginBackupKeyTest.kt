package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

class LoginBackupKeyTest {
    private val password = "correct horse battery staple"
    private val plaintext = "[{\"secret\":\"JBSWY3DPEHPK3PXP\"}]".toByteArray()

    private fun decrypt(frame: ByteArray, password: String): ByteArray {
        val header = EncryptedBackupHeader.parse(frame)
        val key = CryptoPrimitives.generateSymmetricKeyPBKDF2(password, header.getIterations(), header.getSalt())
        return CryptoPrimitives.decrypt(key, header.getEncrypted())
    }

    @Test
    fun snapshotsUseExistingFramingAndTheLoginPassword() {
        LoginBackupKey.derive(password).use { key ->
            val first = key.encrypt(plaintext)
            val second = key.encrypt(plaintext)
            val header1 = EncryptedBackupHeader.parse(first)
            val header2 = EncryptedBackupHeader.parse(second)
            assertEquals(Constants.PBKDF2_MAX_BACKUP_ITERATIONS, header1.getIterations())
            assertArrayEquals(header1.getSalt(), header2.getSalt())
            assertFalse(header1.getEncrypted().copyOfRange(0, 12).contentEquals(header2.getEncrypted().copyOfRange(0, 12)))
            assertArrayEquals(plaintext, decrypt(first, password))
            assertArrayEquals(plaintext, decrypt(second, password))
            try {
                decrypt(first, "$password wrong")
                throw AssertionError("wrong password accepted")
            } catch (_: GeneralSecurityException) { }
            val tampered = first.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            try {
                decrypt(tampered, password)
                throw AssertionError("tampered backup accepted")
            } catch (_: GeneralSecurityException) { }
            LoginBackupKey.derive(password).use { nextSession ->
                val next = EncryptedBackupHeader.parse(nextSession.encrypt(plaintext))
                assertFalse(header1.getSalt().contentEquals(next.getSalt()))
            }
        }
    }

    @Test
    fun copiesAreOwnedAndTheNonceBudgetIsShared() {
        val key = LoginBackupKey.derive(password)
        val copy = requireNotNull(key.copy())
        // Exercise the final two counter states without generating tens of thousands of files.
        val field = key.javaClass.getDeclaredField("budget").apply { isAccessible = true }
        val budget = field.get(key)
        budget.javaClass.getDeclaredField("used").apply { isAccessible = true }.setInt(budget, 65_535)
        key.encrypt(plaintext)
        try {
            copy.encrypt(plaintext)
            throw AssertionError("copies exceeded the shared nonce budget")
        } catch (_: IllegalStateException) { }
        copy.close()
        copy.close()
        assertNull(copy.copy())
        key.close()
        assertNull(key.copy())
        assertEquals("LoginBackupKey(redacted)", key.toString())
    }

    @Test
    fun sessionHandoffsAndCredentialReplacementRevokeOldLeases() {
        val session = SessionController(Any())
        val first = session.beginUnlock()
        val vault = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        LoginBackupKey.derive(password).use { candidate ->
            assertFalse(session.offerBackupKey(candidate, first.generation - 1))
            assertTrue(session.offerBackupKey(candidate, first.generation))
            assertTrue(session.activate(vault, first.generation) is SessionCommandResult.Applied)
        }
        val initial = requireNotNull(session.copyBackupKey())
        assertTrue(session.isBackupCurrent(initial))
        assertArrayEquals(plaintext, decrypt(initial.key.encrypt(plaintext), password))
        val generation = session.snapshot().generation
        LoginBackupKey.derive("new strong password").use { next ->
            assertFalse(session.replaceBackupKeyWithoutNotification(next, generation - 1))
            assertTrue(session.replaceBackupKeyWithoutNotification(next, generation))
            session.notifyBackupKeyChanged()
        }
        assertFalse(session.isBackupCurrent(initial))
        initial.close()
        val replacement = requireNotNull(session.copyBackupKey())
        assertTrue(session.isBackupCurrent(replacement))
        assertArrayEquals(plaintext, decrypt(replacement.key.encrypt(plaintext), "new strong password"))
        session.lock(SessionLockReason.EXPLICIT)
        assertFalse(session.isBackupCurrent(replacement))
        assertNull(session.copyBackupKey())
        replacement.close()
        assertNotNull(session.beginUnlock())
        assertNull(session.copyBackupKey())
    }

    @Test
    fun backupReplacementInvalidatesBeforeNotifyingOutsideTheMutationLock() {
        val mutationLock = Any()
        val session = SessionController(mutationLock)
        val generation = session.beginUnlock().generation
        LoginBackupKey.derive(password).use { assertTrue(session.offerBackupKey(it, generation)) }
        assertTrue(session.activate(SecretKeySpec(ByteArray(16) { 1 }, "AES"), generation) is SessionCommandResult.Applied)
        val old = requireNotNull(session.copyBackupKey())
        var notified = false
        val listener = Runnable {
            assertFalse(Thread.holdsLock(mutationLock))
            assertFalse(session.isBackupCurrent(old))
            notified = true
        }
        session.addBackupChangeListener(listener)
        LoginBackupKey.derive("new login password").use { next ->
            synchronized(mutationLock) {
                assertTrue(session.replaceBackupKeyWithoutNotification(next, session.snapshot().generation))
                assertFalse(session.isBackupCurrent(old))
                assertFalse(notified)
            }
        }
        session.notifyBackupKeyChanged()
        assertTrue(notified)
        session.removeBackupChangeListener(listener)
        old.close()
        session.lock(SessionLockReason.EXPLICIT)
    }
}
