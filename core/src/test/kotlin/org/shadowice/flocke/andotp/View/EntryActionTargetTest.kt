/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the borrowed-identity target used by delayed entry actions.
 */
package org.shadowice.flocke.andotp.View

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

class EntryActionTargetTest {
    private fun entry(label: String): Entry = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6,
        "Example", label, TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    private fun listWith(vararg entries: Entry): EntryList = EntryList().apply {
        for (candidate in entries) addEntry(candidate)
    }

    @Test
    fun resolvesTheSameInstanceUnderTheSameGeneration() {
        val original = entry("alice")
        val list = listWith(original)
        val target = EntryActionTarget(original, generation = 1L)

        assertEquals(original, target.resolve(list, currentGeneration = 1L))
    }

    @Test
    fun aStaleGenerationDoesNotResolve() {
        val original = entry("alice")
        val list = listWith(original)
        val target = EntryActionTarget(original, generation = 1L)

        assertNull(target.resolve(list, currentGeneration = 2L))
    }

    @Test
    fun aReplacedEntryDoesNotResolve() {
        val original = entry("alice")
        val list = listWith(original)
        val target = EntryActionTarget(original, generation = 1L)

        // An edit replaces the entry object; the old target must not act on the new one.
        val replacement = entry("alice")
        list.addEntry(replacement, true)
        assertNull(target.resolve(list, 1L))
        assertEquals(replacement, list.getEntry(0))
    }

    @Test
    fun aRemovedEntryDoesNotResolve() {
        val original = entry("alice")
        val list = listWith(original)
        val target = EntryActionTarget(original, generation = 1L)

        list.removeEntry(0)
        assertNull(target.resolve(list, 1L))
    }

    @Test
    fun anEqualButDifferentInstanceDoesNotResolve() {
        val original = entry("alice")
        val equalTwin = entry("alice")
        val list = listWith(equalTwin)
        val target = EntryActionTarget(original, generation = 1L)

        // Content equality is not identity: the target holds a borrowed reference.
        assertEquals(original, equalTwin)
        assertNull(target.resolve(list, 1L))
    }

    @Test
    fun theToStringRedactsEverything() {
        val target = EntryActionTarget(entry("alice"), 1L)
        assertTrue(!target.toString().contains("alice"))
        assertEquals("EntryActionTarget(redacted)", target.toString())
    }
}
