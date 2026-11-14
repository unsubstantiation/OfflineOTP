package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailPasswordInputTest {
    private fun entry(): Entry {
        val seed = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
            outgoingHost = "smtp.example.com", incomingPassword = "incoming", outgoingPassword = "outgoing").build()
        try {
            val account = seed.getEmailAccount()
            account.account.put("provider", JSONObject().put("unknown", "preserve"))
            val extra = JSONObject(account.identity.toString()).put("email", "alias@example.com").put("password", "alias")
            return Entry.email(account.account, account.identity, listOf(account.identity, extra), arrayListOf("tag")).also {
                it.setLabel("custom label"); it.setIssuer("custom issuer", false); it.setHiddenLabel("protected")
                it.setThumbnailName("Google"); it.setLastUsed(123L); it.setUsedFrequency(7); it.setDetailsVisible(false)
            }
        } finally { seed.destroy() }
    }
    @Test fun replacesOnlyRequestedCredentialsAndPreservesAllMetadata() {
        val original = entry()
        try {
            val initial = EmailPasswordInput.fromEntry(original)
            assertEquals("", initial.incoming); assertEquals(listOf("", ""), initial.outgoing)
            val changed = initial.copy(incoming = " new incoming ", outgoing = listOf("", "new alias")).build(original)
            try {
                val account = changed.getEmailAccount()
                assertEquals(" new incoming ", account.account.getString("password"))
                assertEquals("outgoing", account.identities[0].getString("password"))
                assertEquals("new alias", account.identities[1].getString("password"))
                val before = original.getEmailAccount()
                account.account.put("password", "incoming"); account.identities[1].put("password", "alias")
                assertEquals(before.account.toString(), account.account.toString())
                assertEquals(before.identities.map { it.toString() }, account.identities.map { it.toString() })
                val originalJson = original.toJSON(); val changedJson = changed.toJSON()
                // Compare all envelope fields except secret, not only a selected metadata subset.
                originalJson.put("secret", "redacted"); changedJson.put("secret", "redacted")
                assertEquals(originalJson.toString(), changedJson.toString())
                assertEquals("incoming", original.getEmailAccount().account.getString("password"))
            } finally { changed.destroy() }
        } finally { original.destroy() }
    }
    @Test fun blankPreservesAndInvalidCountOrOversizeIsRejectedWithoutMutation() {
        val original = entry()
        try {
            val initial = EmailPasswordInput.fromEntry(original)
            val unchanged = initial.build(original)
            try { assertEquals(original.toJSON().toString(), unchanged.toJSON().toString()) }
            finally { unchanged.destroy() }
            for (invalid in listOf(initial.copy(outgoing = listOf("wrong")),
                initial.copy(incoming = "x".repeat(EmailMaterial.MAX_CREDENTIAL_CHARS + 1)),
                initial.copy(outgoing = listOf("", "x".repeat(EmailMaterial.MAX_CREDENTIAL_CHARS + 1))))) {
                var rejected = false
                try { invalid.build(original).destroy() } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue(rejected)
            }
            val entered = initial.copy(incoming = "secret", outgoing = listOf("one", "two"))
            assertEquals("EmailPasswordInput(redacted)", entered.toString())
            assertEquals(initial, entered.withoutSecrets())
            assertEquals("alias", original.getEmailAccount().identities[1].getString("password"))
        } finally { original.destroy() }
    }
}
