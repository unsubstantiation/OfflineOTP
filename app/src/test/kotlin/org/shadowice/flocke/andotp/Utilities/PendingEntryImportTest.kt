package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import javax.crypto.spec.SecretKeySpec

class PendingEntryImportTest {
    private fun entry(): Entry = Entry("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&issuer=Example")

    @Test
    fun confirmationTransfersOwnershipOnlyOnce() {
        val entry = entry()
        val pending = PendingEntryImport(entry, 12)
        assertEquals("PendingEntryImport(redacted)", pending.toString())
        assertTrue(pending.take(12, true) === entry)
        assertNull(pending.take(12, true))
        pending.close()
        assertNotNull(entry.getSecret()) // the caller owns the transferred entry
        entry.destroy()
    }

    @Test
    fun dismissalAndInvalidAuthorizationDestroyTheParsedEntry() {
        val cancelled = entry()
        val pending = PendingEntryImport(cancelled, 4)
        pending.close()
        pending.close()
        assertNull(cancelled.getSecret())
        assertNull(pending.take(4, true))

        val locked = entry()
        val lockedPending = PendingEntryImport(locked, 4)
        assertNull(lockedPending.take(4, false))
        assertNull(locked.getSecret())
        assertNull(lockedPending.take(4, true))
    }

    @Test
    fun aReopenedSessionCannotAcceptAnOldConfirmation() {
        val lock = Any()
        val controller = SessionController(lock)
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val first = controller.beginUnlock()
        assertTrue(controller.activate(key, first.generation) is SessionCommandResult.Applied)
        val entry = entry()
        val pending = PendingEntryImport(entry, controller.snapshot().generation)

        controller.lock(SessionLockReason.EXPLICIT)
        val second = controller.beginUnlock()
        assertTrue(controller.activate(key, second.generation) is SessionCommandResult.Applied)
        assertFalse(controller.currentKey() == null)
        synchronized(lock) {
            assertNull(pending.take(controller.snapshot().generation, controller.currentKey() != null))
        }
        assertNull(entry.getSecret())
        assertNull(pending.take(controller.snapshot().generation, true))
        controller.lock(SessionLockReason.EXPLICIT)
    }
}
