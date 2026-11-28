package com.fairuniversal.offlineotp.domain

/** Secret-free policy. Native timers, provider grants, key derivation and file I/O stay in adapters. */
class BackupAutomationPolicy {
    fun valid(minutes: Int, keep: Int, passwordUnits: Int, passwordsMatch: Boolean): Boolean =
        timing(minutes, keep) && passwordUnits in 1..EntryMetadata.MAX_TEXT_CHARS && passwordsMatch
    fun timing(minutes: Int, keep: Int): Boolean = minutes in 1..1440 && keep in 1..100
    fun resume(unlocked: Boolean, requiresSigning: Boolean, signingAvailable: Boolean): String = when {
        !unlocked -> "LOCKED"
        requiresSigning && !signingAvailable -> "SIGNING_REQUIRED"
        else -> "READY"
    }
    fun validRecipients(keys: List<ByteArray>): Boolean = keys.size in 1..64 && keys.all { it.isNotEmpty() } &&
        keys.sumOf { it.size.toLong() } <= 32768 && keys.indices.none { i -> (0 until i).any { keys[i].contentEquals(keys[it]) } }
    fun canDropReverted(current: Boolean, before: String?, digest: String?, olderInFlight: Boolean): Boolean =
        current && before != null && before == digest && !olderInFlight
    fun clearPriorDigest(completedCurrent: Boolean, hasPriorDigest: Boolean): Boolean = !completedCurrent && hasPriorDigest
}

/** Main-executor confined; metadata only. Ticket/generation checks reject late completion after disable/rekey.
 * A save during work requests one catch-up; further saves do not postpone the original deadline.
 * Negative delays mean no scheduling. Native owners must separately revoke detached work before write.
 */
class AutomaticBackupSchedule {
    private var serial = 0L
    private var generation = 0L
    private var interval = 0L
    private var ticket = 0L
    var enabled = false; private set
    var busy = false; private set
    var pendingChange = false; private set
    fun enable(generation: Long, minutes: Int): Boolean {
        if (minutes !in 1..1440) return false
        disable(); this.generation = generation; interval = minutes * 60000L; enabled = true
        return true
    }
    fun changed(currentGeneration: Long, ready: Boolean): Long {
        if (!current(currentGeneration, ready)) { disable(); return -1 }
        if (pendingChange) return -1
        pendingChange = true
        return if (busy) -1 else 1000
    }
    fun begin(currentGeneration: Long, ready: Boolean): Long {
        if (!current(currentGeneration, ready)) { disable(); return 0 }
        if (busy) return 0
        busy = true; pendingChange = false; serial++; ticket = serial
        return ticket
    }
    fun complete(ticket: Long, currentGeneration: Long, ready: Boolean, success: Boolean): Long {
        if (!enabled || !busy || ticket != this.ticket) return -1
        if (!current(currentGeneration, ready) || !success) { disable(); return -1 }
        busy = false
        return if (pendingChange) 1000 else interval
    }
    private fun current(generation: Long, ready: Boolean) = enabled && ready && this.generation == generation
    fun disable() { enabled = false; busy = false; pendingChange = false; ticket = 0; serial++ }
    override fun toString() = "AutomaticBackupSchedule(redacted)"
}

class BackupRetentionCandidate(val name: String, val modified: Long)
/** Supports existing provider UUID names and desktop/Apple managed names without renaming files. */
class AutomaticBackupRetention {
    fun providerName(name: String): Boolean {
        if (!name.startsWith("otp_accounts_")) return false
        val suffix = listOf(".json.aes", ".json.gpg").firstOrNull { name.endsWith(it) } ?: return false
        return uuid(name.substring(13, name.length - suffix.length), false)
    }
    fun managedName(name: String, fileExtension: String): Boolean {
        if (fileExtension !in listOf("aes", "pgp", "offlineotp")) return false
        if (!name.startsWith("auto-") || !name.endsWith(".$fileExtension")) return false
        val stem = name.removeSuffix(".$fileExtension")
        return stem.length == 58 && stem.substring(5, 21).all { it in '0'..'9' } && stem[21] == '-' && uuid(stem.substring(22), true)
    }
    private fun uuid(text: String, lowerOnly: Boolean): Boolean = text.length == 36 && text.indices.all { i ->
        if (i in listOf(8, 13, 18, 23)) text[i] == '-'
        else text[i] in '0'..'9' || text[i] in 'a'..'f' || (!lowerOnly && text[i] in 'A'..'F')
    }
    fun managedPlan(names: List<String>, keep: Int, fileExtension: String): List<String> {
        if (keep !in 1..100) return emptyList()
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return names.filter { it !in duplicates && managedName(it, fileExtension) }.sortedDescending().drop(keep).reversed()
    }
    fun providerPlan(documents: List<BackupRetentionCandidate>, keep: Int): List<String> {
        if (keep !in 1..100) return emptyList()
        // Ambiguous identities are preserved, never resolved to an arbitrary provider object.
        val duplicates = documents.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        val candidates = documents.filter { providerName(it.name) && it.name !in duplicates }
        val newest = candidates.sortedWith(compareByDescending<BackupRetentionCandidate> {
            if (it.modified > 0) it.modified else Long.MAX_VALUE
        }.thenBy { it.name })
        // Unknown dates remain protected even when there are more of them than keep.
        return newest.drop(keep).filter { it.modified > 0 }.sortedWith(compareBy<BackupRetentionCandidate> { it.modified }.thenBy { it.name }).map { it.name }
    }
}
