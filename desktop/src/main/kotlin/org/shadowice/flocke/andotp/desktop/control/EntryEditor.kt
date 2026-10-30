package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry

/**
 * Applies entry mutations to the unlocked [DesktopVaultState] and persists each successful one
 * through the [VaultController]. All methods fail closed: a rejected mutation never triggers a
 * vault write, and a failed persistence is reported rather than silently dropped.
 */
class EntryEditor(
    private val controller: VaultController,
    private val state: DesktopVaultState
) {
    /** Outcome of a clipboard copy request; the Swing shell only touches the clipboard on [Copied]. */
    sealed class TokenCopyResult {
        data class Copied(val token: String) : TokenCopyResult() {
            override fun toString(): String = "TokenCopyResult.Copied(redacted)"
        }
        /** No real token is cached yet, or the entry shows the mOTP no-PIN placeholder. */
        object Unavailable : TokenCopyResult()
        /** The usage metadata was updated in memory but the vault write failed. */
        object PersistenceFailed : TokenCopyResult()
    }

    /** On a failed write, keep the item concealed in memory even if disk still says revealed. */
    fun setDetailsVisible(entry: Entry, visible: Boolean): Boolean {
        if (state.snapshot().none { it === entry }) return false
        entry.setDetailsVisible(visible)
        if (persist()) return true
        entry.setDetailsVisible(false)
        return false
    }

    fun add(entry: Entry): Boolean {
        val proposed = state.snapshot()
        if (proposed.contains(entry)) return false
        proposed.add(entry)
        if (!controller.save(state.key, proposed)) return false
        return state.add(entry)
    }

    /** Saves a deduplicated batch once before publishing it to the UI. Consumes incoming entries. */
    fun importEntries(incoming: List<Entry>): Int? {
        val proposed = state.snapshot()
        val seen = HashSet(proposed)
        val accepted = ArrayList<Entry>()
        try {
            for (entry in incoming) {
                entry.validate()
                if (seen.add(entry)) { proposed.add(entry); accepted.add(entry) }
            }
            if (accepted.isEmpty()) return 0
            if (proposed.size > org.shadowice.flocke.andotp.Utilities.InputPolicy.MAX_ENTRIES ||
                !controller.save(state.key, proposed)) return null
            for (entry in accepted) check(state.add(entry))
            return accepted.size
        } finally {
            // Only entries published into the state are retained.
            val owned = state.snapshot()
            incoming.forEach { entry -> if (owned.none { it === entry }) entry.destroy() }
        }
    }

    /** Persist tag edits atomically; a failed validation or write restores the live tags. */
    fun setTags(entry: Entry, tags: List<String>): Boolean {
        if (!entry.areDetailsVisible() || state.snapshot().none { it === entry }) return false
        val previous = ArrayList(entry.getTags())
        try {
            entry.setTags(ArrayList(tags))
            entry.validate()
            if (!persist()) {
                entry.setTags(previous)
                return false
            }
        } catch (_: IllegalArgumentException) {
            entry.setTags(previous)
            return false
        }
        state.refreshProjection()
        return true
    }

    fun copyPassword(entry: Entry): TokenCopyResult {
        if (!entry.areDetailsVisible() || !entry.isPassword()) return TokenCopyResult.Unavailable
        val password = entry.getPasswordFields().value("password")
        return if (password.isEmpty()) TokenCopyResult.Unavailable else recordCopy(entry, password)
    }

    fun replace(existing: Entry, replacement: Entry): Boolean {
        val proposed = state.snapshot()
        val index = proposed.indexOf(existing)
        if (index < 0 || proposed.indices.any { it != index && proposed[it] == replacement }) return false
        proposed[index] = replacement
        if (!controller.save(state.key, proposed)) return false
        return state.replace(existing, replacement)
    }

    fun removeAt(position: Int): Boolean {
        val proposed = state.snapshot()
        if (position !in proposed.indices) return false
        proposed.removeAt(position)
        if (!controller.save(state.key, proposed)) return false
        return state.removeAt(position) != null
    }

    /**
     * Records one clipboard use of [entry]'s current token, mirroring the Android
     * `updateLastUsedAndFrequency` path: stamp last-used, increment the usage frequency, re-sort
     * the projection when it follows usage, and persist. The token itself is returned for the UI
     * layer to place on the clipboard; this headless class never touches AWT.
     */
    fun copyToken(entry: Entry): TokenCopyResult {
        if (!entry.areDetailsVisible() || (!entry.isNote() && !entry.hasCopyableToken())) return TokenCopyResult.Unavailable
        val token = if (entry.isNote()) entry.getNote() else entry.getCurrentOTP()!!
        return recordCopy(entry, token)
    }

    /** Copy only the explicitly chosen SSH key; never concatenate or expose the other field. */
    fun copySshKey(entry: Entry, privateKey: Boolean): TokenCopyResult {
        if (!entry.areDetailsVisible() || !entry.isSshKey()) return TokenCopyResult.Unavailable
        val keys = entry.getSshKeys()
        val value = if (privateKey) keys.privateKey else keys.publicKey
        if (value.isBlank()) return TokenCopyResult.Unavailable
        return recordCopy(entry, value)
    }

    private fun recordCopy(entry: Entry, token: String): TokenCopyResult {
        entry.setLastUsed(System.currentTimeMillis())
        entry.setUsedFrequency(entry.getUsedFrequency() + 1)
        state.presentation.resortAfterUsage()
        return if (persist()) TokenCopyResult.Copied(token) else TokenCopyResult.PersistenceFailed
    }

    /**
     * Explicitly advances an HOTP counter — the desktop counterpart of tapping the Android
     * counter chip — recomputes the cached token and persists. Returns false for non-HOTP
     * entries, at the [Long] counter ceiling, or when the vault write fails.
     */
    fun advanceHotp(entry: Entry): Boolean {
        if (!entry.areDetailsVisible() || !entry.isCounterBased()) return false
        if (entry.getCounter() >= Long.MAX_VALUE - 1) return false
        entry.setCounter(entry.getCounter() + 1)
        entry.updateOTP(false)
        return persist()
    }

    private fun persist(): Boolean = controller.save(state.key, state.snapshot())
}
