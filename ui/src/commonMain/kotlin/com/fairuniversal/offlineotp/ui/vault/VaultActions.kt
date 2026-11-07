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
    val onQueryChange: (String) -> Unit = {},
    val onSelectCategory: (EntryListPresentation.Category) -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onToggleUntagged: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onSelectSort: (Constants.SortMode) -> Unit = {},
    val onLock: () -> Unit = {}
) {
    // Lambdas close over host state; keep them out of any diagnostic text.
    override fun toString(): String = "VaultActions"
}
