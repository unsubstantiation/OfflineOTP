package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailSettingsInputTest {
    private fun entry(): Entry {
        val seed = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
            outgoingHost = "smtp.example.com", incomingPassword = "incoming-token", outgoingPassword = "smtp-token").build()
        try {
            val account = seed.getEmailAccount()
            account.account.put("provider_extra", "keep").put("pop", false).put("starttls", false)
            val extra = JSONObject(account.identity.toString()).put("email", "alias@example.com").put("password", "alias-token")
            return Entry.email(account.account, account.identity, listOf(account.identity, extra), arrayListOf("work")).also {
                it.setLabel("custom"); it.setIssuer("custom issuer", false); it.setHiddenLabel("protected")
                it.setThumbnailName("Google"); it.setUsedFrequency(7); it.setLastUsed(123L)
            }
        } finally { seed.destroy() }
    }
    @Test fun editsConnectionsButPreservesAllCredentialsIdentitiesAndEnvelopeFields() {
        val original = entry()
        try {
            val initial = EmailSettingsInput.fromEntry(original)
            val input = initial.copy(protocol = 1, incoming = initial.incoming.copy(host = "pop.example.com", port = "995", encryption = 1),
                outgoing = initial.outgoing.mapIndexed { i, c -> if (i == 1) c.copy(host = "alias-smtp.example.com", port = "587", username = "alias-user") else c })
            val edited = input.build(original)
            try {
                val account = edited.getEmailAccount(); val old = original.getEmailAccount()
                assertEquals("pop.example.com", account.account.getString("host"))
                assertEquals(995, account.account.getInt("port"))
                assertTrue(account.account.optBoolean("pop")); assertTrue(account.account.optBoolean("starttls"))
                assertEquals("incoming-token", account.account.getString("password"))
                assertEquals("keep", account.account.getString("provider_extra"))
                assertEquals(old.account.getInt("auth_type"), account.account.getInt("auth_type"))
                assertEquals(old.identities[0].toString(), account.identities[0].toString())
                assertEquals("alias-token", account.identities[1].getString("password"))
                assertEquals("alias@example.com", account.identities[1].getString("email"))
                assertEquals("alias-user", account.identities[1].getString("user"))
                assertEquals("imap.example.com", old.account.getString("host"))
                val a = original.toJSON().put("secret", "redacted"); val b = edited.toJSON().put("secret", "redacted")
                assertEquals(a.toString(), b.toString())
            } finally { edited.destroy() }
        } finally { original.destroy() }
    }
    @Test fun invalidInputIsRejectedAndDiagnosticsDoNotExposeMetadata() {
        val original = entry()
        try {
            val input = EmailSettingsInput.fromEntry(original)
            val invalid = listOf(input.copy(protocol = 2), input.copy(outgoing = emptyList()),
                input.copy(incoming = input.incoming.copy(host = " ")),
                input.copy(incoming = input.incoming.copy(port = "65536")),
                input.copy(incoming = input.incoming.copy(port = "0")),
                input.copy(incoming = input.incoming.copy(port = "invalid")),
                input.copy(incoming = input.incoming.copy(encryption = 3)),
                input.copy(incoming = input.incoming.copy(username = "x".repeat(16385))))
            invalid.forEach { value ->
                var rejected = false
                try { value.build(original).destroy() } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue(rejected)
            }
            assertEquals("EmailSettingsInput(redacted)", input.toString())
            assertEquals("EmailConnectionInput(redacted)", input.incoming.toString())
            assertEquals("incoming-token", original.getEmailAccount().account.getString("password"))
        } finally { original.destroy() }
    }
}
