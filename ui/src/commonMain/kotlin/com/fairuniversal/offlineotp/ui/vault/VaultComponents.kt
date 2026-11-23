package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation

/**
 * Thin `:ui` adapters mapping `:ui-model` values ([VaultUiState], [VaultCardState],
 * [VaultAppearance], [VaultStrings], [VaultActions]) onto the shared Compose components in
 * `:ui-foundation` (`SharedVaultComponents.kt`).
 */

@Composable
internal fun VaultTopBar(
    title: String,
    countLabel: String,
    lockLabel: String,
    onLock: () -> Unit
) {
    SharedVaultTopBar(
        title = title,
        countLabel = countLabel,
        trailingActions = {
            ForestTextAction(text = lockLabel, onClick = onLock)
        }
    )
}

@Composable
internal fun VaultSearchField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = false
) {
    SharedVaultSearchField(
        value = value,
        hint = hint,
        onValueChange = onValueChange,
        modifier = modifier,
        focusOnStart = focusOnStart
    )
}

@Composable
internal fun VaultCategoryTabs(
    tabs: List<VaultCategoryTab>,
    strings: VaultStrings,
    onSelect: (EntryListPresentation.Category) -> Unit
) {
    SharedVaultCategoryBar(
        tabs = tabs.map { tab -> strings.categoryCount(tab.category, tab.count) to tab.selected },
        onSelect = { index -> tabs.getOrNull(index)?.let { onSelect(it.category) } },
        horizontalPaddingDp = 8
    )
}

@Composable
internal fun VaultSortBar(
    options: List<VaultSortOption>,
    label: String,
    strings: VaultStrings,
    onSelect: (Constants.SortMode) -> Unit
) {
    SharedVaultSortBar(
        label = label,
        options = options.map { option -> strings.sortTitle(option.mode) to option.selected },
        onSelect = { index -> options.getOrNull(index)?.let { onSelect(it.mode) } }
    )
}

@Composable
internal fun VaultTagChips(
    chips: List<VaultTagChip>,
    strings: VaultStrings,
    clearLabel: String,
    showClear: Boolean,
    tagRuleLabel: String? = null,
    actions: VaultActions
) {
    val cycleTagRule = actions.onCycleTagRule
    SharedVaultTagBar(
        chips = chips.map { chip -> strings.tagCount(chip.name, chip.count) to chip.selected },
        onToggleChip = { index ->
            chips.getOrNull(index)?.let { chip ->
                if (chip.isUntagged) actions.onToggleUntagged() else actions.onToggleTag(chip.name)
            }
        },
        horizontalPaddingDp = 16,
        trailingContent = if ((cycleTagRule != null && tagRuleLabel != null) || showClear) {
            {
                if (cycleTagRule != null && tagRuleLabel != null) {
                    SharedVaultTagChip(
                        label = tagRuleLabel,
                        selected = false,
                        onClick = cycleTagRule
                    )
                }
                if (showClear) {
                    ForestTextAction(text = clearLabel, onClick = actions.onClearFilters)
                }
            }
        } else null
    )
}

@Composable
internal fun VaultEntryCard(
    card: VaultCardState,
    strings: VaultStrings,
    appearance: VaultAppearance,
    actions: VaultActions,
    artwork: VaultArtwork? = null
) {
    val customPeriod = card.customPeriodSeconds
    val detailLabel = card.detailLabel
    val revealedContent = card.revealedContent
    val showCodeArea = card.detailsVisible &&
        (card.kind == VaultEntryKind.OTP || card.token != null || card.pinRequired || card.counter != null)
    val showThumb = appearance.showThumbnails && card.detailsVisible
    SharedVaultEntryCard(
        title = card.title,
        subtitle = card.subtitle,
        revealed = card.revealed,
        showThumbnail = showThumb,
        thumbnailSizeDp = appearance.thumbnailSizeDp,
        tileSeed = card.thumbnailName.ifBlank { card.title },
        artworkSlot = if (showThumb) {
            { VaultIssuerArtwork(card = card, showIssuer = appearance.showIssuer, sizeDp = appearance.thumbnailSizeDp, artwork = artwork) }
        } else null,
        scrollLabels = appearance.scrollLabels,
        labelSizeSp = appearance.labelSizeSp,
        labelMaxLines = appearance.labelMaxLines,
        verticalPaddingDp = appearance.cardVerticalPaddingDp,
        codeBelowIdentity = appearance.codeBelowIdentity,
        showCodeArea = showCodeArea,
        inlineTagsSummary = if (card.tags.isNotEmpty()) card.tags.joinToString(" · ") else "",
        customPeriodLabel = if (card.detailsVisible && customPeriod != null) strings.customPeriod(customPeriod) else null,
        detailLabel = if (card.detailsVisible && detailLabel != null) detailLabel else null,
        revealedContent = revealedContent,
        isNote = card.kind == VaultEntryKind.NOTES,
        onClick = { (actions.onCardTap ?: actions.onToggleReveal)(card) },
        onDoubleClick = actions.onCardDoubleTap?.let { action -> { action(card) } },
        codeAreaContent = {
            val token = card.token
            val previousToken = card.previousToken
            SharedVaultCodeArea(
                displayedToken = token?.let { if (card.tokenVisible) token else TokenDisplay.mask(token) },
                tokenVisible = card.tokenVisible,
                showsExpiry = card.showsExpiry,
                previousToken = previousToken,
                showCountdown = card.hasCountdown && card.showCountdown,
                progress = card.progress ?: 0f,
                remainingSeconds = card.remainingSeconds ?: 0,
                showSecondsText = true,
                pinRequired = card.pinRequired,
                pinRequiredLabel = strings.pinRequired,
                counterText = card.counter?.let { "${strings.counterLabel} $it" },
                noCodeLabel = strings.noCode
            )
        },
        actionsContent = {
            val subject = card.title
            val privacy = actions.onTogglePrivacy
            if (privacy != null) {
                ForestTextAction(
                    text = if (card.detailsVisible) strings.privacyHide else strings.privacyReveal,
                    onClick = { privacy(card) },
                    contentDescription = "${if (card.detailsVisible) strings.privacyHide else strings.privacyReveal} for $subject"
                )
            }
            if (card.detailsVisible && (card.hasToken || card.copyable)) {
                val copyLabel = if (card.hasToken) strings.copyAction else strings.copyContent
                ForestTextAction(
                    text = copyLabel,
                    enabled = card.copyable,
                    onClick = { actions.onCopyToken(card) },
                    contentDescription = "$copyLabel for $subject"
                )
            }
            val copyAccount = actions.onCopyAccount
            if (card.detailsVisible && card.canCopyAccount && copyAccount != null) {
                ForestTextAction(
                    text = strings.copyAccount,
                    onClick = { copyAccount(card) },
                    contentDescription = "${strings.copyAccount} for $subject"
                )
            }
            val copyPassword = actions.onCopyPassword
            if (card.detailsVisible && card.canCopyPassword && copyPassword != null) {
                ForestTextAction(
                    text = strings.copyPassword,
                    onClick = { copyPassword(card) },
                    contentDescription = "${strings.copyPassword} for $subject"
                )
            }
            val enterPin = actions.onEnterPin
            if (card.detailsVisible && card.pinRequired && enterPin != null) {
                ForestTextAction(
                    text = strings.enterPin,
                    onClick = { enterPin(card) },
                    contentDescription = "${strings.enterPin} for $subject"
                )
            }
            val nextCounter = actions.onNextCounter
            if (card.detailsVisible && card.counter != null && nextCounter != null) {
                ForestTextAction(
                    text = strings.nextCode,
                    onClick = { nextCounter(card) },
                    onLongClick = actions.onEditCounter?.let { action -> { action(card) } },
                    contentDescription = "${strings.nextCode} for $subject"
                )
            }
            if ((!card.detailsVisible && actions.onTogglePrivacy == null) ||
                (card.detailsVisible && (card.hasToken || card.kind != VaultEntryKind.OTP))) {
                val revealLabel = if (card.revealed) strings.hideAction else strings.revealAction
                ForestTextAction(
                    text = revealLabel,
                    onClick = { actions.onToggleReveal(card) },
                    contentDescription = "$revealLabel for $subject"
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            val viewEmailDetails = actions.onViewEmailDetails
            if (card.detailsVisible && card.revealed && card.kind == VaultEntryKind.EMAILS && viewEmailDetails != null) {
                ForestTextAction(text = strings.emailDetails, onClick = { viewEmailDetails(card) },
                    contentDescription = "${strings.emailDetails} for $subject")
            }
            val edit = actions.onEditEntry
            if (card.detailsVisible && card.revealed && edit != null &&
                (card.kind in listOf(VaultEntryKind.OTP, VaultEntryKind.NOTES, VaultEntryKind.SSH_KEYS, VaultEntryKind.EMAILS) ||
                    (card.kind == VaultEntryKind.PASSWORDS && card.canEditPassword))) {
                ForestTextAction(
                    text = strings.editEntry,
                    onClick = { edit(card) },
                    contentDescription = "${strings.editEntry} for $subject"
                )
            }
        }
    )
}

@Composable
private fun VaultIssuerArtwork(card: VaultCardState, showIssuer: Boolean, sizeDp: Float, artwork: VaultArtwork?) {
    SharedIssuerArtwork(
        thumbnailName = if (showIssuer || card.revealed) card.thumbnailName else "",
        title = card.title,
        sizeDp = sizeDp,
        artwork = artwork
    )
}

@Composable
internal fun VaultEmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    actionDescription: String = "",
    onAction: (() -> Unit)? = null
) {
    SharedVaultEmptyState(
        title = title,
        description = description,
        actionLabel = actionLabel,
        actionDescription = actionDescription,
        onAction = onAction
    )
}
