/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Backup file names are dated from a fixed [java.util.Date], so these vectors pin the shared
 * template against the wall clock and the default time zone.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BackupFileNamesTest {
    private lateinit var originalZone: TimeZone

    @Before
    fun useUtc() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun timestampIsSortableAndLocaleIndependent() {
        assertEquals("1970-01-01_00-00-00", BackupFileNames.timestamp(Date(0)))
        assertEquals("2026-10-30_12-34-56", BackupFileNames.timestamp(Date(1793363696_000L)))
    }

    @Test
    fun encryptedNamesUseTheAndroidDatedTemplate() {
        val name = BackupFileNames.encrypted(Date(0))
        assertEquals(
            String.format(Locale.ROOT, Constants.BACKUP_FILENAME_CRYPT_FORMAT, "1970-01-01_00-00-00"),
            name)
        assertEquals("otp_accounts_1970-01-01_00-00-00.json.aes", name)
        // The fixed legacy name would make a second export overwrite the first one.
        assertNotEquals(Constants.BACKUP_FILENAME_CRYPT, name)
    }

    @Test
    fun openPgpNamesUseTheAndroidDatedTemplate() {
        val name = BackupFileNames.openPgp(Date(0))
        assertEquals("otp_accounts_1970-01-01_00-00-00.json.gpg", name)
        assertNotEquals(Constants.BACKUP_FILENAME_PGP, name)
        assertTrue(name.startsWith("otp_accounts_"))
    }

    @Test
    fun twoExportsNeverShareAName() {
        assertNotEquals(
            BackupFileNames.encrypted(Date(0)),
            BackupFileNames.encrypted(Date(1_000)))
    }
}
