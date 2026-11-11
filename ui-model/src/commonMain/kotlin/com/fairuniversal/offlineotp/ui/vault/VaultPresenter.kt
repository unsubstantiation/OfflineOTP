package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants

/**
 * The state a vault screen keeps between frames, in one place for every host.
 *
 * Shared selection and reveal state for hosts of the Compose vault screen.
 *
 * This class holds no toolkit, no clipboard and no persistence: those arrive as callbacks. It owns
 * only the decisions — the filter transitions ([VaultFilter]'s own helpers), the reveal set, the
 * digits-only copy rule and the ordering of "clear what is on screen, then let the host lock" — and
 * exposes them to a screen as [actions].
 *
 * [onChange] is the host's chance to re-render: every mutation calls it once, so a host that is not
 * observing this object (Compose, a Swing timer) still learns that a frame is stale.
 */
class VaultPresenter(
    private val projection: VaultProjection,
    initialFilter: VaultFilter,
    val strings: VaultStrings = VaultStrings.English,
    appearance: VaultAppearance = VaultAppearance(),
    /** Puts a code on the clipboard. Receives digits only; null leaves Copy doing nothing. */
    private val copyCode: ((String) -> Unit)? = null,
    /** Advances an HOTP counter and persists it. Null hides the action. */
    private val advanceCounter: ((VaultCardState) -> Unit)? = null,
    /** Opens the host's per-entry actions. Null hides the action. */
    private val entryActions: ((VaultCardState) -> Unit)? = null,
    /** Opens PIN input; the host validates and persists the eventual submission. */
    private val enterPin: ((VaultCardState) -> Unit)? = null,
    /** Requests confirmation; persistence and live-id checks remain host responsibilities. */
    private val deleteEntry: ((VaultCardState) -> Unit)? = null,
    /** Remembers the chosen order after the filter has changed. */
    private val sortSelected: ((Constants.SortMode) -> Unit)? = null,
    /** Locks the vault, after the revealed rows have already been forgotten. */
    private val lockVault: (() -> Unit)? = null,
    private val onChange: (() -> Unit)? = null,
    private val addEntry: (() -> Unit)? = null,
    private val editEntry: ((VaultCardState) -> Unit)? = null
) {

    /** The selection currently shown. Change it through the [actions], never by assigning here. */
    var filter: VaultFilter = initialFilter
        private set

    var appearance: VaultAppearance = appearance
        private set

    fun setCardLayout(layout: Constants.CardLayouts) {
        if (locked) return
        appearance = appearance.copy(cardLayout = layout)
        onChange?.invoke()
    }

    private val revealed = LinkedHashSet<Long>()
    private var locked = false

    init {
        projection.applyFilter(initialFilter)
    }

    /** One frame at [nowMillis]: the selection applied, revealed content only for revealed ids. */
    fun frame(nowMillis: Long): VaultUiState =
        if (locked) VaultUiState() else projection.snapshot(filter, nowMillis, revealed, appearance, strings)

    /** Applies [next] to the shared projection and remembers it as the current selection. */
    fun applyFilter(next: VaultFilter) {
        if (locked) return
        filter = next
        projection.applyFilter(next)
        onChange?.invoke()
    }

    /** Reveals or hides one row. A revealed row's content lives in the next frame only. */
    fun toggleReveal(id: Long) {
        if (locked) return
        if (!revealed.remove(id)) revealed.add(id)
        onChange?.invoke()
    }

    /** After a host commits deletion, discard reveal state and rebuild the current selection. */
    fun entryRemoved(id: Long) {
        if (locked) return
        revealed.remove(id)
        applyFilter(filter)
    }

    /** Whether [id]'s protected content is on screen. */
    fun isRevealed(id: Long): Boolean = id in revealed

    /**
     * Terminal for this presenter: clear display state before handing lock to the host. Old
     * callbacks become inert and frames become empty even if the host's cleanup throws. A new
     * unlocked session must create a new presenter. Call on the host's UI thread.
     */
    fun lock() {
        if (locked) return
        locked = true
        revealed.clear()
        filter = VaultFilter(combination = filter.combination)
        try {
            lockVault?.invoke()
        } finally {
            onChange?.invoke()
        }
    }

    /** The screen's callbacks, wired to this presenter and to the host's optional hooks. */
    fun actions(copyEntry: ((VaultCardState) -> Unit)? = null,
                moveEntry: ((VaultCardState, Int) -> Unit)? = null,
                configureAutoLock: (() -> Unit)? = null,
                exportEncrypted: (() -> Unit)? = null,
                restoreEncrypted: (() -> Unit)? = null,
                importUri: (() -> Unit)? = null,
                importPasswords: (() -> Unit)? = null,
                importEmail: (() -> Unit)? = null,
                viewEmailDetails: ((VaultCardState) -> Unit)? = null,
                configureLayout: (() -> Unit)? = null,
                importLinScp: (() -> Unit)? = null): VaultActions = VaultActions(
        onImportLinScp = importLinScp,
        onConfigureLayout = configureLayout,
        onViewEmailDetails = viewEmailDetails?.let { view -> { card -> if (card.id in revealed && card.kind == VaultEntryKind.EMAILS) view(card) } },
        onImportEmail = importEmail,
        onImportPasswords = importPasswords,
        onImportUri = importUri,
        onRestoreEncrypted = restoreEncrypted,
        onExportEncrypted = exportEncrypted,
        onConfigureAutoLock = configureAutoLock,
        onMoveUp = moveEntry?.let { move -> { card -> move(card, -1) } },
        onMoveDown = moveEntry?.let { move -> { card -> move(card, 1) } },
        onAddEntry = addEntry,
        onEditEntry = editEntry?.let { edit -> { card -> if (card.id in revealed) edit(card) } },
        onCopyToken = { card -> if (copyEntry != null) copyEntry(card) else copyToken(card) },
        onToggleReveal = { card -> toggleReveal(card.id) },
        onNextCounter = advanceCounter,
        onEntryActions = entryActions,
        onEnterPin = enterPin,
        onDelete = deleteEntry,
        onQueryChange = { query -> applyFilter(filter.withQuery(query)) },
        onSelectCategory = { category -> applyFilter(filter.withCategory(category)) },
        onToggleTag = { tag -> applyFilter(filter.withTagToggled(tag)) },
        onToggleUntagged = { applyFilter(filter.withUntaggedToggled()) },
        onClearFilters = { applyFilter(filter.clearedFilters()) },
        onSelectSort = { mode ->
            if (!locked) {
                applyFilter(filter.withSortMode(mode))
                sortSelected?.invoke(mode)
            }
        },
        onLock = { lock() }
    ).guarded { !locked }

    /**
     * Copies the digits only: the grouping the card shows is a rendering detail, and a pasted
     * `123 456` is rejected by the sites that expect `123456`. Nothing is copied for a row without a
     * code, and an mOTP entry still waiting for its PIN has no copyable token either.
     */
    private fun copyToken(card: VaultCardState) {
        if (locked) return
        val copy = copyCode ?: return
        val token = card.token ?: return
        if (!card.copyable) return
        copy(token.replace(" ", ""))
    }

    // The selection is display state; keep filters and ids out of diagnostics, but they are not
    // secrets, so the shape is enough.
    override fun toString(): String =
        "VaultPresenter(category=${filter.category}, revealed=${revealed.size}, " +
            "filtered=${filter.hasQuery || filter.hasTagFilter})"
}
