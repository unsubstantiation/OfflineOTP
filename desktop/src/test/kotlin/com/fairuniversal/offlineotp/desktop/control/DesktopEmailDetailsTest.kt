package com.fairuniversal.offlineotp.desktop.control

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEmailDetailsTest {
    private fun sample() = EmailAccounts.create("Work", "person@example.org", 0,
        "imap.example.org", 993, 0, 1, "person@example.org", "incoming secret",
        "smtp.example.org", 465, 0, 1, "person@example.org", "outgoing secret")
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
    @Test fun opensMaskedAndRevealsOnlyByExplicitToggleWithoutMutatingEntry() {
        val state = state(); val entry = sample(); state.add(entry)
        val details = DesktopEmailDetails(state)
        try {
            details.open(entry.getListId(), false); assertTrue(details.pending == null)
            details.open(entry.getListId(), true); val request = details.pending!!
            assertFalse(request.secretsVisible)
            assertFalse(details.rows(request, true)!!.any { it.value.contains("secret") })
            assertTrue(details.toggleSecrets(request, true))
            val rows = details.rows(request, true)!!
            assertTrue(rows.any { it.value == "incoming secret" })
            assertTrue(rows.any { it.value == "outgoing secret" })
            assertTrue(rows.any { it.value == "imap.example.org" })
            assertTrue(details.toggleSecrets(request, true))
            assertFalse(details.rows(request, true)!!.any { it.value.contains("secret") })
            details.dismiss(request)
            assertTrue(state.snapshot().single() === entry)
            assertTrue(entry.getSecretSize() > 0)
        } finally { details.close(); state.lock() }
    }
    @Test fun concealmentReplacementAndLockInvalidateExactRequest() {
        val state = state(); val entry = sample(); state.add(entry)
        val details = DesktopEmailDetails(state)
        try {
            details.open(entry.getListId(), true); val hidden = details.pending!!
            details.toggleSecrets(hidden, true)
            assertTrue(details.rows(hidden, false) == null)
            assertFalse(hidden.secretsVisible); assertTrue(details.pending == null)
            details.open(entry.getListId(), true); val stale = details.pending!!
            val replacement = sample()
            assertTrue(state.replace(entry, replacement))
            assertEquals(stale.entryId, replacement.getListId())
            assertFalse(details.toggleSecrets(stale, true))
            assertTrue(details.rows(stale, true) == null)
            details.open(replacement.getListId(), true); val locked = details.pending!!
            state.lock()
            assertTrue(details.rows(locked, true) == null)
            assertFalse(details.toggleSecrets(locked, true))
        } finally { details.close(); state.lock() }
    }
    @Test fun oldCallbacksCannotDismissOrRevealNewRequestAndCloseIsTerminal() {
        val state = state(); val entry = sample(); state.add(entry)
        val details = DesktopEmailDetails(state)
        try {
            details.open(entry.getListId(), true); val old = details.pending!!
            details.open(entry.getListId(), true); val current = details.pending!!
            details.dismiss(old)
            assertTrue(details.pending === current)
            assertFalse(details.toggleSecrets(old, true))
            assertFalse(current.secretsVisible)
            details.close(); details.open(entry.getListId(), true)
            assertTrue(details.pending == null)
            assertTrue(details.rows(current, true) == null)
        } finally { details.close(); state.lock() }
    }
}
