package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.ui.portable.PortableCardDisplaySelection
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout
import com.fairuniversal.offlineotp.ui.portable.PortableTapMode

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
    /** Remembers the chosen tag-matching rule after cycling. */
    private val tagRuleSelected: ((Constants.TagFunctionality) -> Unit)? = null,
    /** Remembers the active tag chips and untagged selection after toggling or clearing. */
    private val tagSelectionChanged: ((List<String>, Boolean) -> Unit)? = null,
    /** Locks the vault, after the revealed rows have already been forgotten. */
    private val lockVault: (() -> Unit)? = null,
    private val onChange: (() -> Unit)? = null,
    private val addEntry: (() -> Unit)? = null,
    private val editEntry: ((VaultCardState) -> Unit)? = null,
    private val editCounter: ((VaultCardState) -> Unit)? = null,
    initialRevealedIds: Set<Long> = emptySet(),
    private val revealChanged: ((Long, Boolean) -> Boolean)? = null
) {

    /** The selection currently shown. Change it through the [actions], never by assigning here. */
    var filter: VaultFilter = initialFilter
        private set

    var appearance: VaultAppearance = appearance
        private set

    var tapSingle: PortableTapMode = PortableTapMode.REVEAL
        private set

    var tapDouble: PortableTapMode = PortableTapMode.NOTHING
        private set

    var tapToRevealTimeoutSeconds: Int = 0
        private set

    fun setCardLayout(layout: Constants.CardLayouts) {
        if (locked) return
        appearance = appearance.copy(cardLayout = layout)
        onChange?.invoke()
    }

    fun setHideIssuer(hideIssuer: Boolean) {
        if (locked) return
        appearance = appearance.copy(showIssuer = !hideIssuer)
        onChange?.invoke()
    }

    fun setAppearance(layout: Constants.CardLayouts, hideIssuer: Boolean) {
        if (locked) return
        appearance = appearance.copy(cardLayout = layout, showIssuer = !hideIssuer)
        onChange?.invoke()
    }

    fun setDisplaySettings(
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?
    ) {
        setDisplaySettings(
            layout = layout,
            hideIssuer = hideIssuer,
            showPreviousToken = showPreviousToken,
            tokenGroupSize = tokenGroupSize,
            showIndividualCountdowns = appearance.showIndividualCountdowns
        )
    }

    fun setDisplaySettings(
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualCountdowns: Boolean
    ) {
        setDisplaySettings(
            layout = layout,
            hideIssuer = hideIssuer,
            showPreviousToken = showPreviousToken,
            tokenGroupSize = tokenGroupSize,
            showIndividualCountdowns = showIndividualCountdowns,
            labelDisplay = appearance.labelDisplay,
            showThumbnails = appearance.showThumbnails
        )
    }

    fun setDisplaySettings(
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualCountdowns: Boolean,
        labelDisplay: Constants.LabelDisplay,
        showThumbnails: Boolean
    ) {
        setDisplaySettings(
            layout = layout,
            hideIssuer = hideIssuer,
            showPreviousToken = showPreviousToken,
            tokenGroupSize = tokenGroupSize,
            showIndividualCountdowns = showIndividualCountdowns,
            labelDisplay = labelDisplay,
            showThumbnails = showThumbnails,
            labelSizeSp = appearance.labelSizeSp ?: 0,
            highlightExpiringTokens = appearance.expiringSeconds >= 0,
            tapSingle = tapSingle,
            tapDouble = tapDouble
        )
    }

    fun setDisplaySettings(
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualCountdowns: Boolean,
        labelDisplay: Constants.LabelDisplay,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightExpiringTokens: Boolean,
        tapSingle: PortableTapMode,
        tapDouble: PortableTapMode,
        hideGlobalTimeout: Boolean = appearance.hideGlobalTimeout,
        tapToRevealTimeoutSeconds: Int = this.tapToRevealTimeoutSeconds,
        customThumbnailSizeDp: Int? = null,
        focusSearchOnStart: Boolean = appearance.focusSearchOnStart
    ) {
        if (locked) return
        val normalizedSize = PortableTapMode.normalizeLabelSizeSp(labelSizeSp)
        val normalizedRevealTimeout = PortableTapMode.normalizeRevealTimeoutSeconds(tapToRevealTimeoutSeconds)
        val portableLayout = PortableCardLayout.values().firstOrNull { it.name == layout.name }
            ?: PortableCardLayout.DEFAULT
        val resolvedThumbnailSizeDp = if (customThumbnailSizeDp != null) {
            portableLayout.effectiveThumbnailSizeDp(customThumbnailSizeDp)
        } else {
            appearance.thumbnailSizeDp
        }
        appearance = appearance.copy(
            cardLayout = layout,
            showIssuer = !hideIssuer,
            showPreviousToken = showPreviousToken,
            groupTokens = tokenGroupSize != 0,
            tokenGroupSize = tokenGroupSize?.takeIf { it > 0 },
            showIndividualCountdowns = showIndividualCountdowns,
            hideGlobalTimeout = hideGlobalTimeout,
            focusSearchOnStart = focusSearchOnStart,
            labelDisplay = labelDisplay,
            showThumbnails = showThumbnails,
            thumbnailSizeDp = resolvedThumbnailSizeDp,
            labelSizeSp = normalizedSize.takeIf { it > 0 },
            expiringSeconds = if (highlightExpiringTokens) 8 else -1
        )
        this.tapSingle = tapSingle
        this.tapDouble = tapDouble
        this.tapToRevealTimeoutSeconds = normalizedRevealTimeout
        if (normalizedRevealTimeout <= 0) {
            revealedAtMillis.clear()
        } else if (lastClockMillis != Long.MIN_VALUE) {
            for (id in revealed) {
                revealedAtMillis.putIfAbsent(id, lastClockMillis)
            }
        }
        onChange?.invoke()
    }

    fun setDisplaySelection(selection: PortableCardDisplaySelection) {
        val resolvedLayout = Constants.CardLayouts.values()
            .firstOrNull { it.name == selection.layout.name } ?: Constants.CardLayouts.DEFAULT
        val resolvedLabelDisplay = Constants.LabelDisplay.values()
            .firstOrNull { it.name == selection.labelDisplay.name } ?: Constants.LabelDisplay.TRUNCATE
        setDisplaySettings(
            layout = resolvedLayout,
            hideIssuer = selection.hideIssuer,
            showPreviousToken = selection.showPreviousToken,
            tokenGroupSize = selection.tokenGroupSize,
            showIndividualCountdowns = selection.showIndividualCountdowns,
            labelDisplay = resolvedLabelDisplay,
            showThumbnails = selection.showThumbnails,
            labelSizeSp = selection.labelSizeSp,
            highlightExpiringTokens = selection.highlightExpiringTokens,
            tapSingle = selection.tapSingle,
            tapDouble = selection.tapDouble,
            hideGlobalTimeout = selection.hideGlobalTimeout,
            tapToRevealTimeoutSeconds = selection.tapToRevealTimeoutSeconds,
            customThumbnailSizeDp = selection.thumbnailSizeDp,
            focusSearchOnStart = selection.focusSearchOnStart
        )
    }

    private val revealed = LinkedHashSet<Long>(initialRevealedIds)
    private val revealedAtMillis = HashMap<Long, Long>()
    private var lastClockMillis: Long = Long.MIN_VALUE
    private var locked = false

    init {
        projection.applyFilter(initialFilter)
    }

    private fun expireRevealedIfNeeded(nowMillis: Long) {
        lastClockMillis = nowMillis
        val timeoutSeconds = tapToRevealTimeoutSeconds
        if (timeoutSeconds <= 0 || revealed.isEmpty()) return
        val timeoutMillis = timeoutSeconds * 1000L
        val expired = ArrayList<Long>()
        for (id in revealed) {
            val openedAt = revealedAtMillis[id]
            if (openedAt == null) {
                revealedAtMillis[id] = nowMillis
            } else if (nowMillis > openedAt && nowMillis - openedAt >= timeoutMillis) {
                expired.add(id)
            }
        }
        for (id in expired) {
            revealed.remove(id)
            revealedAtMillis.remove(id)
            revealChanged?.invoke(id, false)
        }
    }

    /** One frame at [nowMillis]: the selection applied, revealed content only for revealed ids. */
    fun frame(nowMillis: Long): VaultUiState {
        if (locked) return VaultUiState()
        expireRevealedIfNeeded(nowMillis)
        return projection.snapshot(filter, nowMillis, revealed, appearance, strings)
    }

    /** Applies [next] to the shared projection and remembers it as the current selection. */
    fun applyFilter(next: VaultFilter) {
        if (locked) return
        filter = next
        projection.applyFilter(next)
        onChange?.invoke()
    }

    /** Reveals or hides one row and persists the choice when [revealChanged] is supplied. */
    fun toggleReveal(id: Long) {
        if (locked) return
        if (id in revealed) {
            revealed.remove(id)
            revealedAtMillis.remove(id)
            revealChanged?.invoke(id, false)
        } else {
            if (revealChanged?.invoke(id, true) == false) {
                onChange?.invoke()
                return
            }
            revealed.add(id)
            if (tapToRevealTimeoutSeconds > 0 && lastClockMillis != Long.MIN_VALUE) {
                revealedAtMillis[id] = lastClockMillis
            } else {
                revealedAtMillis.remove(id)
            }
        }
        onChange?.invoke()
    }

    /** Replaces the revealed set from persisted entry metadata after a vault restore. */
    fun syncRevealedIds(ids: Set<Long>) {
        if (locked) return
        revealed.clear()
        revealedAtMillis.clear()
        revealed.addAll(ids)
        if (tapToRevealTimeoutSeconds > 0 && lastClockMillis != Long.MIN_VALUE) {
            for (id in ids) {
                revealedAtMillis[id] = lastClockMillis
            }
        }
        onChange?.invoke()
    }

    /** After a host commits deletion, discard reveal state and rebuild the current selection. */
    fun entryRemoved(id: Long) {
        if (locked) return
        revealed.remove(id)
        revealedAtMillis.remove(id)
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
        revealedAtMillis.clear()
        filter = VaultFilter(combination = filter.combination)
        try {
            lockVault?.invoke()
        } finally {
            onChange?.invoke()
        }
    }

    /** The screen's callbacks, wired to this presenter and to the host's optional hooks. */
    fun actions(copyEntry: ((VaultCardState) -> Unit)? = null,
                copyAccount: ((VaultCardState) -> Unit)? = null,
                copyPassword: ((VaultCardState) -> Unit)? = null,
                moveEntry: ((VaultCardState, Int) -> Unit)? = null,
                configureAutoLock: (() -> Unit)? = null,
                exportEncrypted: (() -> Unit)? = null,
                restoreEncrypted: (() -> Unit)? = null,
                importUri: (() -> Unit)? = null,
                importPasswords: (() -> Unit)? = null,
                importEmail: (() -> Unit)? = null,
                importThunderbird: (() -> Unit)? = null,
                viewEmailDetails: ((VaultCardState) -> Unit)? = null,
                configureLayout: (() -> Unit)? = null,
                configureSearchFields: (() -> Unit)? = null,
                importLinScp: (() -> Unit)? = null,
                exportLinScp: (() -> Unit)? = null,
                importOpenPgp: (() -> Unit)? = null,
                exportOpenPgp: (() -> Unit)? = null,
                enrollHardwareUnlock: (() -> Unit)? = null,
                removeHardwareUnlock: (() -> Unit)? = null,
                hardwareCopy: (() -> Unit)? = null,
                hardwareOath: (() -> Unit)? = null,
                storageLocation: (() -> Unit)? = null,
                changePassword: (() -> Unit)? = null,
                exportArchives: (() -> Unit)? = null,
                exportPasswords: (() -> Unit)? = null,
                exportEmail: (() -> Unit)? = null,
                exportThunderbird: (() -> Unit)? = null,
                exportPreferences: (() -> Unit)? = null,
                importPreferences: (() -> Unit)? = null,
                toggleBackupTimestamp: (() -> Unit)? = null,
                openAbout: (() -> Unit)? = null): VaultActions = VaultActions(
        onImportPreferences = importPreferences,
        onToggleBackupTimestamp = toggleBackupTimestamp,
        onOpenAbout = openAbout,
        onEnrollHardwareUnlock = enrollHardwareUnlock,
        onRemoveHardwareUnlock = removeHardwareUnlock,
        onHardwareCopy = hardwareCopy,
        onHardwareOath = hardwareOath,
        onStorageLocation = storageLocation,
        onChangePassword = changePassword,
        onExportArchives = exportArchives,
        onExportPasswords = exportPasswords,
        onExportEmail = exportEmail,
        onExportThunderbird = exportThunderbird,
        onExportPreferences = exportPreferences,
        onExportOpenPgp = exportOpenPgp,
        onImportOpenPgp = importOpenPgp,
        onExportLinScp = exportLinScp,
        onImportLinScp = importLinScp,
        onConfigureLayout = configureLayout,
        onConfigureSearchFields = configureSearchFields,
        onViewEmailDetails = viewEmailDetails?.let { view -> { card -> if (card.id in revealed && card.kind == VaultEntryKind.EMAILS) view(card) } },
        onImportEmail = importEmail,
        onImportThunderbird = importThunderbird,
        onImportPasswords = importPasswords,
        onImportUri = importUri,
        onRestoreEncrypted = restoreEncrypted,
        onExportEncrypted = exportEncrypted,
        onConfigureAutoLock = configureAutoLock,
        onMoveUp = moveEntry?.let { move -> { card -> move(card, -1) } },
        onMoveDown = moveEntry?.let { move -> { card -> move(card, 1) } },
        onAddEntry = addEntry,
        onEditEntry = editEntry?.let { edit -> { card -> if (card.id in revealed) edit(card) } },
        onEditCounter = editCounter?.let { edit -> { card -> if (card.id in revealed && card.counter != null) edit(card) } },
        onCopyToken = { card -> if (copyEntry != null) copyEntry(card) else copyToken(card) },
        onCopyAccount = copyAccount,
        onCopyPassword = copyPassword,
        onToggleReveal = { card -> toggleReveal(card.id) },
        onCardTap = if (tapSingle == PortableTapMode.REVEAL && tapDouble == PortableTapMode.NOTHING) null else { card ->
            dispatchTap(tapSingle, card, copyEntry)
        },
        onCardDoubleTap = if (tapDouble == PortableTapMode.NOTHING) null else { card ->
            dispatchTap(tapDouble, card, copyEntry)
        },
        onNextCounter = advanceCounter,
        onEntryActions = entryActions,
        onEnterPin = enterPin,
        onDelete = deleteEntry,
        onQueryChange = { query -> applyFilter(filter.withQuery(query)) },
        onSelectCategory = { category -> applyFilter(filter.withCategory(category)) },
        onToggleTag = { tag ->
            if (!locked) {
                val next = filter.withTagToggled(tag)
                applyFilter(next)
                tagSelectionChanged?.invoke(next.selectedTags, next.untaggedSelected)
            }
        },
        onToggleUntagged = {
            if (!locked) {
                val next = filter.withUntaggedToggled()
                applyFilter(next)
                tagSelectionChanged?.invoke(next.selectedTags, next.untaggedSelected)
            }
        },
        onCycleTagRule = {
            if (!locked) {
                val prevTags = filter.selectedTags
                val next = filter.withCycledTagFunctionality()
                applyFilter(next)
                tagRuleSelected?.invoke(next.tagFunctionality)
                if (next.selectedTags != prevTags) {
                    tagSelectionChanged?.invoke(next.selectedTags, next.untaggedSelected)
                }
            }
        },
        onClearFilters = {
            if (!locked) {
                val next = filter.clearedFilters()
                applyFilter(next)
                tagSelectionChanged?.invoke(next.selectedTags, next.untaggedSelected)
            }
        },
        onSelectSort = { mode ->
            if (!locked) {
                applyFilter(filter.withSortMode(mode))
                sortSelected?.invoke(mode)
            }
        },
        onLock = { lock() }
    ).guarded { !locked }

    private fun dispatchTap(
        mode: PortableTapMode,
        card: VaultCardState,
        copyEntry: ((VaultCardState) -> Unit)?
    ) {
        if (locked) return
        when (mode) {
            PortableTapMode.NOTHING -> Unit
            PortableTapMode.REVEAL -> toggleReveal(card.id)
            PortableTapMode.COPY -> if (copyEntry != null) copyEntry(card) else copyToken(card)
        }
    }

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
