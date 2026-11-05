package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result text a restore reports. Android tells the user what the restore did (a merge and a
 * replace are different outcomes), so the desktop dialog must not claim "Restored 0 entries" when
 * the backup simply held nothing new.
 */
class BackupDialogsTest {

    @Test
    fun aReplaceReportsHowManyEntriesWereInstalled() {
        assertEquals("Restored 1 entry", BackupDialogs.restoreSummary(1, replace = true))
        assertEquals("Restored 3 entries", BackupDialogs.restoreSummary(3, replace = true))
    }

    @Test
    fun aMergeReportsOnlyWhatItAdded() {
        assertEquals("Merged 1 new entry into the vault", BackupDialogs.restoreSummary(1, replace = false))
        assertEquals("Merged 4 new entries into the vault", BackupDialogs.restoreSummary(4, replace = false))
        // Merging a backup of the current vault adds nothing; say so instead of "Restored 0 entries".
        val unchanged = BackupDialogs.restoreSummary(0, replace = false)
        assertTrue(unchanged, unchanged.contains("no entries"))
    }
}
