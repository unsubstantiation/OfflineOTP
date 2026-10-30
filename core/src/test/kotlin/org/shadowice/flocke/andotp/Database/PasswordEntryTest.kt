package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.BrowserPasswordCsv
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupCodec
import org.shadowice.flocke.andotp.Utilities.PasswordImport
import org.shadowice.flocke.andotp.Utilities.PasswordMaterial
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.View.EntryListPresentation

class PasswordEntryTest {
    private val csv = "\uFEFFTitle,URL,Username,Password,Notes,OTPAuth\r\nSite,https://site.test,alice,\"p,\"\"q\"\"\",note,otpauth://example\r\n".toByteArray()

    @Test fun individualAndOriginalBytesSurviveEncryptedBackups() {
        val individuals = PasswordImport.prepare(csv, "passwords.csv", PasswordImport.Mode.INDIVIDUAL)
        val archive = PasswordImport.prepare(csv, "passwords.csv", PasswordImport.Mode.ARCHIVE).single()
        val entries = individuals + archive
        val json = VaultJson.entriesToString(entries)
        val encrypted = EncryptedBackupCodec.seal("backup-password", json)
        val restored = VaultJson.stringToEntries(EncryptedBackupCodec.open("backup-password", encrypted))
        try {
            val fields = restored[0].getPasswordFields()
            assertEquals("alice", fields.value("username"))
            assertEquals("p,\"q\"", fields.value("password"))
            assertEquals("note", fields.value("notes"))
            assertEquals("otpauth://example", fields.value("otpauth"))
            assertTrue(csv.contentEquals(restored[1].exportPasswordArchive()))
            for (entry in restored) {
                assertFalse(entry.areDetailsVisible())
                assertFalse(entry.updateOTP(false))
                try { entry.toUriString(); throw AssertionError("password exported as OTP") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { entries.forEach { it.destroy() }; restored.forEach { it.destroy() } }
    }

    @Test fun largeBinaryArchiveRemainsByteIdentical() {
        val data = ByteArray(256 * 1024) { (it * 31).toByte() }
        val entry = Entry.passwordArchive("original.csv", data)
        val restored = VaultJson.stringToEntries(VaultJson.entriesToString(listOf(entry))).single()
        try {
            assertTrue(data.contentEquals(restored.exportPasswordArchive()))
            assertFalse(restored.isTimeBased())
        } finally { entry.destroy(); restored.destroy(); data.fill(0) }
    }

    @Test fun malformedPayloadAndIncompleteCsvFailClosed() {
        try { PasswordMaterial.decode(byteArrayOf(0, 1, 2)); throw AssertionError("accepted") }
        catch (_: IllegalArgumentException) { }
        try {
            PasswordImport.prepare("name,url,username,password\na,b,c,d\nbroken".toByteArray(), "x.csv", PasswordImport.Mode.INDIVIDUAL)
            throw AssertionError("partial import")
        } catch (_: IllegalArgumentException) { }
        assertTrue(BrowserPasswordCsv.parse(csv) is BrowserPasswordCsv.Result.Success)
    }

    @Test fun repeatedLargeHeadersCannotExpandAnImportWithoutBound() {
        val header = "name,url,username,password," + "h".repeat(60_000) + "\n"
        val csv = (header + "site,url,user,pass,x\n".repeat(150)).toByteArray()
        try {
            PasswordImport.prepare(csv, "x.csv", PasswordImport.Mode.INDIVIDUAL)
            throw AssertionError("expanded payload was not bounded")
        } catch (_: IllegalArgumentException) { }
        finally { csv.fill(0) }
    }

    @Test fun tabsFilterSearchAndRejectStaleResults() {
        val list = EntryList()
        val otp = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Site", "alice", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val note = Entry(Entry.OTPType.NOTE, "secret", "", "note", arrayListOf())
        val key = Entry.sshKey("public", "", "", "key", arrayListOf())
        val password = PasswordImport.prepare(csv, "x.csv", PasswordImport.Mode.INDIVIDUAL).single()
        val archive = PasswordImport.prepare(csv, "x.csv", PasswordImport.Mode.ARCHIVE).single()
        val entries = listOf(otp, note, key, password, archive)
        try {
            entries.forEach { list.addEntry(it) }
            val view = EntryListPresentation(list)
            val cases = listOf(EntryListPresentation.Category.OTP to 1, EntryListPresentation.Category.NOTES to 1,
                EntryListPresentation.Category.SSH_KEYS to 1, EntryListPresentation.Category.PASSWORDS to 2)
            for ((category, count) in cases) {
                view.setCategory(category); view.showReorderedEntries(); assertEquals(count, view.size())
                assertTrue(view.canDrag())
            }
            view.setSearch("alice", listOf(Constants.SearchIncludes.LABEL))
            val stale = view.search("alice", listOf(Constants.SearchIncludes.LABEL))
            view.setCategory(EntryListPresentation.Category.OTP)
            assertFalse(view.publishSearch(stale))
            assertTrue(view.publishSearch(view.search("alice", listOf(Constants.SearchIncludes.LABEL))))
            assertEquals(1, view.size())
            assertEquals(Entry.OTPType.TOTP, view.get(0).getType())
        } finally { entries.forEach { it.destroy() } }
    }
}
