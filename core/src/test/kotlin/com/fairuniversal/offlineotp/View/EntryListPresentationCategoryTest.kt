/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for the entry-to-tab mapping both clients use when they reveal or add an entry.
 */
package com.fairuniversal.offlineotp.View

import org.junit.Assert.assertEquals
import org.junit.Test
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.Utilities.TokenCalculator

import java.util.ArrayList

class EntryListPresentationCategoryTest {

    private fun entry(type: Entry.OTPType): Entry = when (type) {
        Entry.OTPType.NOTE -> Entry(Entry.OTPType.NOTE, "body", "Issuer", "label", ArrayList())
        Entry.OTPType.SSH_KEY -> Entry.sshKey("ssh-rsa AAAA", "-----BEGIN KEY-----", "Issuer", "label",
            ArrayList())
        // mOTP seeds are raw strings rather than Base32 and carry no digits/period parameters.
        Entry.OTPType.MOTP -> Entry(type, "seed", "Issuer", "label", ArrayList())
        // Steam locks its five-digit contract.
        Entry.OTPType.STEAM -> Entry(type, "JBSWY3DPEHPK3PXP", 30, 5, "Issuer", "label",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        else -> Entry(type, "JBSWY3DPEHPK3PXP", 30, 6, "Issuer", "label",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
    }

    @Test
    fun everyKindMapsOntoTheTabThatShowsIt() {
        val expectations = listOf(
            Entry.OTPType.TOTP to EntryListPresentation.Category.OTP,
            Entry.OTPType.HOTP to EntryListPresentation.Category.OTP,
            Entry.OTPType.STEAM to EntryListPresentation.Category.OTP,
            Entry.OTPType.MOTP to EntryListPresentation.Category.OTP,
            Entry.OTPType.NOTE to EntryListPresentation.Category.NOTES,
            Entry.OTPType.SSH_KEY to EntryListPresentation.Category.SSH_KEYS)
        for ((type, category) in expectations) {
            val created = entry(type)
            try {
                assertEquals("$type", category, EntryListPresentation.categoryOf(created))
            } finally { created.destroy() }
        }
    }

    @Test
    fun importedPasswordsLandInThePasswordsTab() {
        val individual = PasswordImport.prepare(
            "Title,URL,Username,Password,Notes\nSite,https://site.test,alice,secret,\n".toByteArray(),
            "passwords.csv", PasswordImport.Mode.INDIVIDUAL).single()
        val archive = PasswordImport.prepare(
            "Title,URL,Username,Password,Notes\nSite,https://site.test,alice,secret,\n".toByteArray(),
            "passwords.csv", PasswordImport.Mode.ARCHIVE).single()
        try {
            assertEquals(EntryListPresentation.Category.PASSWORDS,
                EntryListPresentation.categoryOf(individual))
            assertEquals(EntryListPresentation.Category.PASSWORDS,
                EntryListPresentation.categoryOf(archive))
        } finally { individual.destroy(); archive.destroy() }
    }
}
