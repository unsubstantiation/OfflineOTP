package com.fairuniversal.offlineotp.Utilities

/**
 * Retention policy for the documents an automatic backup writes.
 *
 * Automatic exports deliberately never overwrite an earlier file (a fixed name could not tell two
 * vault states apart, and the previous copy is the last recovery path), so every eligible change
 * leaves another document in the chosen folder. Left alone that grows without bound: a phone that
 * backs up on every edit fills a provider with hundreds of snapshots nobody will read.
 *
 * This object decides **which** documents may be deleted; it never deletes anything itself. Two
 * rules make the decision safe to apply to a user's cloud folder:
 *
 * 1. **This app's automatic backups only.** [isPrunableName] accepts exactly the names
 *    `BackupHelper.backupFile` generates for an automatic export — `otp_accounts_<uuid>.json.aes`
 *    (and the `.json.gpg` an automatic OpenPGP export writes). A user's own export, a hand-named
 *    file, a restored backup or anything else in the folder is never a candidate, whatever its
 *    retention count says.
 * 2. **The newest [keep] snapshots are never touched.** [plan] returns only the documents older
 *    than that set, and it returns them oldest first so a partially completed run still leaves the
 *    folder in a sane state (the failure mode is "less was pruned", never "the newest copy went").
 *
 * Anything that cannot be ordered is kept: a candidate with an unknown timestamp is not pruned,
 * because guessing its age could delete the newest copy. Duplicate provider identities are kept. [keep] of zero disables pruning entirely —
 * the setting's documented meaning — without changing existing settings.
 */
object BackupRetention {
    /** Automatic-backup name prefix, shared with [BackupFileNames]. */
    const val AUTO_NAME_PREFIX = "otp_accounts_"

    /** The retain-N choices the setting offers; `0` means "keep everything". */
    val CHOICES: List<Int> = listOf(0, 5, 10, 20, 50)

    const val DEFAULT_KEEP = 0

    /** Largest retain-N value accepted from storage, so a corrupt preference cannot mean "all". */
    const val MAX_KEEP = 100

    /** One candidate document in the backup folder. */
    class Candidate(val name: String, val lastModified: Long)

    /**
     * True when [name] is an automatic encrypted or OpenPGP backup this app wrote, e.g.
     * `otp_accounts_3f2a….json.aes`. A dated manual export (`otp_accounts_2026-11-06_12-00-00.json.aes`)
     * is deliberately **not** matched: it has no UUID and a user may be keeping it on purpose.
     */
    @JvmStatic
    fun isPrunableName(name: String?): Boolean = name != null &&
        com.fairuniversal.offlineotp.domain.AutomaticBackupRetention().providerName(name)

    @JvmStatic
    fun plan(documents: List<Candidate>, keep: Int): List<String> =
        com.fairuniversal.offlineotp.domain.AutomaticBackupRetention().providerPlan(documents.map {
            com.fairuniversal.offlineotp.domain.BackupRetentionCandidate(it.name, it.lastModified)
        }, keep)

    /** [keep] clamped to the offered choices; an out-of-range stored value falls back to the default. */
    @JvmStatic
    fun normalizeKeep(stored: Int): Int = when {
        stored in 1..MAX_KEEP -> stored
        stored == 0 -> 0
        else -> DEFAULT_KEEP
    }
}
