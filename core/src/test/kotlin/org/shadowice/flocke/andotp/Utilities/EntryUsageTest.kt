/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the usage rules both clients share: the last-used/frequency stamp a copy writes,
 * the stamp a new or imported entry gets, and the one-step HOTP advance.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry

import java.util.ArrayList

class EntryUsageTest {

    private fun totp(issuer: String = "GitHub", label: String = "alice"): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    private fun hotp(): Entry =
        Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Bank", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList()).apply {
            setCounter(41)
            updateOTP(false)
        }

    @Test
    fun recordUseStampsTheTimeAndCountsOneMoreUse() {
        val entry = totp()
        try {
            entry.setLastUsed(1_000L)
            entry.setUsedFrequency(4)
            EntryUsage.recordUse(entry, 5_000L)
            assertEquals(5_000L, entry.getLastUsed())
            assertEquals(5L, entry.getUsedFrequency())
            // The issuer and label are untouched: the stamp is the only change.
            assertEquals("GitHub", entry.getIssuer())
            assertEquals("alice", entry.getLabel())
        } finally { entry.destroy() }
    }

    @Test
    fun recordUseDefaultsToTheCurrentTime() {
        val entry = totp()
        try {
            val before = System.currentTimeMillis()
            EntryUsage.recordUse(entry)
            assertTrue(entry.getLastUsed() >= before)
            assertTrue(entry.getLastUsed() <= System.currentTimeMillis())
            assertEquals(1L, entry.getUsedFrequency())
        } finally { entry.destroy() }
    }

    @Test
    fun markImportedComputesTheFirstTokenAndStampsTheEntry() {
        val entry = totp()
        try {
            EntryUsage.markImported(entry, 7_000L)
            assertEquals(7_000L, entry.getLastUsed())
            assertNotEquals(null, entry.getCurrentOTP())
            // An import is not a use: the counter stays at zero.
            assertEquals(0L, entry.getUsedFrequency())
        } finally { entry.destroy() }
    }

    @Test
    fun advanceHotpMovesOneStepAndRecomputesTheToken() {
        val entry = hotp()
        try {
            val before = entry.getCurrentOTP()
            assertTrue(EntryUsage.advanceHotp(entry))
            assertEquals(42L, entry.getCounter())
            assertNotEquals(before, entry.getCurrentOTP())
        } finally { entry.destroy() }
    }

    @Test
    fun advanceHotpStopsAtTheSharedCeiling() {
        val entry = hotp()
        try {
            entry.setCounter(EntryUsage.MAX_COUNTER - 1)
            assertTrue(EntryUsage.advanceHotp(entry))
            assertEquals(EntryUsage.MAX_COUNTER, entry.getCounter())
            // At the ceiling the counter is left exactly where it is, not wrapped around.
            assertFalse(EntryUsage.advanceHotp(entry))
            assertEquals(EntryUsage.MAX_COUNTER, entry.getCounter())
        } finally { entry.destroy() }
    }

    @Test
    fun advanceHotpRefusesEntriesWithoutACounter() {
        val entry = totp()
        try {
            assertFalse(EntryUsage.advanceHotp(entry))
            assertEquals(0L, entry.getCounter())
        } finally { entry.destroy() }
    }
}
