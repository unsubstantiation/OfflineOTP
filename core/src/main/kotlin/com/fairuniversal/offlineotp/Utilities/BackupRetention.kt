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
 * because guessing its age could delete the newest copy. [keep] of zero disables pruning entirely —
 * the setting's documented meaning — even though nothing else would be authenticated.
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
    fun isPrunableName(name: String?): Boolean {
        if (name == null) return false
        if (!name.startsWith(AUTO_NAME_PREFIX)) return false
        val suffix = when {
            name.endsWith(".json.aes") -> ".json.aes"
            name.endsWith(".json.gpg") -> ".json.gpg"
            else -> return false
        }
        val stem = name.substring(AUTO_NAME_PREFIX.length, name.length - suffix.length)
        return isUuid(stem)
    }

    /** The 8-4-4-4-12 hexadecimal form `UUID.randomUUID().toString()` writes, upper or lower case. */
    private fun isUuid(stem: String): Boolean {
        if (stem.length != 36) return false
        for (i in 0 until 36) {
            val c = stem[i]
            val dash = i == 8 || i == 13 || i == 18 || i == 23
            if (dash) {
                if (c != '-') return false
            } else if (!c.isDigit() && c !in 'a'..'f' && c !in 'A'..'F') {
                return false
            }
        }
        return true
    }

    /**
     * The names to delete: automatic backups older than the newest [keep], oldest first.
     *
     * [lastModified] is the provider's timestamp, which a hostile provider can report as anything —
     * so this only ever *chooses less to delete* when the data is odd (ties keep everything, an
     * unset timestamp keeps the file).
     */
    @JvmStatic
    fun plan(documents: List<Candidate>, keep: Int): List<String> {
        if (keep <= 0) return emptyList()
        val candidates = documents.filter { isPrunableName(it.name) }
        if (candidates.size <= keep) return emptyList()
        // Newest first; a document whose timestamp is unknown (<= 0) counts as newest, so it is
        // always inside the kept set rather than silently deleted.
        val newestFirst = candidates.sortedWith(
            compareByDescending<Candidate> { if (it.lastModified > 0L) it.lastModified else Long.MAX_VALUE }
                .thenBy { it.name })
        val doomed = newestFirst.drop(keep)
        // Oldest first, so an interrupted run deleted the least useful copies first.
        return doomed.sortedWith(
            compareBy<Candidate> { if (it.lastModified > 0L) it.lastModified else Long.MAX_VALUE }
                .thenBy { it.name })
            .map { it.name }
    }

    /** [keep] clamped to the offered choices; an out-of-range stored value falls back to the default. */
    @JvmStatic
    fun normalizeKeep(stored: Int): Int = when {
        stored in 1..MAX_KEEP -> stored
        stored == 0 -> 0
        else -> DEFAULT_KEEP
    }
}
