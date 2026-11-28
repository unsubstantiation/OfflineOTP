package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

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
        leadingContent = if (cycleTagRule != null && tagRuleLabel != null) {
            {
                ForestTextAction(
                    text = tagRuleLabel,
                    onClick = cycleTagRule,
                    color = ForestPalette.textSecondary,
                    contentDescription = "How selected tags combine"
                )
            }
        } else null,
        trailingContent = if (showClear) {
            {
                ForestTextAction(
                    text = clearLabel,
                    onClick = actions.onClearFilters,
                    color = ForestPalette.warning,
                    contentDescription = "Clear the search and tag filters"
                )
            }
        } else null
    )
}

@Composable
internal fun VaultEntryCard(card: VaultCardState, strings: VaultStrings, appearance: VaultAppearance,
                            actions: VaultActions, artwork: VaultArtwork? = null) {
    SharedVaultRenderedCard(vaultCardContent(card, strings, appearance, actions), artwork) { command ->
        dispatchVaultCardCommand(card, command, actions)
    }
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
