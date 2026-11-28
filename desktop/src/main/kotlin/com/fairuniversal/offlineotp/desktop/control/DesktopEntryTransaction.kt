package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson
import java.security.MessageDigest
import com.fairuniversal.offlineotp.Utilities.EntryUsage
import com.fairuniversal.offlineotp.Utilities.InputPolicy

/**
 * UI-thread save-before-adopt entry transaction used by Compose deletion/counter confirmation.
 * The host must prevent other mutations while busy and close this owner on lock/disposal.
 * Lock is mandatory on abandonment/expiry: the disk may already contain the candidate.
 */
class DesktopEntryTransaction(private val state: DesktopVaultState, private val save: DesktopDetachedSave,
    private val lockSession: () -> Unit) : AutoCloseable {
    enum class Kind { DELETE, ADVANCE_COUNTER, SET_PIN, ADD, REPLACE }
    enum class Result { DELETED, COUNTER_ADVANCED, PIN_SAVED, ADDED, REPLACED, SAVE_FAILED }
    private class Baseline(val entries: ArrayList<Entry>) {
        private val digests = ArrayList<ByteArray>()
        init {
            try { entries.forEach { digests.add(fingerprint(it)) } }
            catch (error: Exception) { destroy(); throw error }
        }
        fun matches(now: ArrayList<Entry>): Boolean = try {
            now.size == entries.size && now.indices.all { index ->
                if (now[index] !== entries[index]) false else {
                    val current = fingerprint(now[index])
                    try { MessageDigest.isEqual(digests[index], current) } finally { current.fill(0) }
                }
            }
        } catch (_: Exception) { false }
        fun destroy() { digests.forEach { it.fill(0) }; digests.clear(); entries.clear() }
        companion object {
            private fun fingerprint(entry: Entry): ByteArray {
                val bytes = VaultJson.entriesToString(listOf(entry)).toByteArray(Charsets.UTF_8)
                return try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
            }
        }
    }
    private var baseline: Baseline? = null
    private var ownedPin: CharArray? = null
    private var ownedDraft: Entry? = null
    private fun clearBaseline() {
        baseline?.destroy(); baseline = null
        ownedPin?.fill('\u0000'); ownedPin = null
        ownedDraft?.destroy(); ownedDraft = null
    }
    private var closed = false
    private var pending = false
    val busy get() = pending

    /** Copies the caller's draft; caller retains ownership on every outcome. */
    fun submitNew(draft: Entry, active: () -> Boolean, completed: (Result) -> Unit): Boolean {
        if (closed || pending || state.isLocked) return false
        val owned = try { draft.detachedCopy() } catch (_: Exception) { return false }
        try { owned.validate() } catch (_: Exception) { owned.destroy(); return false }
        ownedDraft = owned
        val accepted = submit(owned, Kind.ADD, active, completed)
        if (!accepted) { ownedDraft?.destroy(); ownedDraft = null }
        return accepted
    }

    /** Copies the edited entry; target must still be the exact live instance. */
    fun submitReplacement(target: Entry, replacement: Entry, active: () -> Boolean, completed: (Result) -> Unit): Boolean {
        if (closed || pending || state.isLocked) return false
        val owned = try { replacement.detachedCopy() } catch (_: Exception) { return false }
        owned.preserveExtensionFieldsFrom(target)
        try { owned.validate() } catch (_: Exception) { owned.destroy(); return false }
        ownedDraft = owned
        val accepted = submit(target, Kind.REPLACE, active, completed)
        if (!accepted) { ownedDraft?.destroy(); ownedDraft = null }
        return accepted
    }

    /** Consumes pin on every outcome; immutable Entry/JSON PIN strings cannot be zeroized. */
    fun submitPin(target: Entry, pin: CharArray, active: () -> Boolean, completed: (Result) -> Unit): Boolean {
        if (closed || pending || state.isLocked || target.getType() != Entry.OTPType.MOTP || pin.isEmpty() ||
            pin.size > InputPolicy.MAX_TAG_CHARS || runCatching { InputPolicy.text(String(pin), InputPolicy.MAX_TAG_CHARS) }.isFailure) {
            pin.fill('\u0000'); return false
        }
        ownedPin = pin
        val accepted = submit(target, Kind.SET_PIN, active, completed)
        if (!accepted) { pin.fill('\u0000'); ownedPin = null }
        return accepted
    }

    fun submit(target: Entry, active: () -> Boolean, completed: (Result) -> Unit): Boolean =
        submit(target, Kind.DELETE, active, completed)

    fun submit(target: Entry, kind: Kind, active: () -> Boolean, completed: (Result) -> Unit): Boolean {
        if (closed || pending || state.isLocked) return false
        if (kind == Kind.ADD && (ownedDraft !== target || state.snapshot().contains(target))) return false
        if (kind == Kind.REPLACE && (ownedDraft == null || state.snapshot().any { it !== target && it == ownedDraft })) return false
        if (kind == Kind.SET_PIN && (ownedPin == null || target.getType() != Entry.OTPType.MOTP)) return false
        if (kind == Kind.ADVANCE_COUNTER && (!target.isCounterBased() || target.getCounter() < 0 || target.getCounter() >= EntryUsage.MAX_COUNTER)) return false
        val counter = target.getCounter()
        val before = state.snapshot()
        val index = before.indexOfFirst { it === target }
        if (kind != Kind.ADD && index < 0) return false
        val captured = try { Baseline(before) } catch (_: Exception) { return false }
        baseline = captured
        fun unchanged(): Boolean = baseline === captured && captured.matches(state.snapshot())
        fun authorized(): Boolean {
            val live = try { active() } catch (_: Exception) { false }
            if (!live || state.isLocked || !unchanged()) {
                // Never leave a potentially stale live session available for a later save.
                try { if (pending && !state.isLocked) lockSession() } finally { clearBaseline() }
                return false
            }
            return !closed
        }
        pending = true
        val accepted = save.submitCandidate(state, ::authorized,
            prepare = { candidate ->
                when (kind) {
                    Kind.ADD -> candidate.add(target.detachedCopy())
                    Kind.REPLACE -> candidate.set(index, checkNotNull(ownedDraft).detachedCopy()).destroy()
                    Kind.DELETE -> candidate.removeAt(index).destroy()
                    Kind.ADVANCE_COUNTER -> check(EntryUsage.advanceHotp(candidate[index]))
                    Kind.SET_PIN -> { candidate[index].setPin(String(checkNotNull(ownedPin))); candidate[index].updateOTP(true) }
                }
            },
            completed = { saved ->
                if (!closed && pending) {
                    if (saved) {
                        if (!unchanged() || state.isLocked) {
                            try { lockSession() } finally { clearBaseline() }
                        } else {
                            val result = when (kind) {
                                Kind.ADD -> {
                                    check(state.add(target)) { "Candidate adoption failed" }
                                    ownedDraft = null // Live state now owns the entry.
                                    Result.ADDED
                                }
                                Kind.REPLACE -> {
                                    check(state.replace(target, checkNotNull(ownedDraft))) { "Replacement adoption failed" }
                                    ownedDraft = null
                                    Result.REPLACED
                                }
                                Kind.DELETE -> { state.removeAt(index)?.destroy(); Result.DELETED }
                                Kind.ADVANCE_COUNTER -> {
                                    target.setCounter(counter + 1)
                                    target.updateOTP(true)
                                    Result.COUNTER_ADVANCED
                                }
                                Kind.SET_PIN -> {
                                    target.setPin(String(checkNotNull(ownedPin)))
                                    target.updateOTP(true)
                                    Result.PIN_SAVED
                                }
                            }
                            pending = false
                            clearBaseline()
                            completed(result)
                        }
                    } else {
                        pending = false
                        clearBaseline()
                        completed(Result.SAVE_FAILED)
                    }
                }
            })
        if (!accepted) { pending = false; clearBaseline() }
        return accepted
    }

    override fun close() {
        if (closed) return
        closed = true
        save.close()
        clearBaseline()
        if (pending && !state.isLocked) lockSession()
        pending = false
    }
}
