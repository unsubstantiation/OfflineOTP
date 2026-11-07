package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The retention decision that guards a user's backup folder against deleting the wrong file. */
class BackupRetentionTest {
    private val uuid = "3f2a8c1e-9b4d-4f6a-8e21-7c5d0b9a1e33"

    private fun auto(name: String = "otp_accounts_$uuid.json.aes", at: Long) =
        BackupRetention.Candidate(name, at)

    /** An automatic backup whose timestamp the provider does not report. */
    private fun nameless() =
        auto("otp_accounts_99999999-9b4d-4f6a-8e21-7c5d0b9a1e33.json.aes", at = 0L)

    /** A distinct, well-formed automatic-backup name; [index] makes the UUID unique. */
    private fun named(index: Int, at: Long) =
        auto("otp_accounts_0000000$index-9b4d-4f6a-8e21-7c5d0b9a1e33.json.aes", at)

    @Test
    fun onlyThisAppsAutomaticBackupNamesAreCandidates() {
        assertTrue(BackupRetention.isPrunableName("otp_accounts_$uuid.json.aes"))
        assertTrue(BackupRetention.isPrunableName("otp_accounts_$uuid.json.gpg"))
        // Case-insensitive hex, as a UUID string may be upper case.
        assertTrue(BackupRetention.isPrunableName("otp_accounts_${uuid.uppercase()}.json.aes"))

        for (name in listOf(
            null,
            "",
            "otp_accounts.json.aes",                        // the fixed name a user export uses
            "otp_accounts_2026-11-06_12-00-00.json.aes",    // a dated manual export
            "otp_accounts_$uuid.json",                      // not a backup suffix
            "otp_accounts_$uuid.txt",
            "otp_accounts_${uuid.dropLast(1)}.json.aes",    // 35-character stem
            "otp_accounts_${uuid}0.json.aes",               // 37-character stem
            "otp_accounts_${uuid.replace('-', 'x')}.json.aes",
            "my_otp_accounts_$uuid.json.aes",
            "secrets.dat",
            "hardware-unlock.b64",
            "notes.txt",
        )) {
            assertFalse("must never be pruned: $name", BackupRetention.isPrunableName(name))
        }
    }

    @Test
    fun theNewestCopiesAreAlwaysKept() {
        // Distinct names so the plan can be compared exactly.
        val documents = (1..7).map { named(it, it * 1_000L) }
        val doomed = BackupRetention.plan(documents, keep = 3)
        assertEquals(4, doomed.size)
        // oldest first
        assertEquals(documents.take(4).map { it.name }, doomed)
        // The three newest are never in the plan, whichever way the caller applies it.
        val kept = documents.map { it.name } - doomed.toSet()
        // Sorted lists, not sets: the JUnit shim compares with `==` on the boxed values.
        assertEquals(documents.takeLast(3).map { it.name }.sorted(), kept.sorted())
    }

    @Test
    fun nothingIsPrunedWhenRetentionIsOffOrNothingIsOldEnough() {
        val documents = (1..3).map { named(it, it * 1_000L) }
        assertTrue("0 disables pruning", BackupRetention.plan(documents, keep = 0).isEmpty())
        assertTrue("keep >= size keeps everything", BackupRetention.plan(documents, keep = 3).isEmpty())
        assertTrue(BackupRetention.plan(documents, keep = 10).isEmpty())
        assertTrue(BackupRetention.plan(emptyList(), keep = 1).isEmpty())
    }

    @Test
    fun nonBackupFilesAreIgnoredHoweverOldTheyAre() {
        val documents = listOf(
            auto(at = 1_000L),
            auto(at = 2_000L),
            BackupRetention.Candidate("otp_accounts_2020-01-01_00-00-00.json.aes", 1L),
            BackupRetention.Candidate("holiday-photo.jpg", 1L),
            BackupRetention.Candidate("secrets.dat", 1L),
        )
        // Only the two automatic backups compete for the single retained slot; the dated export and
        // the unrelated files are older than everything and still never appear in the plan.
        val doomed = BackupRetention.plan(documents, keep = 1)
        assertEquals(listOf(documents[0].name), doomed)
    }

    @Test
    fun anUnknownTimestampIsTreatedAsNewestRatherThanDeleted() {
        val documents = listOf(
            named(1, 1_000L),
            named(2, 2_000L),
            nameless()
        )
        // keep=1 keeps the unknown-age document; the two timestamped ones are older.
        val doomed = BackupRetention.plan(documents, keep = 1)
        assertEquals(2, doomed.size)
        assertFalse("an unset timestamp is never the pruned copy",
            doomed.last() == documents[2].name)
    }

    @Test
    fun tiesAreOrderedByNameSoAFailureCanOnlyPruneLess() {
        val a = BackupRetention.Candidate("otp_accounts_0000aaaa-9b4d-4f6a-8e21-7c5d0b9a1e33.json.aes", 5_000L)
        val b = BackupRetention.Candidate("otp_accounts_1111aaaa-9b4d-4f6a-8e21-7c5d0b9a1e33.json.aes", 5_000L)
        // Deterministic order for identical timestamps: the plan does not depend on list order.
        assertEquals(BackupRetention.plan(listOf(a, b), keep = 1),
            BackupRetention.plan(listOf(b, a), keep = 1))
        assertEquals(listOf(b.name), BackupRetention.plan(listOf(a, b), keep = 1))
    }

    @Test
    fun aStoredRetentionValueIsClampedToTheOfferedChoices() {
        for (choice in BackupRetention.CHOICES) assertEquals(choice, BackupRetention.normalizeKeep(choice))
        assertEquals(0, BackupRetention.normalizeKeep(-1))
        assertEquals(0, BackupRetention.normalizeKeep(-100))
        assertEquals(0, BackupRetention.normalizeKeep(BackupRetention.MAX_KEEP + 1))
        assertEquals(0, BackupRetention.normalizeKeep(Int.MIN_VALUE))
        assertEquals(7, BackupRetention.normalizeKeep(7))
    }
}
