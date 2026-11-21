package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec

class DesktopThunderbirdAndActionsTest {

    private fun testKey() = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Test
    fun thunderbirdImportAndSelectiveExportRoundTripEmailAndPasswordCredentials() {
        val dir = Files.createTempDirectory("offlineotp-tb-test").toFile()
        try {
            val csv = """
                "url","username","password","httpRealm","formActionOrigin","guid","timeCreated","timeLastUsed","timePasswordChanged"
                "imap://imap.example.org","alice@example.org","imap-secret","imap://imap.example.org","","{1}","1","1","1"
                "smtp://smtp.example.org","alice@example.org","smtp-secret","smtp://smtp.example.org","","{2}","1","1","1"
            """.trimIndent()
            val source = File(dir, "logins.csv").apply { writeText(csv, StandardCharsets.UTF_8) }
            val state = DesktopVaultState(testKey())
            val direct = Executor { it.run() }
            var changes = 0
            val imported = ArrayList<Entry>()
            val importer = DesktopThunderbirdImport(
                state = state,
                worker = direct,
                dispatch = { it.run() },
                changed = { changes++ },
                install = { entries ->
                    entries.forEach { state.add(it); imported.add(it) }
                    entries.size
                }
            )
            importer.openAndRead(source) { true }
            val req = importer.pending
            assertNotNull(req)
            assertEquals(1, req!!.count)
            assertTrue(importer.confirm(req) { true })
            assertEquals(1, importer.importedCount)
            assertEquals(1, state.snapshot().size)
            assertTrue(state.snapshot().single().isEmail())

            val pwEntry = PasswordImport.prepare(
                "Title,URL,Username,Password,Notes\nMail,https://mail.example.org,bob@example.org,pw-secret,\n".toByteArray(),
                "passwords.csv",
                PasswordImport.Mode.INDIVIDUAL
            ).single()
            val nonUriPw = PasswordImport.prepare(
                "Title,URL,Username,Password,Notes\nLocalNote,,local-user,local-secret,\n".toByteArray(),
                "passwords.csv",
                PasswordImport.Mode.INDIVIDUAL
            ).single()
            assertTrue(state.add(pwEntry))
            assertTrue(state.add(nonUriPw))

            var writtenBytes: ByteArray? = null
            val exporter = DesktopThunderbirdExport(
                state = state,
                worker = direct,
                dispatch = { it.run() },
                changed = { changes++ },
                write = { _, bytes -> writtenBytes = bytes.copyOf() }
            )
            assertTrue(exporter.open { true })
            val expReq = exporter.pending!!
            assertEquals(2, expReq.candidates.size)
            assertEquals(DesktopThunderbirdExport.Status.SELECT, expReq.status)

            val target = File(dir, "exported.csv")
            assertTrue(exporter.choose(expReq, setOf(0), target) { true })
            assertEquals(DesktopThunderbirdExport.Status.CONFIRM, expReq.status)
            assertTrue(exporter.confirm(expReq) { true })
            assertEquals(DesktopThunderbirdExport.Status.SAVED, expReq.status)
            val exportedText = String(writtenBytes!!, StandardCharsets.UTF_8)
            assertTrue(exportedText.contains("alice@example.org"))
            assertFalse(exportedText.contains("bob@example.org"))

            state.lock()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun entryActionsEnforceLiveRevealAndRollBackFailedMutations() {
        val state = DesktopVaultState(testKey())
        val hotp = Entry(
            Entry.OTPType.HOTP,
            "JBSWY3DPEHPK3PXP",
            7L,
            6,
            "GitHub",
            "alice",
            TokenCalculator.HashAlgorithm.SHA256,
            arrayListOf("work")
        )
        val note = Entry(Entry.OTPType.NOTE, "secret note", "Docs", "readme", arrayListOf())
        assertTrue(state.add(hotp))
        assertTrue(state.add(note))

        var shouldPersist = true
        val actions = DesktopEntryActions(
            state = state,
            persist = { shouldPersist },
            changed = {}
        )

        // Concealed entry refuses QR disclosure; revealed OTP entry builds QR matrix, while Note never offers QR.
        assertFalse(actions.openQr(hotp.getListId(), revealed = false) { true })
        assertTrue(actions.openQr(hotp.getListId(), revealed = true) { true })
        assertNotNull(actions.pending!!.qrMatrix)
        assertFalse(actions.openQr(note.getListId(), revealed = true) { true })

        // Tag update & failed-persistence rollback.
        assertTrue(actions.openTags(hotp.getListId()) { true })
        assertTrue(actions.submitTags(actions.pending!!, linkedMapOf("work" to true, "ops" to true)) { true })
        assertEquals(listOf("work", "ops"), hotp.getTags())

        shouldPersist = false
        assertTrue(actions.openCounter(hotp.getListId(), revealed = true) { true })
        assertFalse(actions.submitCounter(actions.pending!!, "42", revealed = true) { true })
        assertEquals(7L, hotp.getCounter())

        shouldPersist = true
        assertTrue(actions.openCounter(hotp.getListId(), revealed = true) { true })
        assertTrue(actions.submitCounter(actions.pending!!, "42", revealed = true) { true })
        assertEquals(42L, hotp.getCounter())

        assertTrue(actions.openThumbnail(hotp.getListId()) { true })
        val validThumb = ThumbnailCatalog.NAMES.first { it != ThumbnailCatalog.DEFAULT_NAME }
        assertTrue(actions.submitThumbnail(actions.pending!!, validThumb) { true })
        assertEquals(validThumb, hotp.getThumbnailName())

        // Password and password-archive entries edit tags, artwork, and extra columns via DesktopEntryEditing,
        // while exports remain vault-wide via DesktopPasswordExport.open and DesktopArchiveExport.open.
        val browserPw = PasswordImport.prepare(
            "name,url,username,password,note\nGitHub,https://github.com,alice,pw-999,team login\n".toByteArray(),
            "chrome.csv",
            PasswordImport.Mode.INDIVIDUAL
        ).single()
        val archiveEntry = PasswordImport.prepare(
            "raw-archive-payload".toByteArray(),
            "vault.kdbx",
            PasswordImport.Mode.ARCHIVE
        ).single()
        assertTrue(state.add(browserPw))
        assertTrue(state.add(archiveEntry))

        val editing = DesktopEntryEditing(state, state::replace)
        editing.open(browserPw.getListId())
        val pwReq = editing.pending!!
        val pwInit = editing.initialPasswordInput(pwReq)!!
        val updatedExtras = pwInit.extraFields!!.map {
            if (it.name == "note") it.copy(value = "updated note") else it
        }
        assertTrue(
            editing.submitPassword(
                pwReq,
                pwInit.copy(tags = "dev\nops", thumbnail = validThumb, extraFields = updatedExtras),
                revealed = true
            ) { true }
        )
        val updatedPw = state.snapshot().first { it.isPassword() }
        assertEquals("updated note", updatedPw.getPasswordFields().value("note"))
        assertEquals(listOf("dev", "ops"), updatedPw.getTags())
        assertEquals(validThumb, updatedPw.getThumbnailName())

        editing.open(archiveEntry.getListId())
        val archReq = editing.pending!!
        val archInit = editing.initialPasswordInput(archReq)!!
        assertTrue(archInit.archiveMode)
        assertTrue(
            editing.submitPassword(
                archReq,
                archInit.copy(issuer = "Team Archive", label = "vault.kdbx", tags = "backup", thumbnail = validThumb),
                revealed = true
            ) { true }
        )
        val updatedArch = state.snapshot().first { it.isPasswordArchive() }
        assertEquals("Team Archive", updatedArch.getIssuer())
        assertEquals(listOf("backup"), updatedArch.getTags())

        val direct = Executor { it.run() }
        var groupCsv: ByteArray? = null
        val pwExporter = DesktopPasswordExport(state, direct, { it.run() }, {}, write = { _, b -> groupCsv = b.copyOf() })
        assertTrue(pwExporter.open { true })
        assertTrue(pwExporter.choose(pwExporter.pending!!, 0, File("pw.csv")) { true })
        assertTrue(pwExporter.confirm(pwExporter.pending!!) { true })
        assertTrue(String(groupCsv!!, StandardCharsets.UTF_8).contains("pw-999"))
        pwExporter.close()

        var exportedArchive: ByteArray? = null
        val archiveExporter = DesktopArchiveExport(state, direct, { it.run() }, {}, write = { _, b -> exportedArchive = b.copyOf() })
        assertTrue(archiveExporter.open { true })
        assertTrue(archiveExporter.choose(archiveExporter.pending!!, 0, File("archive.bin")) { true })
        assertTrue(archiveExporter.confirm(archiveExporter.pending!!) { true })
        assertEquals("raw-archive-payload", String(exportedArchive!!, StandardCharsets.UTF_8))
        archiveExporter.close()

        editing.close()
        actions.close()
        assertNull(actions.pending)
        state.lock()
    }
}
