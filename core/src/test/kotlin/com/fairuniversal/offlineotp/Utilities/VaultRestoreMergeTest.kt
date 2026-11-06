/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the restore rule both clients use: merge by default, replace on request, imports
 * first, and the vault's own entries keep their counters and metadata.
 */
package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Database.Entry

import java.util.ArrayList

class VaultRestoreMergeTest {

    private fun entry(issuer: String, label: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun aMergePutsTheImportedEntriesFirstAndKeepsTheCurrentOnes() {
        val existing = entry("Existing", "alice").apply { setLastUsed(1_000L); setUsedFrequency(3) }
        val current = arrayListOf(existing)
        val imported = arrayListOf(entry("New", "bob"))

        val merged = VaultRestoreMerge.plan(imported, current, replace = false)

        assertEquals(listOf("bob", "alice"), merged.map { it.getLabel() })
        // The vault's own instance survives untouched: same object, same usage metadata.
        assertTrue(merged[1] === existing)
        assertEquals(1_000L, existing.getLastUsed())
        assertEquals(3L, existing.getUsedFrequency())
    }

    @Test
    fun anImportedDuplicateIsDroppedAndDestroyed() {
        val existing = entry("Existing", "alice").apply { setLastUsed(1_000L); setUsedFrequency(3) }
        val duplicate = entry("Existing", "alice").apply { setLastUsed(9_999L); setUsedFrequency(42) }
        val imported = arrayListOf(duplicate)

        val merged = VaultRestoreMerge.plan(imported, arrayListOf(existing), replace = false)

        // Only the entry the vault already had is kept, so the counters do not jump.
        assertEquals(1, merged.size)
        assertTrue(merged[0] === existing)
        assertEquals(1_000L, merged[0].getLastUsed())
        // The duplicate left the caller's list, so a cleanup loop cannot wipe the kept entry.
        assertTrue(imported.isEmpty())
    }

    @Test
    fun aReplaceTakesTheDocumentVerbatim() {
        val existing = entry("Existing", "alice")
        val imported = arrayListOf(entry("New", "bob"), entry("Other", "carol"))

        val merged = VaultRestoreMerge.plan(imported, arrayListOf(existing), replace = true)

        assertEquals(listOf("bob", "carol"), merged.map { it.getLabel() })
        assertEquals(2, merged.size)
        // A replace ignores the current entries rather than merging them in.
        assertTrue(merged.none { it === existing })
    }

    @Test
    fun aBackupThatWouldCollapseTwoAccountsIsRejected() {
        // Two equal entries inside the document cannot both be stored: EntryList treats them as
        // one account, so the restore is refused instead of silently dropping one.
        val imported = arrayListOf(entry("Same", "alice"), entry("Same", "alice"))
        try {
            VaultRestoreMerge.plan(imported, emptyList(), replace = true)
            throw AssertionError("accepted a document with duplicate accounts")
        } catch (_: IllegalArgumentException) { }
        imported.forEach { it.destroy() }
    }

    @Test
    fun anEmptyDocumentPlusCurrentEntriesIsJustTheCurrentEntries() {
        val existing = entry("Existing", "alice")
        val merged = VaultRestoreMerge.plan(arrayListOf(), arrayListOf(existing), replace = false)
        assertEquals(1, merged.size)
        assertTrue(merged[0] === existing)
    }
}
