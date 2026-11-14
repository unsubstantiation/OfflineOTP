package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailEntryInputTest {
    private fun input() = EmailEntryInput(address = "alice@example.com", incomingHost = "imap.example.com",
        outgoingHost = "smtp.example.com", incomingUser = "alice", outgoingUser = "smtp-alice",
        incomingPassword = " incoming secret ", outgoingPassword = " outgoing secret ", tags = "work\n work \nmail")

    @Test fun createsIndependentAccountAndIdentityAndRoundTripsFairEmail() {
        val entry = input().build()
        try {
            assertTrue(entry.isEmail())
            assertEquals(listOf("work", "mail"), entry.getTags())
            val account = entry.getEmailAccount()
            assertEquals("alice@example.com", account.name)
            assertEquals(" incoming secret ", account.account.getString("password"))
            assertEquals(" outgoing secret ", account.identity.getString("password"))
            assertEquals(993, account.account.getInt("port"))
            assertEquals(465, account.identity.getInt("port"))
            val bytes = EmailMaterial.exportBackup(listOf(account))
            try {
                val imported = EmailMaterial.importBackup(bytes, null).single()
                assertEquals(account.address, imported.address)
                assertEquals("smtp-alice", imported.identity.getString("user"))
            } finally { bytes.fill(0) }
        } finally { entry.destroy() }
    }

    @Test fun malformedAndOutOfRangeFieldsAreRejected() {
        val valid = input()
        val invalid = listOf(valid.copy(address = "invalid"), valid.copy(incomingHost = " "),
            valid.copy(outgoingHost = ""), valid.copy(protocol = 2), valid.copy(incomingPort = "0"),
            valid.copy(outgoingPort = "65536"), valid.copy(incomingPort = "not a number"),
            valid.copy(outgoingAuth = "2147483648"), valid.copy(incomingAuth = "-1"),
            valid.copy(incomingEncryption = -1), valid.copy(outgoingEncryption = 3),
            valid.copy(incomingPassword = "x".repeat(EmailMaterial.MAX_CREDENTIAL_CHARS + 1)),
            valid.copy(outgoingUser = "x".repeat(EmailMaterial.MAX_CREDENTIAL_CHARS + 1)),
            valid.copy(tags = (0..32).joinToString("\n")), valid.copy(tags = "x".repeat(129)),
            valid.copy(name = "x".repeat(1025)))
        invalid.forEach { value ->
            var rejected = false
            try { value.build().destroy() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test fun secretsAreRedactedAndRemovedWithoutChangingMetadata() {
        val input = input()
        assertFalse(input.toString().contains("secret"))
        val cleared = input.withoutSecrets()
        assertEquals("", cleared.incomingPassword)
        assertEquals("", cleared.outgoingPassword)
        assertEquals(input.address, cleared.address)
        assertEquals(input.incomingUser, cleared.incomingUser)
        val entry = cleared.copy(protocol = 1, incomingPort = "995", outgoingEncryption = 1, outgoingPort = "587").build()
        try {
            assertEquals(1, entry.getEmailAccount().account.getInt("protocol"))
            assertEquals("", entry.getEmailAccount().identity.getString("password"))
        } finally { entry.destroy() }
    }
}
