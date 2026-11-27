package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOpenPgpSigningCredentialsTest {
    @Test fun ownsAndConsumesInputsAndSnapshotsAreIndependentAndWipeable() {
        val session = SessionOpenPgpSigningCredentials()
        val key = byteArrayOf(1, 2); val password = "session-secret".toCharArray()
        assertTrue(session.load(key, password)); assertTrue(key.all { it == 0.toByte() }); assertTrue(password.all { it == '\u0000' })
        val revision = session.revision(); val work = session.snapshot(revision)!!
        session.clear(); assertFalse(session.available()); assertNull(session.snapshot(revision))
        assertEquals(1, work.key[0].toInt()); assertEquals("session-secret", work.passphrase.concatToString())
        work.close(); assertTrue(work.key.all { it == 0.toByte() }); assertTrue(work.passphrase.all { it == '\u0000' })
        assertFalse(work.toString().contains("session-secret")); session.close()
    }
    @Test fun replacementRejectsOldRevisionAndClosedOwnersNeverReload() {
        val session = SessionOpenPgpSigningCredentials()
        assertTrue(session.load(byteArrayOf(1), charArrayOf()))
        val old = session.revision()
        assertTrue(session.load(byteArrayOf(2), "new".toCharArray()))
        assertNull(session.snapshot(old))
        session.close()
        val key = byteArrayOf(3); val password = "secret".toCharArray()
        assertFalse(session.load(key, password)); assertTrue(key.all { it == 0.toByte() }); assertTrue(password.all { it == '\u0000' })
        assertNull(session.snapshot(session.revision())); assertFalse(session.available())
    }
}
