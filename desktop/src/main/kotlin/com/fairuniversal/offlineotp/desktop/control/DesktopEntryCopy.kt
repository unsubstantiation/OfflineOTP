package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.VaultCardState
import java.util.IdentityHashMap

/** Frame/object-bound copy authority. Displayed token strings are never used as copy payloads. */
class DesktopEntryCopy(
    private val state: DesktopVaultState,
    private val editor: EntryEditor,
    private val clipboard: ExpiringClipboard,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    enum class Result { COPIED, UNAVAILABLE, SAVE_FAILED, CLIPBOARD_FAILED }
    class Frame internal constructor(internal val targets: IdentityHashMap<VaultCardState, Entry>)
    class Request internal constructor(internal val target: Entry, val fields: List<EntryEditor.CopyField>) {
        val entryId: Long get() = target.getListId()
    }
    var pending: Request? = null
        private set
    private var closed = false

    fun bind(cards: List<VaultCardState>): Frame {
        val targets = IdentityHashMap<VaultCardState, Entry>()
        if (!closed && !state.isLocked) {
            val entries = state.snapshot().associateBy { it.getListId() }
            cards.forEach { card -> entries[card.id]?.let { targets[card] = it } }
        }
        return Frame(targets)
    }

    fun open(frame: Frame, card: VaultCardState, revealed: Boolean): Request? {
        pending = null
        val target = frame.targets[card] ?: return null
        if (!revealed || !live(target)) return null
        val fields = when {
            target.isSshKey() -> buildList {
                val keys = target.getSshKeys()
                if (keys.publicKey.isNotEmpty()) add(EntryEditor.CopyField.SSH_PUBLIC_KEY)
                if (keys.privateKey.isNotEmpty()) add(EntryEditor.CopyField.SSH_PRIVATE_KEY)
                if (!target.getSshConnection()?.password.isNullOrEmpty()) add(EntryEditor.CopyField.SSH_PASSWORD)
            }
            target.isPassword() -> listOf(EntryEditor.CopyField.PASSWORD)
            target.isTimeBased() || target.isCounterBased() || target.isNote() -> listOf(EntryEditor.CopyField.TOKEN_OR_NOTE)
            else -> emptyList()
        }
        if (fields.isEmpty()) return null
        return Request(target, fields).also { pending = it }
    }

    private fun live(target: Entry) = !closed && !state.isLocked && state.snapshot().any { it === target }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null; clipboard.close() }

    fun copy(request: Request, field: EntryEditor.CopyField, revealed: Boolean): Result {
        if (pending !== request) return Result.UNAVAILABLE
        if (!revealed || !live(request.target)) {
            pending = null
            return Result.UNAVAILABLE
        }
        if (field !in request.fields) return Result.UNAVAILABLE
        pending = null
        val target = request.target
        // Refresh live time codes at the instant of copy, even if the displayed frame is old.
        if (target.isTimeBased()) {
            try { target.updateOTPAt(nowMillis() / 1000L, false) } catch (_: Exception) { return Result.UNAVAILABLE }
        }
        return when (val copied = editor.copyRevealed(target, field, true)) {
            EntryEditor.TokenCopyResult.Unavailable -> Result.UNAVAILABLE
            EntryEditor.TokenCopyResult.PersistenceFailed -> Result.SAVE_FAILED
            is EntryEditor.TokenCopyResult.Copied ->
                if (clipboard.copy(copied.token)) Result.COPIED else Result.CLIPBOARD_FAILED
        }
    }
}
