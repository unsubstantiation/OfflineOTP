package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailFieldInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EmailEditInputTest {
    private fun entry(): Entry {
        val seed = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
            outgoingHost = "smtp.example.com", incomingPassword = " secret ", outgoingPassword = "token").build()
        try {
            val email = seed.getEmailAccount()
            email.account.put("custom", JSONObject().put("number", 42)).put("pop", false).put("starttls", false)
            val extra = JSONObject(email.identity.toString()).put("email", "alias@example.com").put("password", "second token").put("auth_type", 99)
            return Entry.email(email.account, email.identity, listOf(email.identity, extra), arrayListOf("work"))
        } finally { seed.destroy() }
    }
    @Test fun losslessRoundTripAndEveryIdentityCredentialsAreEditable() {
        val original = entry()
        try {
            val input = EmailEditInput.fromEntry(original)
            val roundTrip = input.build(original)
            try { assertEquals(original.getSecretEncoded(), roundTrip.getSecretEncoded()) } finally { roundTrip.destroy() }
            val changed = input.copy(issuer = "Mail", label = "Personal", hiddenLabel = "private", tags = "one\ntwo",
                identities = input.identities.map { fields -> fields.map { if (it.name == "password") it.copy(value = "new ") else it } }).build(original)
            try {
                val email = changed.getEmailAccount()
                assertEquals(2, email.identities.size)
                email.identities.forEach { assertEquals("new ", it.getString("password")) }
                assertEquals(99, email.identities[1].getInt("auth_type"))
                assertEquals(42, email.account.getJSONObject("custom").getInt("number"))
                assertEquals(" secret ", email.account.getString("password"))
                assertEquals("private", changed.getHiddenLabel())
                assertEquals(listOf("one", "two"), changed.getTags())
                assertEquals("token", original.getEmailAccount().identity.getString("password"))
            } finally { changed.destroy() }
        } finally { original.destroy() }
    }
    @Test fun invalidTypedValuesAndPortsNeverMutateOriginal() {
        val original = entry()
        try {
            val input = EmailEditInput.fromEntry(original)
            val before = original.getSecretEncoded()
            for ((name, value) in listOf("port" to "0", "port" to "\"993\"", "custom" to "[]", "pop" to "yes")) {
                var rejected = false
                try { input.copy(account = input.account.map { if (it.name == name) it.copy(value = value) else it }).build(original).destroy() }
                catch (_: Exception) { rejected = true }
                assertTrue(rejected); assertEquals(before, original.getSecretEncoded())
            }
            assertFalse(input.toString().contains("secret"))
            assertFalse(input.account.toString().contains("secret"))
        } finally { original.destroy() }
    }
    @Test fun selectorsKeepLegacyFlagsConsistent() {
        val original = entry()
        try {
            val fields = EmailEditInput.fromEntry(original).account
            val pop = EmailFieldInput.updated(fields, fields.indexOfFirst { it.name == "protocol" }, "1")
            assertEquals("true", pop.first { it.name == "pop" }.value)
            val tls = EmailFieldInput.updated(pop, pop.indexOfFirst { it.name == "encryption" }, "1")
            assertEquals("true", tls.first { it.name == "starttls" }.value)
        } finally { original.destroy() }
    }
}
