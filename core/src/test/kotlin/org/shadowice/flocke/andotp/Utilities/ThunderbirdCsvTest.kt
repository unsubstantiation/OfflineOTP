package org.shadowice.flocke.andotp.Utilities

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

    @Test fun thunderbirdImportProducesPasswordEntriesAndRoundTrips() {
        val csv = "\"url\",\"username\",\"password\",\"httpRealm\"\n" +
            "\"imap://mail.example.com\",\"alice@example.com\",\"p@ss,word\",\"Mail\"\n" +
            "\"smtp://smtp.example.com\",\"alice@example.com\",\"s3cr3t\",\"Mail\"\n"
        val entries = ThunderbirdImport.prepare(csv.toByteArray(Charsets.UTF_8))
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.isPassword() })
        // Export should produce canonical Thunderbird CSV that can be re-imported
        val exported = ThunderbirdImport.exportCsv(entries)
        val reparsed = ThunderbirdImport.prepare(exported)
        assertEquals(2, reparsed.size)
        entries.forEach { it.destroy() }
        reparsed.forEach { it.destroy() }
        exported.fill(0)
    }

    @Test fun hostnameAliasIsMappedToUrl() {
        val csv = "hostname,username,password\n" +
            "mailbox://localhost,alice,s3cret\n"
        val entries = ThunderbirdImport.prepare(csv.toByteArray(Charsets.UTF_8))
        assertEquals(1, entries.size)
        val fields = entries[0].getPasswordFields()
        assertEquals("mailbox://localhost", fields.value("url"))
        entries.forEach { it.destroy() }
    }

    @Test fun rejectsMalformed() {
        fails("hostname,username,password\na,b", ThunderbirdCsv.Error.INVALID_CSV)
        fails("hostname,username,password\n", ThunderbirdCsv.Error.INVALID_CSV)
        fails("url,username,password\na,b,c,\"unterminated", ThunderbirdCsv.Error.INVALID_CSV)
        fails("hostname,username,PASSWORD\na,b,c", ThunderbirdCsv.Error.INVALID_CSV)
    }

    @Test fun boundedInput() {
        fails("hostname,username,password\n" + "a,b,c\n".repeat(ThunderbirdCsv.MAX_ROWS + 1), ThunderbirdCsv.Error.TOO_MANY_ROWS)
        assertEquals(ThunderbirdCsv.Error.TOO_LARGE,
            (ThunderbirdCsv.parse(ByteArray(ThunderbirdCsv.MAX_FILE_BYTES + 1)) as ThunderbirdCsv.Result.Failure).error)
    }
}
