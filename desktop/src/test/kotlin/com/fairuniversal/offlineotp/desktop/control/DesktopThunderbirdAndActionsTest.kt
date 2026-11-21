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

        // Concealed menu omits Edit, Details, Show QR code, and Set counter, while offering Delete.
        assertTrue(actions.openMenu(hotp.getListId(), revealed = false, canMoveUp = false, canMoveDown = true) { true })
        val concealedMenu = actions.pending!!
        assertFalse(DesktopEntryActions.Action.EDIT in concealedMenu.actions)
        assertFalse(DesktopEntryActions.Action.DETAILS in concealedMenu.actions)
        assertFalse(DesktopEntryActions.Action.SHOW_QR in concealedMenu.actions)
        assertFalse(DesktopEntryActions.Action.SET_COUNTER in concealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.MANAGE_TAGS in concealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.MOVE_DOWN in concealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.DELETE in concealedMenu.actions)

        // Revealed HOTP menu offers Edit, Details, Show QR code, Set counter, and Delete; Note never offers Show QR code.
        assertTrue(actions.openMenu(hotp.getListId(), revealed = true, canMoveUp = false, canMoveDown = false) { true })
        val revealedMenu = actions.pending!!
        assertTrue(DesktopEntryActions.Action.EDIT in revealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.DETAILS in revealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.SHOW_QR in revealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.SET_COUNTER in revealedMenu.actions)
        assertTrue(DesktopEntryActions.Action.DELETE in revealedMenu.actions)

        assertTrue(actions.selectSubSurface(revealedMenu, DesktopEntryActions.Action.DETAILS, revealed = true) { true })
        assertTrue(actions.pending!!.detailsBody.contains("Algorithm: SHA256"))

        assertTrue(actions.openMenu(note.getListId(), revealed = true, canMoveUp = true, canMoveDown = false) { true })
        assertFalse(DesktopEntryActions.Action.SHOW_QR in actions.pending!!.actions)

        // Tag update & failed-persistence rollback.
        assertTrue(actions.openMenu(hotp.getListId(), revealed = true, canMoveUp = false, canMoveDown = false) { true })
        assertTrue(actions.selectSubSurface(actions.pending!!, DesktopEntryActions.Action.MANAGE_TAGS, revealed = true) { true })
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

        assertTrue(actions.openMenu(hotp.getListId(), revealed = true, canMoveUp = false, canMoveDown = false) { true })
        assertTrue(actions.selectSubSurface(actions.pending!!, DesktopEntryActions.Action.CHANGE_THUMBNAIL, revealed = true) { true })
        val validThumb = ThumbnailCatalog.NAMES.first { it != ThumbnailCatalog.DEFAULT_NAME }
        assertTrue(actions.submitThumbnail(actions.pending!!, validThumb) { true })
        assertEquals(validThumb, hotp.getThumbnailName())

        // Per-entry browser password CSV export (gated on reveal) and password archive export.
        val browserPw = PasswordImport.prepare(
            "name,url,username,password\nGitHub,https://github.com,alice,pw-999\n".toByteArray(),
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

        assertTrue(actions.openMenu(browserPw.getListId(), revealed = false, canMoveUp = false, canMoveDown = false) { true })
        assertFalse(DesktopEntryActions.Action.EXPORT_PASSWORD_CSV in actions.pending!!.actions)
        assertTrue(actions.openMenu(browserPw.getListId(), revealed = true, canMoveUp = false, canMoveDown = false) { true })
        assertTrue(DesktopEntryActions.Action.EXPORT_PASSWORD_CSV in actions.pending!!.actions)

        assertTrue(actions.openMenu(archiveEntry.getListId(), revealed = false, canMoveUp = false, canMoveDown = false) { true })
        assertTrue(DesktopEntryActions.Action.EXPORT_PASSWORD_ARCHIVE in actions.pending!!.actions)

        val direct = Executor { it.run() }
        var singleCsv: ByteArray? = null
        val pwExporter = DesktopPasswordExport(state, direct, { it.run() }, {}, write = { _, b -> singleCsv = b.copyOf() })
        assertFalse(pwExporter.openSingle(browserPw.getListId(), revealed = false, File("pw.csv")) { true })
        assertTrue(pwExporter.openSingle(browserPw.getListId(), revealed = true, File("pw.csv")) { true })
        assertTrue(pwExporter.confirm(pwExporter.pending!!) { true })
        assertTrue(String(singleCsv!!, StandardCharsets.UTF_8).contains("pw-999"))
        pwExporter.close()

        var singleArchive: ByteArray? = null
        val archiveExporter = DesktopArchiveExport(state, direct, { it.run() }, {}, write = { _, b -> singleArchive = b.copyOf() })
        assertTrue(archiveExporter.openSingle(archiveEntry.getListId(), File("archive.bin")) { true })
        assertTrue(archiveExporter.confirm(archiveExporter.pending!!) { true })
        assertEquals("raw-archive-payload", String(singleArchive!!, StandardCharsets.UTF_8))
        archiveExporter.close()

        actions.close()
        assertNull(actions.pending)
        state.lock()
    }
}
