package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordCsvExportTest {

    private val chrome = ("name,url,username,password,note\n"
            + "Site,https://site.test,alice,s3cret,first note\n"
            + "Other,https://other.test,bob,other pw,\n").toByteArray()

    private val safari = ("Title,URL,Username,Password,Notes,OTPAuth\n"
            + "Site,https://site.test,alice,\"p,\"\"q\"\"\",note,otpauth://example\n").toByteArray()

    private fun imported(bytes: ByteArray): List<Entry> =
        PasswordImport.prepare(bytes, "passwords.csv", PasswordImport.Mode.INDIVIDUAL)

    @Test fun importedDialectSurvivesRoundTrip() {
        val entries = imported(chrome)
        try {
            for (entry in entries) {
                val support = PasswordCsvExport.support(entry)
                assertTrue("not exportable: $support", support is PasswordCsvExport.Support.Exportable)
                assertEquals(PasswordCsvExport.Dialect.CHROME, (support as PasswordCsvExport.Support.Exportable).dialect)
            }
            val exported = PasswordCsvExport.exportCsv(entries, PasswordCsvExport.Dialect.CHROME)
            val reparsed = BrowserPasswordCsv.parse(exported)
            assertTrue("export did not parse: $reparsed", reparsed is BrowserPasswordCsv.Result.Success)
            val success = reparsed as BrowserPasswordCsv.Result.Success
            // The original header is kept, every row comes back in order, and the values match.
            assertEquals(entries[0].getPasswordFields().columns(), success.columns)
            assertEquals(2, success.rows.size)
            val back = imported(exported)
            try {
                assertEquals("alice", back[0].getPasswordFields().value("username"))
                assertEquals("s3cret", back[0].getPasswordFields().value("password"))
                assertEquals("first note", back[0].getPasswordFields().value("note"))
                assertEquals("https://site.test", back[0].getPasswordFields().value("url"))
                assertEquals("bob", back[1].getPasswordFields().value("username"))
            } finally { back.forEach { it.destroy() } }
        } finally { entries.forEach { it.destroy() } }
    }

    @Test fun safariColumnsKeepTheirNotesAndOtpAuth() {
        val entries = imported(safari)
        try {
            val support = PasswordCsvExport.support(entries.single()) as PasswordCsvExport.Support.Exportable
            assertEquals(PasswordCsvExport.Dialect.SAFARI, support.dialect)
            val exported = PasswordCsvExport.exportCsv(entries, support.dialect)
            val text = String(exported, Charsets.UTF_8)
            assertTrue(text, text.startsWith("Title,URL,Username,Password,Notes,OTPAuth\n"))
            assertTrue(text, text.contains("\"p,\"\"q\"\"\""))
            assertTrue(text, text.contains("otpauth://example"))
        } finally { entries.forEach { it.destroy() } }
    }

    @Test fun mixedHeadersUseTheCanonicalHeaderForTheChosenDialect() {
        val first = imported(chrome)
        val second = imported(safari)
        try {
            val exported = PasswordCsvExport.exportCsv(listOf(first[0], second[0]), PasswordCsvExport.Dialect.FIREFOX)
            val success = BrowserPasswordCsv.parse(exported) as BrowserPasswordCsv.Result.Success
            assertEquals(PasswordCsvExport.canonicalHeader(PasswordCsvExport.Dialect.FIREFOX), success.columns)
            // Chrome's row lands under the canonical Firefox header; nothing invents a GUID.
            assertEquals("https://site.test", success.rows[0].valueAt(0))
            assertEquals("alice", success.rows[0].valueAt(1))
            assertEquals("s3cret", success.rows[0].valueAt(2))
            assertEquals("", success.rows[0].valueAt(5))
        } finally { first.forEach { it.destroy() }; second.forEach { it.destroy() } }
    }

    @Test fun foreignAndNonPasswordEntriesAreRefused() {
        val session = Entry.passwordFields(
            "alice@host", "host",
            listOf("url" to "ssh://host:22", "username" to "alice", "password" to "pw", "linscp_session_json" to "{}"))
        val archive = Entry.passwordArchive("browser.csv", chrome)
        val note = EntryDraft.create(
            Entry.OTPType.NOTE, "", "title", "some text", "6", "30", TokenCalculator.HashAlgorithm.SHA1)
        try {
            assertTrue(PasswordCsvExport.support(session) is PasswordCsvExport.Support.Foreign)
            assertTrue(PasswordCsvExport.support(archive) is PasswordCsvExport.Support.NotPassword)
            assertTrue(PasswordCsvExport.support(note) is PasswordCsvExport.Support.NotPassword)
            try {
                PasswordCsvExport.exportCsv(listOf(session), PasswordCsvExport.Dialect.FIREFOX)
                throw AssertionError("exported a foreign column set")
            } catch (_: IllegalArgumentException) { }
        } finally { session.destroy(); archive.destroy(); note.destroy() }
    }

    @Test fun editingChangesOnlyTheRequestedFields() {
        val entry = imported(chrome)[0]
        val edited = PasswordCsvExport.withEditedFields(entry, username = "carol", password = "new pw")
        try {
            val fields = edited.getPasswordFields()
            assertEquals("carol", fields.value("username"))
            assertEquals("new pw", fields.value("password"))
            assertEquals("https://site.test", fields.value("url"))
            assertEquals("first note", fields.value("note"))
            // The card the user sees follows the edited username.
            assertEquals("carol", edited.getLabel())
            assertEquals("Site", edited.getIssuer())
            assertEquals(entry.getThumbnailName(), edited.getThumbnailName())
            assertEquals(entry.getTags().toList(), edited.getTags().toList())
            assertTrue(entry.getTags().isEmpty() || edited.getTags() !== entry.getTags())
        } finally { entry.destroy(); edited.destroy() }
    }

    @Test fun editingRejectsAnEmptyPasswordAndLeavesTheEntryUntouched() {
        val entry = imported(chrome)[0]
        try {
            val before = entry.getPasswordFields()
            try {
                PasswordCsvExport.withEditedFields(entry, password = "")
                throw AssertionError("accepted an empty password")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before.value("password"), entry.getPasswordFields().value("password"))
        } finally { entry.destroy() }
    }

    @Test fun editedEntriesStillExportAsTheirDialect() {
        val entry = imported(safari)[0]
        val edited = PasswordCsvExport.withEditedFields(entry, url = "https://moved.test")
        try {
            val support = PasswordCsvExport.support(edited) as PasswordCsvExport.Support.Exportable
            assertEquals(PasswordCsvExport.Dialect.SAFARI, support.dialect)
            val text = String(PasswordCsvExport.exportCsv(listOf(edited), support.dialect), Charsets.UTF_8)
            assertTrue(text, text.contains("https://moved.test"))
            assertFalse(text, text.contains("https://site.test"))
            assertTrue(text, text.contains("otpauth://example"))
        } finally { entry.destroy(); edited.destroy() }
    }
}
