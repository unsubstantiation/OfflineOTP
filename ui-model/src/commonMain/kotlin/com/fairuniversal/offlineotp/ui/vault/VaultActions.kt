package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation

/**
 * Everything a vault screen can ask its host to do.
 *
 * The screen never performs an action itself: copying, revealing, advancing an HOTP counter,
 * editing, changing the filter and locking are host decisions, because only the host owns the
 * session, the clipboard, the settings store and the persistence task. Callbacks take a
 * [VaultCardState] whose [VaultCardState.id] is the entry's stable list id, never a row position,
 * so a frame that arrives after a reorder, deletion or lock cannot act on a different account.
 */
class VaultActions(
    /** Copy the entry's current code; the host decides whether that is allowed. */
    val onCopyToken: (VaultCardState) -> Unit = {},
    /** Reveal or hide this row's protected content. */
    val onToggleReveal: (VaultCardState) -> Unit = {},
    /**
     * Advance an HOTP counter, then refresh the list. Null hides the action: a host that cannot
     * persist a counter yet must not offer a button that silently drops it.
     */
    val onNextCounter: ((VaultCardState) -> Unit)? = null,
    /**
     * Open the host's per-entry actions (edit, tags, thumbnail, delete, export). Null hides the
     * action, which is how a partially migrated host avoids advertising what it cannot do.
     */
    val onEntryActions: ((VaultCardState) -> Unit)? = null,
    /** Request a PIN for an mOTP account. Null hides the input action. */
    val onEnterPin: ((VaultCardState) -> Unit)? = null,
    /** Request confirmed deletion; null hides Delete for read-only hosts. */
    val onDelete: ((VaultCardState) -> Unit)? = null,
    val onTogglePrivacy: ((VaultCardState) -> Unit)? = null,
    val onCardTap: ((VaultCardState) -> Unit)? = null,
    val onCardDoubleTap: ((VaultCardState) -> Unit)? = null,
    val onEditCounter: ((VaultCardState) -> Unit)? = null,
    val onMoveUp: ((VaultCardState) -> Unit)? = null,
    val onMoveDown: ((VaultCardState) -> Unit)? = null,
    val onQueryChange: (String) -> Unit = {},
    val onSelectCategory: (EntryListPresentation.Category) -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onToggleUntagged: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onSelectSort: (Constants.SortMode) -> Unit = {},
    val onLock: () -> Unit = {},
    val onAddEntry: (() -> Unit)? = null,
    val onEditEntry: ((VaultCardState) -> Unit)? = null,
    val onConfigureAutoLock: (() -> Unit)? = null,
    val onExportEncrypted: (() -> Unit)? = null,
    val onRestoreEncrypted: (() -> Unit)? = null
) {
    /**
     * Check host liveness at invocation time, not when callbacks are captured by a frame.
     * Optional actions remain null so unavailable features stay hidden.
     */
    fun guarded(isActive: () -> Boolean): VaultActions = VaultActions(
        onRestoreEncrypted = onRestoreEncrypted?.let { action -> { if (isActive()) action() } },
        onExportEncrypted = onExportEncrypted?.let { action -> { if (isActive()) action() } },
        onConfigureAutoLock = onConfigureAutoLock?.let { action -> { if (isActive()) action() } },
        onEditEntry = onEditEntry?.let { action -> { if (isActive()) action(it) } },
        onAddEntry = onAddEntry?.let { action -> { if (isActive()) action() } },
        onCopyToken = { if (isActive()) onCopyToken(it) },
        onToggleReveal = { if (isActive()) onToggleReveal(it) },
        onNextCounter = onNextCounter?.let { action -> { if (isActive()) action(it) } },
        onEntryActions = onEntryActions?.let { action -> { if (isActive()) action(it) } },
        onEnterPin = onEnterPin?.let { action -> { if (isActive()) action(it) } },
        onDelete = onDelete?.let { action -> { if (isActive()) action(it) } },
        onTogglePrivacy = onTogglePrivacy?.let { action -> { if (isActive()) action(it) } },
        onCardTap = onCardTap?.let { action -> { if (isActive()) action(it) } },
        onCardDoubleTap = onCardDoubleTap?.let { action -> { if (isActive()) action(it) } },
        onEditCounter = onEditCounter?.let { action -> { if (isActive()) action(it) } },
        onMoveUp = onMoveUp?.let { action -> { if (isActive()) action(it) } },
        onMoveDown = onMoveDown?.let { action -> { if (isActive()) action(it) } },
        onQueryChange = { if (isActive()) onQueryChange(it) },
        onSelectCategory = { if (isActive()) onSelectCategory(it) },
        onToggleTag = { if (isActive()) onToggleTag(it) },
        onToggleUntagged = { if (isActive()) onToggleUntagged() },
        onClearFilters = { if (isActive()) onClearFilters() },
        onSelectSort = { if (isActive()) onSelectSort(it) },
        onLock = { if (isActive()) onLock() }
    )

    // Lambdas close over host state; keep them out of any diagnostic text.
    override fun toString(): String = "VaultActions"
}
