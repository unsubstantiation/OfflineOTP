package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordEntryInputTest {
    @Test fun conflictingAliasesAreNotSilentlyRewritten() {
        val entry = Entry.passwordFields("user", "site", listOf("url" to "site", "username" to "user", "password" to "secret", "host" to "keep-host"))
        try { assertFalse(PasswordEntryInput.supports(entry)) } finally { entry.destroy() }
    }
    @Test fun editsPreserveExtraColumnsMetadataAndBlankPasswords() {
        val original = Entry.passwordFields("alice", "site", listOf("url" to "https://old", "username" to "alice",
            "password" to "private", "notes" to "keep notes", "guid" to "keep guid"), arrayListOf("work"))
        original.setHiddenLabel("preserved hidden label"); original.setColor(123); original.setUsedFrequency(4); original.setLastUsed(123); original.setDetailsVisible(true)
        try {
            val initial = PasswordEntryInput.fromEntry(original)
            assertEquals("", initial.password)
            assertEquals("alice", initial.username)
            for (password in listOf("", "new secret", "x".repeat(16385))) {
                val input = initial.copy(url = "https://new", username = "bob", password = password)
                val edited = input.build(original)
                try {
                    val fields = edited.getPasswordFields()
                    assertEquals("https://new", fields.value("url"))
                    assertEquals("bob", fields.value("username"))
                    assertEquals(if (password.isEmpty()) "private" else password, fields.value("password"))
                    assertEquals("keep notes", fields.value("notes"))
                    assertEquals("keep guid", fields.value("guid"))
                    assertEquals(listOf("work"), edited.getTags())
                    assertEquals("preserved hidden label", edited.getHiddenLabel())
                    assertEquals(123, edited.getColor())
                    assertEquals(4L, edited.getUsedFrequency()); assertEquals(123L, edited.getLastUsed())
                    assertTrue(edited.areDetailsVisible())
                    assertEquals("private", original.getPasswordFields().value("password"))
                    assertEquals("", input.withoutPassword().password)
                    assertFalse(input.toString().contains("https://new"))
                } finally { edited.destroy() }
            }
        } finally { original.destroy() }
    }

    @Test fun fromAnyEntrySupportsForeignColumnsCustomNamesAndArchives() {
        val foreignPw = Entry.passwordFields(
            "alice",
            "Bastion",
            listOf(
                "url" to "ssh://bastion:22",
                "username" to "alice",
                "password" to "pw-secret",
                "host" to "bastion.example.com",
                "passphrase" to "keep-phrase"
            )
        )
        val linScpPw = Entry.passwordFields(
            "ops",
            "Service",
            listOf(
                "url" to "ssh://svc:22",
                "username" to "ops",
                "password" to "tok-1",
                "linscp_session_json" to "{\"port\":22}"
            )
        )
        val archive = Entry.passwordArchive("vault.kdbx", "raw-bytes".toByteArray())
        try {
            assertFalse(PasswordEntryInput.supports(foreignPw))
            assertTrue(PasswordEntryInput.supportsAny(foreignPw))
            assertTrue(PasswordEntryInput.supportsAny(linScpPw))
            assertTrue(PasswordEntryInput.supportsAny(archive))

            val foreignInit = PasswordEntryInput.fromAnyEntry(foreignPw)
            assertTrue(foreignInit.customColumnsMode)
            assertEquals("", foreignInit.extraFields!!.single { it.name == "password" }.value)
            assertEquals("", foreignInit.extraFields!!.single { it.name == "passphrase" }.value)
            val foreignSaved = foreignInit.copy(tags = "ssh\nops", thumbnail = "GitHub").build(foreignPw)
            try {
                assertEquals("pw-secret", foreignSaved.getPasswordFields().value("password"))
                assertEquals("keep-phrase", foreignSaved.getPasswordFields().value("passphrase"))
                assertEquals(listOf("ssh", "ops"), foreignSaved.getTags())
                assertEquals("GitHub", foreignSaved.getThumbnailName())
            } finally { foreignSaved.destroy() }

            val customInit = PasswordEntryInput.fromAnyEntry(linScpPw)
            assertFalse(customInit.customColumnsMode)
            val customSaved = customInit.copy(tags = "internal", thumbnail = "GitHub").build(linScpPw)
            try {
                assertEquals("tok-1", customSaved.getPasswordFields().value("password"))
                assertEquals("{\"port\":22}", customSaved.getPasswordFields().value("linscp_session_json"))
                assertEquals(listOf("internal"), customSaved.getTags())
            } finally { customSaved.destroy() }

            val archInit = PasswordEntryInput.fromAnyEntry(archive)
            assertTrue(archInit.archiveMode)
            assertEquals(9, archInit.archiveSizeBytes)
            val archSaved = archInit.copy(issuer = "Backup Archive", tags = "archive").build(archive)
            try {
                assertEquals("Backup Archive", archSaved.getIssuer())
                assertEquals(listOf("archive"), archSaved.getTags())
                assertEquals("raw-bytes", String(archSaved.exportPasswordArchive()))
            } finally { archSaved.destroy() }
        } finally {
            foreignPw.destroy()
            linScpPw.destroy()
            archive.destroy()
        }
    }
}
