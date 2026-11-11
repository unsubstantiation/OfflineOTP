package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.ui.vault.ManualEntryInput
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPasswordExportPlanTest {
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
    private fun row(vararg fields: Pair<String, String>) = Entry.passwordFields("alice", "site", fields.toList())
    private fun chrome(extra: Pair<String, String> = "note" to "note") = row(
        "name" to "Site", "url" to "https://example.com", "username" to "alice", "password" to "secret", extra)
    private fun parsed(bytes: ByteArray) = BrowserPasswordCsv.parse(bytes) as BrowserPasswordCsv.Result.Success

    @Test fun mixedDialectsAndUnknownColumnsAreNeverConvertedOrDropped() {
        val state = state()
        state.add(chrome())
        state.add(chrome("provider_extra" to "keep,\"quoted\"\nline"))
        state.add(row("Title" to "Safari", "URL" to "https://safari", "Username" to "bob",
            "Password" to "safari-secret", "OTPAuth" to "otpauth://private"))
        state.applySearch("nothing matches")
        val plan = DesktopPasswordExportPlan.prepare(state)
        try {
            assertEquals(3, plan.count); assertEquals(3, plan.groups.size)
            assertEquals(listOf(PasswordCsvExport.Dialect.CHROME, PasswordCsvExport.Dialect.CHROME,
                PasswordCsvExport.Dialect.SAFARI), plan.groups.map { it.dialect })
            state.lock() // Detached payloads survive destruction of source entries.
            plan.groups.forEachIndexed { index, group ->
                val bytes = group.takeBytes()!!
                try {
                    val result = parsed(bytes)
                    when (index) {
                        0 -> { assertEquals("note", result.columns.last()); assertEquals("note", result.rows.single().valueAt(4)) }
                        1 -> { assertEquals("provider_extra", result.columns.last()); assertEquals("keep,\"quoted\"\nline", result.rows.single().valueAt(4)) }
                        2 -> { assertEquals("OTPAuth", result.columns.last()); assertEquals("otpauth://private", result.rows.single().valueAt(4)) }
                    }
                } finally { bytes.fill(0) }
                assertTrue(group.takeBytes() == null)
            }
        } finally { plan.close(); state.lock() }
    }

    @Test fun identicalHeadersCoalesceInVaultOrderButReorderedColumnsRemainSeparate() {
        val state = state()
        state.add(chrome())
        state.add(row("NAME" to "Second", "URL" to "https://second", "USERNAME" to "bob", "PASSWORD" to "second", "NOTE" to "other"))
        state.add(row("url" to "https://third", "name" to "Third", "username" to "carol", "password" to "third", "note" to "last"))
        val plan = DesktopPasswordExportPlan.prepare(state)
        try {
            assertEquals(listOf(2, 1), plan.groups.map { it.count })
            val bytes = plan.groups.first().takeBytes()!!
            try {
                val result = parsed(bytes)
                assertEquals(listOf("name", "url", "username", "password", "note"), result.columns)
                assertEquals("alice", result.rows[0].valueAt(2)); assertEquals("bob", result.rows[1].valueAt(2))
            } finally { bytes.fill(0) }
        } finally { plan.close(); state.lock() }
    }

    @Test fun exclusionsAreCountedWithoutExportingArchivesOrForeignCredentials() {
        val state = state()
        state.add(row("url" to "ssh://host", "username" to "alice", "password" to "foreign"))
        state.add(Entry.passwordArchive("backup.csv", "archive secret".toByteArray()))
        state.add(ManualEntryInput(secret = "JBSWY3DPEHPK3PXP").build())
        val plan = DesktopPasswordExportPlan.prepare(state)
        try {
            assertEquals(0, plan.count); assertTrue(plan.groups.isEmpty())
            assertEquals(1, plan.foreignPasswords); assertEquals(1, plan.archives)
        } finally { plan.close(); state.lock() }
    }

    @Test fun closePreventsFurtherTransfersAndDoesNotWipeAlreadyTransferredBuffer() {
        val state = state(); state.add(chrome()); state.add(chrome("other" to "sensitive"))
        val plan = DesktopPasswordExportPlan.prepare(state)
        try {
            assertFalse(plan.toString().contains("secret"))
            assertFalse(plan.groups[0].toString().contains("secret"))
            val owned = plan.groups[0].takeBytes()!!
            try {
                plan.close(); plan.close()
                assertTrue(plan.groups.all { it.takeBytes() == null })
                assertTrue(String(owned).contains("secret")) // Writer owns cleanup after transfer.
            } finally { owned.fill(0) }
        } finally { plan.close(); state.lock() }
    }

    @Test fun blankCellsAreNotReplacedByConflictingAliasColumns() {
        val state = state()
        state.add(row("name" to "", "title" to "alternate", "url" to "https://example.com",
            "username" to "alice", "password" to "secret", "note" to "", "notes" to "other notes"))
        val plan = DesktopPasswordExportPlan.prepare(state)
        try {
            val bytes = plan.groups.single().takeBytes()!!
            try {
                val result = parsed(bytes)
                assertEquals("", result.rows.single().valueAt(0))
                assertEquals("alternate", result.rows.single().valueAt(1))
                assertEquals("", result.rows.single().valueAt(5))
                assertEquals("other notes", result.rows.single().valueAt(6))
            } finally { bytes.fill(0) }
        } finally { plan.close(); state.lock() }
    }

    @Test fun lockedVaultCannotPrepareSnapshot() {
        val state = state(); state.lock()
        var rejected = false
        try { DesktopPasswordExportPlan.prepare(state).close() } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }
}
