package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.ui.portable.VaultCardContent
import com.fairuniversal.offlineotp.ui.portable.VaultCardCommand

/** JVM entry/frame adapter only; the renderer consumes no core enum, entry or callback container. */
fun vaultCardContent(card: VaultCardState, strings: VaultStrings, appearance: VaultAppearance, actions: VaultActions): VaultCardContent {
    val commands = ArrayList<VaultCardCommand>()
    fun command(key: String, label: String, enabled: Boolean = true, longPress: String? = null) {
        commands.add(VaultCardCommand(key, label, "$label for ${card.title}", enabled, longPress))
    }
    if (actions.onTogglePrivacy != null) command("privacy", if (card.detailsVisible) strings.privacyHide else strings.privacyReveal)
    if (card.detailsVisible && (card.hasToken || card.copyable)) command("copy", if (card.hasToken) strings.copyAction else strings.copyContent, card.copyable)
    if (card.detailsVisible && card.canCopyAccount && actions.onCopyAccount != null) command("copyAccount", strings.copyAccount)
    if (card.detailsVisible && card.canCopyPassword && actions.onCopyPassword != null) command("copyPassword", strings.copyPassword)
    if (card.detailsVisible && card.pinRequired && actions.onEnterPin != null) command("pin", strings.enterPin)
    if (card.detailsVisible && card.counter != null && actions.onNextCounter != null) command("step", strings.nextCode, longPress = if (actions.onEditCounter != null) "counter" else null)
    if ((!card.detailsVisible && actions.onTogglePrivacy == null) || (card.detailsVisible && (card.hasToken || card.kind != VaultEntryKind.OTP)))
        command("reveal", if (card.revealed) strings.hideAction else strings.revealAction)
    if (card.detailsVisible && card.revealed && card.kind == VaultEntryKind.EMAILS && actions.onViewEmailDetails != null) command("emailDetails", strings.emailDetails)
    if (card.detailsVisible && card.revealed && actions.onEditEntry != null && (card.kind != VaultEntryKind.PASSWORDS || card.canEditPassword)) command("edit", strings.editEntry)
    return VaultCardContent(
        title = card.title, subtitle = card.subtitle, revealed = card.revealed,
        showThumbnail = appearance.showThumbnails && card.detailsVisible,
        thumbnailName = if (appearance.showIssuer || card.revealed) card.thumbnailName else "",
        tileSeed = card.thumbnailName.ifBlank { card.title }, thumbnailSizeDp = appearance.thumbnailSizeDp,
        scrollLabels = appearance.scrollLabels, labelSizeSp = appearance.labelSizeSp, labelMaxLines = appearance.labelMaxLines,
        verticalPaddingDp = appearance.cardVerticalPaddingDp, codeBelowIdentity = appearance.codeBelowIdentity,
        showCodeArea = card.detailsVisible && (card.kind == VaultEntryKind.OTP || card.token != null || card.pinRequired || card.counter != null),
        tags = if (card.detailsVisible) card.tags else emptyList(),
        customPeriodLabel = card.customPeriodSeconds?.takeIf { card.detailsVisible }?.let { strings.customPeriod(it) },
        detailLabel = card.detailLabel?.takeIf { card.detailsVisible }, revealedContent = card.revealedContent?.takeIf { card.detailsVisible },
        isNote = card.kind == VaultEntryKind.NOTES,
        token = card.token?.let { if (card.tokenVisible) it else TokenDisplay.mask(it) }, tokenVisible = card.tokenVisible,
        expiring = card.showsExpiry, previousToken = card.previousToken?.takeIf { card.tokenVisible },
        showCountdown = card.hasCountdown && card.showCountdown, progress = card.progress ?: 0f, remainingSeconds = card.remainingSeconds ?: 0,
        pinRequired = card.pinRequired, pinRequiredLabel = strings.pinRequired, counterText = card.counter?.let { "${strings.counterLabel} $it" },
        noCodeLabel = strings.noCode, onTap = "tap", onDoubleTap = if (actions.onCardDoubleTap != null) "doubleTap" else null, actions = commands
    )
}

fun dispatchVaultCardCommand(card: VaultCardState, key: String, actions: VaultActions) {
    when (key) {
        "tap" -> (actions.onCardTap ?: actions.onToggleReveal)(card)
        "doubleTap" -> actions.onCardDoubleTap?.invoke(card)
        "privacy" -> actions.onTogglePrivacy?.invoke(card)
        "copy" -> actions.onCopyToken(card)
        "copyAccount" -> actions.onCopyAccount?.invoke(card)
        "copyPassword" -> actions.onCopyPassword?.invoke(card)
        "pin" -> actions.onEnterPin?.invoke(card)
        "step" -> actions.onNextCounter?.invoke(card)
        "counter" -> actions.onEditCounter?.invoke(card)
        "reveal" -> actions.onToggleReveal(card)
        "emailDetails" -> actions.onViewEmailDetails?.invoke(card)
        "edit" -> actions.onEditEntry?.invoke(card)
    }
}
