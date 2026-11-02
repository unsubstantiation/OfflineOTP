package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.EmailMaterial

/**
 * Desktop email-account support: the manual form, the connection-details rows and the FairEmail
 * backup mapping must all work without a display, and must never leak a password that the user did
 * not ask to reveal.
 */
class EmailAccountsTest {

    private fun sample(): Entry = EmailAccounts.create(
        name = "Work", address = "person@example.org", protocol = 0,
        incomingHost = "imap.example.org", incomingPort = 993, incomingEncryption = 0,
        incomingAuth = 1, incomingUser = "person@example.org", incomingPassword = "incoming secret",
        outgoingHost = "smtp.example.org", outgoingPort = 465, outgoingEncryption = 0,
        outgoingAuth = 1, outgoingUser = "person@example.org", outgoingPassword = "outgoing secret",
        tags = listOf("team")
    )

    @Test
    fun manualAccountBecomesAnEmailEntryWithIssuerAndLabel() {
        val entry = sample()
        try {
            assertTrue(entry.isEmail())
            assertEquals(Entry.OTPType.EMAIL, entry.getType())
            // Android parity: the address is the issuer, the account name the label.
            assertEquals("person@example.org", entry.getIssuer())
            assertEquals("Work", entry.getLabel())
            assertEquals(listOf("team"), entry.getTags())
        } finally { entry.destroy() }
    }

    @Test
    fun detailsMaskPasswordsUntilTheyAreExplicitlyRevealed() {
        val entry = sample()
        try {
            val masked = EmailAccounts.details(entry, false)!!
            val revealed = EmailAccounts.details(entry, true)!!
            assertEquals(masked.size, revealed.size)
            assertEquals(revealed.map { it.label }, masked.map { it.label })

            fun value(fields: List<EmailMaterial.Detail>, label: String) =
                fields.single { it.label == label }.value

            assertEquals("imap.example.org", value(masked, "Incoming (IMAP) server"))
            assertEquals("993", value(masked, "Incoming (IMAP) port"))
            assertEquals("SSL", value(masked, "Incoming (IMAP) encryption"))
            assertEquals("person@example.org", value(masked, "Outgoing (SMTP) user name"))
            assertEquals(EmailMaterial.SECRET_MASK, value(masked, "Incoming (IMAP) password"))
            assertEquals(EmailMaterial.SECRET_MASK, value(masked, "Outgoing (SMTP) password"))
            assertFalse("no detail row may carry a plaintext password while masked",
                masked.any { it.value.contains("secret") })

            assertEquals("incoming secret", value(revealed, "Incoming (IMAP) password"))
            assertEquals("outgoing secret", value(revealed, "Outgoing (SMTP) password"))
            assertTrue(masked.single { it.label == "Incoming (IMAP) password" }.secret)
            assertFalse(masked.single { it.label == "Incoming (IMAP) server" }.secret)
        } finally { entry.destroy() }
    }

    @Test
    fun everyStoredIdentityGetsItsOwnOutgoingRowGroup() {
        val source = EmailMaterial.create(
            "Work", "person@example.org", "imap.example.org", 993, 0, 0,
            "person@example.org", "incoming secret", 1,
            "smtp.example.org", 465, 0, "person@example.org", "outgoing secret", 1)
        val alias = org.json.JSONObject(source.identity.toString())
            .put("id", 2).put("email", "alias@example.org").put("primary", false)
            .put("host", "smtp2.example.org").put("password", "alias secret")
        val entry = Entry.email(source.account, source.identity, listOf(source.identity, alias))
        try {
            val labels = EmailAccounts.details(entry, false)!!.map { it.label }
            assertTrue(labels.contains("Outgoing (SMTP) server"))
            assertTrue(labels.contains("Outgoing (SMTP) #2 server"))
            val aliasServer = EmailAccounts.details(entry, false)!!
                .single { it.label == "Outgoing (SMTP) #2 server" }
            assertEquals("smtp2.example.org", aliasServer.value)
        } finally { entry.destroy() }
    }

    @Test
    fun aNonEmailEntryHasNoEmailDetails() {
        val note = Entry(Entry.OTPType.NOTE, "private note", "Notes", "Recovery", arrayListOf())
        try {
            assertNull(EmailAccounts.details(note, false))
            assertNull(EmailAccounts.details(note, true))
        } finally { note.destroy() }
    }

    @Test
    fun manualAccountRejectsFieldsFairEmailWouldRefuse() {
        fun build(protocol: Int = 0, port: Int = 993, encryption: Int = 0,
                  address: String = "person@example.org", host: String = "imap.example.org"): Entry =
            EmailAccounts.create("Work", address, protocol, host, port, encryption, 1,
                "person@example.org", "secret", "smtp.example.org", 465, 0, 1,
                "person@example.org", "secret")

        // Ports, encryptions and the protocol are bounded, and the identity needs an address.
        assertThrows(IllegalArgumentException::class.java) { build(port = 0).destroy() }
        assertThrows(IllegalArgumentException::class.java) { build(port = 65536).destroy() }
        assertThrows(IllegalArgumentException::class.java) { build(encryption = 3).destroy() }
        assertThrows(IllegalArgumentException::class.java) { build(protocol = 2).destroy() }
        assertThrows(IllegalArgumentException::class.java) { build(host = " ").destroy() }
        assertThrows(IllegalArgumentException::class.java) { build(address = "person").destroy() }
    }

    @Test
    fun plainBackupRoundTripsThroughImportAndExport() {
        val source = sample()
        try {
            val backup = EmailAccounts.exportBackup(listOf(source))
            try {
                val imported = EmailAccounts.importEntries(backup, null)
                assertEquals(1, imported.size)
                val restored = imported.single()
                try {
                    assertTrue(restored.isEmail())
                    assertEquals("person@example.org", restored.getIssuer())
                    assertEquals("Work", restored.getLabel())
                    val account = restored.getEmailAccount()
                    assertEquals("incoming secret", account.account.getString("password"))
                    assertEquals("outgoing secret", account.identity.getString("password"))
                } finally { imported.forEach { it.destroy() } }
            } finally { backup.fill(0) }
        } finally { source.destroy() }
    }

    @Test
    fun anInvalidBackupIsRejectedWithoutLeavingEntriesBehind() {
        // A short non-FairEmail file and a password-protected-looking header both fail closed.
        assertThrows(IllegalArgumentException::class.java) {
            EmailAccounts.importEntries(byteArrayOf(1, 2, 3, 4), null).forEach { it.destroy() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmailAccounts.importEntries("___FairEmail___".toByteArray() + ByteArray(64), null)
                .forEach { it.destroy() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmailAccounts.importEntries(ByteArray(EmailAccounts.MAX_IMPORT_BYTES + 1), null)
                .forEach { it.destroy() }
        }
    }

    @Test
    fun exportRefusesAnEmptyOrMixedSelection() {
        val source = sample()
        val note = Entry(Entry.OTPType.NOTE, "private note", "Notes", "Recovery", arrayListOf())
        try {
            assertThrows(IllegalArgumentException::class.java) { EmailAccounts.exportBackup(emptyList()) }
            assertThrows(IllegalArgumentException::class.java) {
                EmailAccounts.exportBackup(listOf(source, note))
            }
        } finally { source.destroy(); note.destroy() }
    }

    @Test
    fun labelsStayReadableAndNeverDangleAnIssuer() {
        val entry = sample()
        try {
            assertEquals("Work (person@example.org)", EmailAccounts.displayLabel(entry))
        } finally { entry.destroy() }
    }

    @Test
    fun onlyEmailEntriesAreListedForExport() {
        val source = sample()
        val note = Entry(Entry.OTPType.NOTE, "private note", "Notes", "Recovery", arrayListOf())
        try {
            val listed = EmailAccounts.entries(listOf(note, source))
            assertEquals(1, listed.size)
            assertTrue(listed.single() === source)
        } finally { source.destroy(); note.destroy() }
    }
}
