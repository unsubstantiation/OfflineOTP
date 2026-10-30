/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the unlocked-vault state: entry mutations, the core search projection and
 * token ticking, all without a display.
 */
package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.PasswordImport
import org.shadowice.flocke.andotp.View.EntryListPresentation
import javax.crypto.KeyGenerator
import java.util.ArrayList

class DesktopVaultStateTest {

    private fun state() = DesktopVaultState(KeyGenerator.getInstance("AES").generateKey())

    private fun entry(issuer: String, label: String, secret: String = "JBSWY3DPEHPK3PXP"): Entry =
        Entry(Entry.OTPType.TOTP, secret, 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun addReplaceAndRemoveMutateTheProjection() {
        val state = state()
        val first = entry("Issuer A", "alice")
        val second = entry("Issuer B", "bob")

        assertTrue(state.add(first))
        assertFalse(state.add(first)) // equal entries are not duplicated
        assertTrue(state.add(second))
        assertEquals(2, state.presentation.size())

        val replacement = entry("Issuer A", "alice-renamed")
        assertTrue(state.replace(first, replacement))
        assertEquals(2, state.presentation.size())
        assertEquals("alice-renamed", state.snapshot().first { it.getIssuer() == "Issuer A" }.getLabel())

        assertNotNull(state.removeAt(0))
        assertEquals(1, state.snapshot().size)
    }

    @Test
    fun replaceKeepsTheManualPositionAndRowIdentity() {
        val state = state()
        val first = entry("Issuer A", "alice")
        val second = entry("Issuer B", "bob")
        state.add(first)
        state.add(second)
        val originalId = first.getListId()

        val replacement = entry("Issuer A", "alice-renamed")
        assertTrue(state.replace(first, replacement))

        // The edited entry stays first instead of being appended, and keeps the old list id.
        assertEquals(0, state.snapshot().indexOf(replacement))
        assertEquals(originalId, replacement.getListId())
        assertEquals("bob", state.snapshot()[1].getLabel())
    }

    @Test
    fun searchNarrowsTheProjectionAndABlankQueryRestoresIt() {
        val state = state()
        state.add(entry("GitHub", "alice"))
        state.add(entry("GitLab", "bob"))
        state.add(entry("Amazon", "orders"))

        state.applySearch("git")
        assertEquals(2, state.presentation.size())

        state.applySearch("amazon")
        assertEquals(1, state.presentation.size())
        assertEquals("Amazon", state.presentation.get(0).getIssuer())

        state.applySearch("")
        assertEquals(3, state.presentation.size())
    }

    @Test
    fun tagFilteringUsesTheCoreProjection() {
        val state = state()
        val tagged = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Tagged", "t",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf("work"))
        state.add(tagged)
        state.add(entry("Untagged", "u"))

        state.applyTagFilter(listOf("work"), false, Constants.TagFunctionality.SINGLE)
        assertEquals(1, state.presentation.size())
        assertEquals("Tagged", state.presentation.get(0).getIssuer())

        state.applyTagFilter(emptyList(), false)
        assertEquals(2, state.presentation.size())
    }

    @Test
    fun tagSearchAndCategoryComposeAcrossMutations() {
        val state = state()
        val first = entry("Service", "alice").apply { setTags(arrayListOf("work", "東京")) }
        val second = entry("Service", "bob").apply { setTags(arrayListOf("personal")) }
        val noTags = entry("Service", "charlie")
        val note = Entry(Entry.OTPType.NOTE, "body", "", "alice note", arrayListOf("work"))
        listOf(first, second, noTags, note).forEach { state.add(it) }
        state.presentation.setCategory(EntryListPresentation.Category.OTP)
        state.applyTagFilter(listOf("work"), false)
        state.applySearch("alice")
        assertEquals(1, state.presentation.size())
        assertEquals("alice", state.presentation.get(0).getLabel())
        state.applySearch("bob")
        assertEquals(0, state.presentation.size())
        state.applySearch("")
        assertEquals(1, state.presentation.size())
        // New and removed rows do not drop the active filter.
        val third = entry("Service", "dave").apply { setTags(arrayListOf("work")) }
        state.add(third)
        assertEquals(2, state.presentation.size())
        state.removeAt(state.snapshot().indexOf(third))
        assertEquals(1, state.presentation.size())
        state.presentation.setCategory(EntryListPresentation.Category.NOTES)
        state.refreshProjection()
        assertEquals("alice note", state.presentation.get(0).getLabel())
        state.presentation.setCategory(EntryListPresentation.Category.OTP)
        state.applyTagFilter(emptyList(), true)
        assertEquals(1, state.presentation.size())
        assertEquals("charlie", state.presentation.get(0).getLabel())
        state.applyTagFilter(listOf("work", "東京"), false, Constants.TagFunctionality.AND)
        assertEquals(1, state.presentation.size())
        state.applyTagFilter(emptyList(), false)
        assertEquals(3, state.presentation.size())
    }

    @Test
    fun sameTagFiltersEveryCategoryIncludingBothPasswordKinds() {
        val state = state()
        val csv = "name,url,username,password\nsite,https://example.test,alice,secret\n".toByteArray()
        val items = listOf(entry("", "OTP"),
            Entry(Entry.OTPType.NOTE, "note", "", "Note", arrayListOf()),
            Entry.sshKey("public", "private", "", "Key", arrayListOf()),
            PasswordImport.prepare(csv, "p.csv", PasswordImport.Mode.INDIVIDUAL).single(),
            PasswordImport.prepare(csv, "p.csv", PasswordImport.Mode.ARCHIVE).single())
        items.forEach { it.setTags(arrayListOf("shared")); assertTrue(state.add(it)) }
        state.applyTagFilter(listOf("shared"), false)
        for ((category, count) in listOf(EntryListPresentation.Category.OTP to 1,
            EntryListPresentation.Category.NOTES to 1, EntryListPresentation.Category.SSH_KEYS to 1,
            EntryListPresentation.Category.PASSWORDS to 2)) {
            state.presentation.setCategory(category)
            state.refreshProjection()
            assertEquals(count, state.presentation.size())
            state.applySearch("shared")
            assertEquals(count, state.presentation.size())
            state.applySearch("")
        }
    }

    @Test
    fun aTokenTickPopulatesCachedOtps() {
        val state = state()
        state.add(entry("Issuer", "label"))
        assertEquals(null, state.snapshot()[0].getCurrentOTP())

        val updated = state.tickTokens()
        assertEquals(1, updated)
        assertNotNull(state.snapshot()[0].getCurrentOTP())
        assertEquals(6, state.snapshot()[0].getCurrentOTP()!!.length)
    }
}
