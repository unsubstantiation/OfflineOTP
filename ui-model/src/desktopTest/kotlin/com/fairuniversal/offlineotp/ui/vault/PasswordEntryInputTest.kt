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
        original.setUsedFrequency(4); original.setLastUsed(123); original.setDetailsVisible(true)
        try {
            val initial = PasswordEntryInput.fromEntry(original)
            assertEquals("", initial.password)
            assertEquals("alice", initial.username)
            for (password in listOf("", "new secret")) {
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
                    assertEquals(4L, edited.getUsedFrequency()); assertEquals(123L, edited.getLastUsed())
                    assertTrue(edited.areDetailsVisible())
                    assertEquals("private", original.getPasswordFields().value("password"))
                    assertEquals("", input.withoutPassword().password)
                    assertFalse(input.toString().contains("https://new"))
                } finally { edited.destroy() }
            }
        } finally { original.destroy() }
    }
}
