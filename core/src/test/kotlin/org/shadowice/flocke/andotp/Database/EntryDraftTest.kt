/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the draft builder both manual-entry dialogs use: which constructor each type
 * needs, how typed digits/period/counter are read, what a note or SSH key keeps verbatim, and the
 * usage stamp a new entry gets.
 */
package org.shadowice.flocke.andotp.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.EntryUsage
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.util.ArrayList

class EntryDraftTest {

    private fun create(
        type: Entry.OTPType,
        secret: String?,
        digits: String = "6",
        timing: String = "30",
        pin: String = "",
        publicKey: String = "",
        connection: SshConnection.Details? = null,
        tags: List<String> = emptyList(),
        newEntry: Boolean = false
    ): Entry = EntryDraft.create(
        type, "issuer", "label", secret, digits, timing, TokenCalculator.HashAlgorithm.SHA1,
        pin = pin, publicKey = publicKey, sshConnection = connection,
        tags = ArrayList(tags), newEntry = newEntry)

    @Test
    fun timeBasedDraftsKeepTheirTypedPeriodDigitsAndAlgorithm() {
        val entry = create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", digits = "8", timing = "45")
        try {
            assertTrue(entry.isTimeBased())
            assertEquals(8, entry.getDigits())
            assertEquals(45, entry.getPeriod())
        } finally { entry.destroy() }
    }

    @Test
    fun hotpDraftUsesTheTimingFieldAsItsCounter() {
        val entry = create(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", timing = "17")
        try {
            assertTrue(entry.isCounterBased())
            assertEquals(17L, entry.getCounter())
        } finally { entry.destroy() }
    }

    @Test
    fun motpDraftKeepsTheRawSeedAndAnOptionalPin() {
        // mOTP has no digits field of its own, so an unreadable one cannot reject it.
        val entry = create(Entry.OTPType.MOTP, "seed value", digits = "nonsense", timing = "", pin = "1234")
        try {
            assertEquals("seed value", entry.getSecretEncoded())
            assertEquals("1234", entry.getPin())
        } finally { entry.destroy() }
    }

    @Test
    fun noteAndSshDraftsIgnoreTheDigitsAndTimingFieldsTheyCannotShow() {
        val note = create(Entry.OTPType.NOTE, "first line\nsecond line", digits = "", timing = "")
        try {
            assertEquals("first line\nsecond line", note.getNote())
            assertEquals(TokenCalculator.TOTP_DEFAULT_DIGITS, note.getDigits())
        } finally { note.destroy() }

        val ssh = create(Entry.OTPType.SSH_KEY, "private key text", digits = "nonsense",
            timing = "", publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test@example")
        try {
            assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test@example",
                ssh.getSshKeys().publicKey)
            assertEquals("private key text", ssh.getSshKeys().privateKey)
        } finally { ssh.destroy() }
    }

    @Test
    fun sshDraftCarriesTheConnectionThatCameWithIt() {
        val connection = SshConnection.of("example.com", 2222, "user", "secret")
        val ssh = create(Entry.OTPType.SSH_KEY, "private",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test@example",
            connection = connection)
        try {
            assertEquals(connection, ssh.getSshConnection())
        } finally { ssh.destroy() }
    }

    @Test
    fun aTypedDigitOrPeriodOrCounterMustBeANumber() {
        for (type in listOf(Entry.OTPType.TOTP, Entry.OTPType.HOTP, Entry.OTPType.STEAM)) {
            val rejected = try {
                create(type, "JBSWY3DPEHPK3PXP", digits = "six").destroy()
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue("$type accepted unparsable digits", rejected)
        }
        for (timing in listOf("", " thirty", "30s")) {
            val rejected = try {
                create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", timing = timing).destroy()
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue("TOTP accepted period '$timing'", rejected)
        }
        val hotpRejected = try {
            create(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", timing = "1.5").destroy()
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(hotpRejected)
        // Surrounding whitespace is a typing artefact, not a rejected value.
        create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", digits = " 6 ", timing = " 30 ").destroy()
    }

    @Test
    fun theTypesTheFormsDoNotOfferAreRefused() {
        for (type in listOf(Entry.OTPType.PASSWORD, Entry.OTPType.PASSWORD_ARCHIVE, Entry.OTPType.EMAIL)) {
            val rejected = try {
                create(type, "JBSWY3DPEHPK3PXP").destroy()
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue("$type was built by the draft builder", rejected)
        }
    }

    @Test
    fun aTitleIsRequiredWhereTheModelRequiresOne() {
        for (type in listOf(Entry.OTPType.NOTE, Entry.OTPType.SSH_KEY)) {
            val rejected = try {
                EntryDraft.create(type, "issuer", "  ", "text", "6", "30",
                    TokenCalculator.HashAlgorithm.SHA1,
                    publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA").destroy()
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue("$type accepted a blank title", rejected)
        }
    }

    @Test
    fun onlyTheSecretOfAnOtpEntryLosesItsWhitespace() {
        assertEquals("JBSWY3DPEHPK3PXP",
            EntryDraft.sanitizeSecret(Entry.OTPType.TOTP, "JBSW Y3DP\nEHPK 3PXP"))
        assertEquals("JBSWY3DPEHPK3PXP",
            EntryDraft.sanitizeSecret(Entry.OTPType.STEAM, " JBSWY3DPEHPK3PXP "))
        assertEquals("two words", EntryDraft.sanitizeSecret(Entry.OTPType.NOTE, "two words"))
        assertEquals("private key\n", EntryDraft.sanitizeSecret(Entry.OTPType.SSH_KEY, "private key\n"))
    }

    @Test
    fun aNewEntryIsStampedAsUsedNowAndAnEditedDraftIsNot() {
        val before = System.currentTimeMillis()
        val fresh = create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", newEntry = true)
        try {
            assertTrue(fresh.getLastUsed() >= before)
            // A stamp is last-used only; the frequency still counts actual uses.
            assertEquals(0L, fresh.getUsedFrequency())
        } finally { fresh.destroy() }

        val edited = create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP")
        try {
            assertEquals(0L, edited.getLastUsed())
            assertEquals(0L, edited.getUsedFrequency())
        } finally { edited.destroy() }

        // The note and SSH paths are stamped too; both used to be assembled by hand on the phone.
        val note = create(Entry.OTPType.NOTE, "text", newEntry = true)
        try { assertTrue(note.getLastUsed() > 0L) } finally { note.destroy() }
    }

    @Test
    fun aStampIsTheSameShapeAsTheOneAnImportGets() {
        val fresh = create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", newEntry = true)
        val imported = create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP")
        try {
            assertNotEquals(fresh.getLastUsed(), 0L)
            EntryUsage.markImported(imported)
            assertTrue(imported.getLastUsed() > 0L)
            assertEquals(fresh.getUsedFrequency(), imported.getUsedFrequency())
        } finally { fresh.destroy(); imported.destroy() }
    }

    @Test
    fun tagsTravelWithTheDraft() {
        val entry = create(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", tags = listOf("work", "cloud"))
        try {
            assertEquals(listOf("work", "cloud"), entry.getTags())
        } finally { entry.destroy() }
    }
}
