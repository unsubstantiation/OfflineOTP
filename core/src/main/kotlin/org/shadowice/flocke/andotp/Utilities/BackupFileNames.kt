package org.shadowice.flocke.andotp.Utilities

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Default names for exported backup files.
 *
 * A new backup is stamped with the moment it was written
 * (`otp_accounts_2026-10-30_12-34-56.json.aes`) instead of a fixed name, so repeated exports never
 * overwrite an earlier backup by accident. Android has always done this; the desktop client uses
 * the same templates so both clients produce identical, interchangeable names.
 */
object BackupFileNames {
    /**
     * Sortable, locale-independent timestamp for backup names. Android's
     * `Tools.getDateTimeString()` delegates here so the two clients cannot drift.
     */
    const val TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    /** Dated encrypted-backup name, e.g. `otp_accounts_2026-10-30_12-34-56.json.aes`. */
    @JvmStatic
    fun encrypted(date: Date): String = String.format(
        Locale.ROOT, Constants.BACKUP_FILENAME_CRYPT_FORMAT, timestamp(date))

    /** Dated OpenPGP-backup name, e.g. `otp_accounts_2026-10-30_12-34-56.json.gpg`. */
    @JvmStatic
    fun openPgp(date: Date): String = String.format(
        Locale.ROOT, Constants.BACKUP_FILENAME_PGP_FORMAT, timestamp(date))

    /** [date] in [TIMESTAMP_PATTERN]; a fixed [Locale.ENGLISH] keeps the digits ASCII everywhere. */
    @JvmStatic
    fun timestamp(date: Date): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ENGLISH).format(date)
}
