package com.fairuniversal.offlineotp.ui.portable

/** Apple/portable frame adapter. No token computation, entry ownership, native service or Compose. */
fun portableCardContent(card: PortableCard, state: PortableVaultState, layout: PortableCardLayout): VaultCardContent {
    val title = card.visibleTitle(state.hiddenTitleEnabled)
    val commands = ArrayList<VaultCardCommand>()
    fun command(key: String, label: String, description: String = "$label for $title", longPress: String? = null) {
        commands.add(VaultCardCommand(key, label, description, longPress = longPress))
    }
    command("reveal", if (card.revealed) "Hide" else "Reveal", "Show or hide the code for $title")
    if (card.canCopy) command("copy", if (card.hasCode) "Copy Token" else "Copy")
    if (card.canCopyAccount) command("copyAccount", "Copy Account")
    if (card.canCopyPassword) command("copyPassword", "Copy Password")
    if (card.pinRequired) command("pin", "Enter PIN")
    if (card.canReorder) { command("moveUp", "Move up", "Move $title up"); command("moveDown", "Move down", "Move $title down") }
    if (card.canStep) command("step", "Next code", longPress = "counter")
    if (card.isEmail && card.revealed) command("emailDetails", "Connection details")
    if (card.revealed) command("edit", "Edit")
    fun tap(mode: PortableTapMode): String? = when (mode) {
        PortableTapMode.REVEAL -> "reveal"
        PortableTapMode.COPY -> if (card.canCopy) "copy" else null
        PortableTapMode.NOTHING -> null
    }
    return VaultCardContent(
        title = title, subtitle = card.subtitle, revealed = card.revealed,
        showThumbnail = state.showThumbnails,
        thumbnailName = if (card.revealed || !state.hiddenTitleEnabled) card.mark else "",
        tileSeed = card.visibleMark(state.hiddenTitleEnabled), thumbnailSizeDp = layout.effectiveThumbnailSizeDp(state.customThumbnailSizeDp),
        scrollLabels = state.labelDisplay.scrollLabels, labelSizeSp = state.labelSizeSp.takeIf { it > 0 },
        labelMaxLines = state.labelDisplay.labelMaxLines(layout), verticalPaddingDp = layout.cardVerticalPaddingDp,
        outerVerticalPaddingDp = 4, codeBelowIdentity = layout.codeBelowIdentity,
        showCodeArea = card.hasCode || card.revealed && (card.pinRequired || card.counterLabel.isNotEmpty()),
        tags = if (card.revealed) card.tags else emptyList(), customPeriodLabel = card.customPeriodLabel.ifEmpty { null },
        detailLabel = card.visibleDetailLabel().ifEmpty { null }, metadataLabel = card.metadata,
        metadataWarning = state.highlightExpiringTokens && card.isExpiring && card.revealed,
        revealedContent = card.visibleRevealedContent().ifEmpty { null }, isNote = card.isNote,
        token = if (card.hasCode) { if (card.revealed) card.visibleCode() else "••• •••" } else null,
        tokenVisible = card.revealed && card.hasCode, expiring = state.highlightExpiringTokens && card.isExpiring,
        previousToken = card.previousCode.takeIf { card.revealed && card.hasCode && it.isNotEmpty() },
        showCountdown = card.hasCode && card.showCountdown && card.secondsLeft > 0,
        progress = card.countdownProgress, remainingSeconds = card.secondsLeft,
        pinRequired = card.pinRequired && card.revealed, counterText = if (card.revealed) card.counterLabel.ifEmpty { null } else null,
        onTap = tap(state.tapSingle), onDoubleTap = tap(state.tapDouble), actions = commands
    )
}
