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
    class Request internal constructor(
        internal val target: Entry,
        val fields: List<EntryEditor.CopyField>,
        val passwordChoiceLabels: List<String> = emptyList()
    ) {
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

    fun open(frame: Frame, card: VaultCardState, revealed: Boolean = false): Request? {
        @Suppress("UNUSED_PARAMETER") val ignoredReveal = revealed
        pending = null
        val target = frame.targets[card] ?: return null
        if (!live(target)) return null
        val fields = when {
            target.isSshKey() -> buildList {
                val keys = target.getSshKeys()
                if (keys.publicKey.isNotEmpty()) add(EntryEditor.CopyField.SSH_PUBLIC_KEY)
                if (keys.privateKey.isNotEmpty()) add(EntryEditor.CopyField.SSH_PRIVATE_KEY)
                if (!target.getSshConnection()?.password.isNullOrEmpty()) add(EntryEditor.CopyField.SSH_PASSWORD)
            }
            target.isEmail() -> buildList {
                val email = target.getEmailAccount()
                if (email.account.optString("password").orEmpty().isNotEmpty()) add(EntryEditor.CopyField.EMAIL_INCOMING_PASSWORD)
                if (email.identity.optString("password").orEmpty().isNotEmpty()) add(EntryEditor.CopyField.EMAIL_OUTGOING_PASSWORD)
            }
            target.isPassword() -> buildList {
                if (target.getPasswordFields().value("password").isNotEmpty()) add(EntryEditor.CopyField.PASSWORD)
                if (target.getPasswordFields().value("username").isNotEmpty()) add(EntryEditor.CopyField.PASSWORD_USERNAME)
            }
            target.isTimeBased() || target.isCounterBased() || target.isNote() -> listOf(EntryEditor.CopyField.TOKEN_OR_NOTE)
            else -> emptyList()
        }
        if (fields.isEmpty()) return null
        return Request(target, fields).also { pending = it }
    }

    fun openAccount(frame: Frame, card: VaultCardState): Request? {
        pending = null
        val target = frame.targets[card] ?: return null
        if (!live(target) || !target.hasCopyableAccount()) return null
        return Request(target, listOf(EntryEditor.CopyField.ACCOUNT)).also { pending = it }
    }

    fun openPassword(frame: Frame, card: VaultCardState): Request? {
        pending = null
        val target = frame.targets[card] ?: return null
        if (!live(target) || !target.hasCopyablePassword()) return null
        val choices = target.passwordChoicesToCopy()
        if (choices.isEmpty()) return null
        return if (choices.map { it.second }.distinct().size == 1) {
            Request(target, listOf(EntryEditor.CopyField.ENTRY_PASSWORD)).also { pending = it }
        } else {
            Request(target, emptyList(), choices.map { it.first }).also { pending = it }
        }
    }

    private fun live(target: Entry) = !closed && !state.isLocked && state.snapshot().any { it === target }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null; clipboard.close() }

    fun copy(request: Request, field: EntryEditor.CopyField, revealed: Boolean = false, active: () -> Boolean = { true }): Result {
        @Suppress("UNUSED_PARAMETER") val ignoredReveal = revealed
        if (pending !== request) return Result.UNAVAILABLE
        if (!active() || pending !== request) return Result.UNAVAILABLE
        if (!live(request.target)) {
            pending = null
            return Result.UNAVAILABLE
        }
        if (field !in request.fields) return Result.UNAVAILABLE
        pending = null
        val target = request.target
        // Refresh live time codes at the instant of copy, even if the displayed frame is old.
        if (field == EntryEditor.CopyField.TOKEN_OR_NOTE && target.isTimeBased()) {
            try { target.updateOTPAt(nowMillis() / 1000L, false) } catch (_: Exception) { return Result.UNAVAILABLE }
        }
        val copied = editor.copyRevealed(target, field, true)
        // Usage persistence may have blocked the UI past its deadline. Never publish the
        // captured secret after lock, target replacement or another copy dialog opening.
        if (!active() || !live(target) || pending != null) return Result.UNAVAILABLE
        return when (copied) {
            EntryEditor.TokenCopyResult.Unavailable -> Result.UNAVAILABLE
            EntryEditor.TokenCopyResult.PersistenceFailed -> Result.SAVE_FAILED
            is EntryEditor.TokenCopyResult.Copied ->
                if (clipboard.copy(copied.token)) Result.COPIED else Result.CLIPBOARD_FAILED
        }
    }

    fun copyPasswordChoice(request: Request, index: Int, active: () -> Boolean = { true }): Result {
        if (pending !== request || !active() || pending !== request) return Result.UNAVAILABLE
        if (!live(request.target) || index !in request.passwordChoiceLabels.indices) {
            pending = null
            return Result.UNAVAILABLE
        }
        pending = null
        val target = request.target
        val copied = editor.copyPasswordChoice(target, index)
        if (!active() || !live(target) || pending != null) return Result.UNAVAILABLE
        return when (copied) {
            EntryEditor.TokenCopyResult.Unavailable -> Result.UNAVAILABLE
            EntryEditor.TokenCopyResult.PersistenceFailed -> Result.SAVE_FAILED
            is EntryEditor.TokenCopyResult.Copied ->
                if (clipboard.copy(copied.token)) Result.COPIED else Result.CLIPBOARD_FAILED
        }
    }
}
