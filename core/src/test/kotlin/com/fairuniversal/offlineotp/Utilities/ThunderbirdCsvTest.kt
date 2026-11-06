package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThunderbirdCsvTest {
    private fun parse(text: String) = ThunderbirdCsv.parse(text.toByteArray(Charsets.UTF_8))
    private fun success(text: String) = parse(text) as ThunderbirdCsv.Result.Success
    private fun fails(text: String, error: ThunderbirdCsv.Error) {
        assertEquals(error, (parse(text) as ThunderbirdCsv.Result.Failure).error)
    }

    @Test fun urlAndHostnameVariantsParse() {
        val thunderbird = success("hostname,username,password,httpRealm\n" +
            "imap://mail.example.com,alice@example.com,s3cr3t,Mail\n")
        assertEquals(1, thunderbird.rows.size)
        assertEquals("alice@example.com", thunderbird.rows[0].valueAt(1))

        val firefoxStyle = success("\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
            "\"imap://imap.gmail.com\",\"bob@example.com\",\"secret\",\"\",\"\",\"{guid}\",\"0\",\"0\",\"0\"\n")
        assertEquals(1, firefoxStyle.rows.size)
        assertEquals("bob@example.com", firefoxStyle.rows[0].valueAt(1))
    }

    @Test fun thunderbirdImportProducesEmailEntriesAndRoundTrips() {
        // Two rows with same username (imap + smtp) are grouped into one EMAIL account,
        // exactly how FairEmail stores an account + its SMTP identity.
        val csv = "\"url\",\"username\",\"password\",\"httpRealm\"\n" +
            "\"imap://mail.example.com\",\"alice@example.com\",\"p@ss,word\",\"Mail\"\n" +
            "\"smtp://smtp.example.com\",\"alice@example.com\",\"s3cr3t\",\"Mail\"\n"
        val entries = ThunderbirdImport.prepare(csv.toByteArray(Charsets.UTF_8))
        assertEquals(1, entries.size)
        assertTrue(entries.all { it.isEmail() })
        // Export should produce canonical Thunderbird CSV (incoming + outgoing rows) that re-imports to the same vault
        val exported = ThunderbirdImport.exportCsv(entries)
        val reparsed = ThunderbirdImport.prepare(exported)
        assertEquals(1, reparsed.size)
        assertEquals(entries[0].getEmailAccount().account.getString("host"), reparsed[0].getEmailAccount().account.getString("host"))
        entries.forEach { it.destroy() }
        reparsed.forEach { it.destroy() }
        exported.fill(0)
    }

    @Test fun hostnameAliasBecomesEmailHost() {
        val csv = "hostname,username,password\n" +
            "mailbox://localhost,alice@example.com,s3cret\n"
        val entries = ThunderbirdImport.prepare(csv.toByteArray(Charsets.UTF_8))
        assertEquals(1, entries.size)
        assertTrue(entries[0].isEmail())
        val acct = entries[0].getEmailAccount()
        assertTrue(acct.account.getString("host").isNotEmpty())
        assertEquals("alice@example.com", acct.address)
        entries.forEach { it.destroy() }
    }

    @Test fun fairEmailAndThunderbirdShareTheSameEmailEntry() {
        // A FairEmail account can be written as Thunderbird CSV and read back as the same vault account
        val fairEmailAccount = EmailMaterial.create(
            "Alice", "alice@example.com",
            "mail.example.com", 993, 0, 0, "alice@example.com", "p@ssword", 1,
            "smtp.example.com", 465, 0, "alice@example.com", "s3cret", 1
        )
        val fairEmailBytes = EmailMaterial.exportBackup(listOf(fairEmailAccount))
        val fromFairEmail = EmailMaterial.importBackup(fairEmailBytes)
        assertEquals(1, fromFairEmail.size)
        // Export the same account as Thunderbird CSV
        val tbBytes = EmailMaterial.exportThunderbirdCsv(fromFairEmail)
        assertTrue(tbBytes.isNotEmpty())
        val fromThunderbird = EmailMaterial.importThunderbirdCsv(tbBytes)
        assertEquals(1, fromThunderbird.size)
        assertEquals(fromFairEmail[0].address, fromThunderbird[0].address)
        assertEquals(fromFairEmail[0].account.getString("host"), fromThunderbird[0].account.getString("host"))
        fairEmailBytes.fill(0)
        tbBytes.fill(0)
    }

    @Test fun rejectsMalformed() {
        fails("hostname,username,password\na,b", ThunderbirdCsv.Error.INVALID_CSV)
        fails("hostname,username,password\n", ThunderbirdCsv.Error.INVALID_CSV)
        fails("url,username,password\na,b,c,\"unterminated", ThunderbirdCsv.Error.INVALID_CSV)
        fails("hostname,username,password,PASSWORD\na,b,c,d", ThunderbirdCsv.Error.INVALID_CSV)
    }

    @Test fun boundedInput() {
        fails("hostname,username,password\n" + "a,b,c\n".repeat(ThunderbirdCsv.MAX_ROWS + 1), ThunderbirdCsv.Error.TOO_MANY_ROWS)
        assertEquals(ThunderbirdCsv.Error.TOO_LARGE,
            (ThunderbirdCsv.parse(ByteArray(ThunderbirdCsv.MAX_FILE_BYTES + 1)) as ThunderbirdCsv.Result.Failure).error)
    }
}
