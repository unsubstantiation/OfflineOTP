package com.fairuniversal.offlineotp.desktop.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import com.fairuniversal.offlineotp.desktop.ui.QrCodeDialog

import java.util.ArrayList

/**
 * Which entries can be shown as a QR code, and what the payload is. Android hides
 * `menu_popup_show_qr_code` for notes, SSH keys, password entries and email accounts because those
 * have no `otpauth://` form; the desktop action is bound to the same rule, so a menu item can never
 * appear for an entry whose code would not scan. The pixel work is compile-verified only (the
 * sandbox zxing stubs never encode), so this covers the payload and labelling rules.
 */
class QrCodeImageTest {

    private fun totp(issuer: String = "GitHub", label: String = "alice") =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun otpEntriesCarryAnOtpauthPayload() {
        val totp = totp()
        try {
            val payload = QrCodeImage.payloadFor(totp)
            assertTrue(payload!!.startsWith("otpauth://totp/"))
            assertTrue(payload.contains("secret=JBSWY3DPEHPK3PXP"))
            // The payload is exactly what Android encodes, so a scan of either client is identical.
            assertEquals(totp.toUriString(), payload)
        } finally { totp.destroy() }
    }

    @Test
    fun hotpAndSteamEntriesAreEncodableToo() {
        val hotp = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Bank", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        val steam = Entry(Entry.OTPType.STEAM, "JBSWY3DPEHPK3PXP", 30, 5, "Steam", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())
        try {
            assertTrue(QrCodeImage.payloadFor(hotp)!!.startsWith("otpauth://hotp/"))
            // Steam keeps its own scheme name so a scanner knows to use the Steam alphabet.
            assertTrue(QrCodeImage.payloadFor(steam)!!.startsWith("otpauth://steam/"))
        } finally { hotp.destroy(); steam.destroy() }
    }

    @Test
    fun kindsWithoutAnOtpUriHaveNoQrCode() {
        val note = Entry(Entry.OTPType.NOTE, "body", "", "alice note", arrayListOf())
        val ssh = Entry.sshKey("ssh-rsa AAAA", "-----BEGIN KEY-----", "server", "host", ArrayList())
        val password = PasswordImport.prepare(
            "Title,URL,Username,Password,Notes\nSite,https://site.test,alice,secret,\n".toByteArray(),
            "passwords.csv", PasswordImport.Mode.INDIVIDUAL).single()
        val archive = Entry.passwordArchive("passwords.csv", "a,b\n1,2\n".toByteArray())
        try {
            assertNull(QrCodeImage.payloadFor(note))
            assertNull(QrCodeImage.payloadFor(ssh))
            assertNull(QrCodeImage.payloadFor(password))
            assertNull(QrCodeImage.payloadFor(archive))
        } finally {
            listOf(note, ssh, password, archive).forEach { it.destroy() }
        }
    }

    @Test
    fun theDialogHeadingNamesTheEntryWithoutConcealedFields() {
        val entry = totp("GitHub", "alice")
        try {
            assertEquals("GitHub – alice", QrCodeDialog.entryTitle(entry))
        } finally { entry.destroy() }
        val labelOnly = totp("", "alice")
        val issuerOnly = totp("GitHub", "")
        try {
            assertEquals("alice", QrCodeDialog.entryTitle(labelOnly))
            assertEquals("GitHub", QrCodeDialog.entryTitle(issuerOnly))
        } finally { labelOnly.destroy(); issuerOnly.destroy() }
    }

    @Test
    fun theRefusalMessageExplainsTheMissingCode() {
        assertTrue(QrCodeDialog.UNSUPPORTED.contains("cannot be shown as a QR code"))
    }
}
