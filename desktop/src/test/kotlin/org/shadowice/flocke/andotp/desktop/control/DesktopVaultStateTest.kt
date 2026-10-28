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
