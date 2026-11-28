package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

/**
 * The vault screen, shared by every Compose host.
 *
 * Vault data remains stateless (only the Tools menu owns ephemeral visibility): [state] is one immutable frame and every interaction goes back out
 * through [actions]. That is what makes the Android `ComposeView` and the desktop window the same
 * code, and it keeps the frame free of entries, keys and session authority — a stale frame can only
 * ask the host to do something, and the host re-checks the session when it does.
 *
 * The host owns the clock: rebuild [VaultUiState] from `rememberNowMillis()` and
 * `VaultProjection.snapshot(..)` and only this screen recomposes once per second.
 */
@Composable
fun VaultScreen(
    state: VaultUiState,
    strings: VaultStrings,
    actions: VaultActions,
    modifier: Modifier = Modifier,
    appearance: VaultAppearance = VaultAppearance(),
    artwork: VaultArtwork? = null,
    toast: String = "",
    onDismissToast: (() -> Unit)? = null
) {
    SharedVaultScaffold(
        isEmpty = state.isEmpty,
        modifier = modifier,
        backgroundColor = ForestPalette.backgroundDeep,
        centerEmpty = true,
        toast = toast,
        onDismissToast = onDismissToast,
        headerContent = {
            if (appearance.showControls) {
                VaultTopBar(
                    title = strings.appTitle,
                    countLabel = countLabel(state, strings),
                    lockLabel = strings.lockAction,
                    onLock = actions.onLock
                )
                if (!appearance.hideGlobalTimeout && state.totalEntries > 0 && state.globalProgress > 0f) {
                    SharedVaultGlobalProgressBar(
                        progress = state.globalProgress,
                        expiring = state.globalExpiring
                    )
                }
                VaultToolsBar(actions = actions, strings = strings)
                SharedVaultFilterHeader(
                    horizontalPaddingDp = 16,
                    verticalPaddingDp = 10,
                    itemSpacingDp = 10,
                    searchContent = {
                        VaultSearchField(
                            value = state.filter.query,
                            hint = strings.searchHint,
                            onValueChange = actions.onQueryChange,
                            focusOnStart = appearance.focusSearchOnStart
                        )
                    },
                    categoryContent = {
                        VaultCategoryTabs(
                            tabs = state.categories,
                            strings = strings,
                            onSelect = actions.onSelectCategory
                        )
                    },
                    tagContent = if (state.tags.isNotEmpty()) {
                        {
                            VaultTagChips(
                                chips = state.tags,
                                strings = strings,
                                clearLabel = strings.clearFilters,
                                showClear = state.filter.hasTagFilter || state.filter.hasQuery,
                                tagRuleLabel = strings.tagRuleTitle(state.filter.tagFunctionality),
                                actions = actions
                            )
                        }
                    } else null,
                    sortContent = {
                        VaultSortBar(
                            options = state.sortOptions,
                            label = strings.sortAction,
                            strings = strings,
                            onSelect = actions.onSelectSort
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        },
        emptyContent = {
            val addAction = if (!state.filter.hasQuery && !state.filter.hasTagFilter) actions.onAddEntry else null
            val addLabel = strings.addEntryTitle(state.filter.category)
            VaultEmptyState(
                title = state.emptyTitle,
                description = state.emptyDescription,
                actionLabel = if (addAction != null) addLabel else null,
                actionDescription = addLabel,
                onAction = addAction
            )
        },
        listContent = {
            SharedVaultList(cards = state.cards, key = { it.id }, filterKey = state.filter,
                canMove = { card, direction -> if (direction < 0) card.canMoveUp && actions.onMoveUp != null else card.canMoveDown && actions.onMoveDown != null },
                onMove = { card, direction -> if (direction < 0) actions.onMoveUp?.invoke(card) else actions.onMoveDown?.invoke(card) }
            ) { card -> VaultEntryCard(card, strings, appearance, actions, artwork) }
        }
    )
}

/** `12 entries` / `3 of 12` in the app bar. */
private fun countLabel(state: VaultUiState, strings: VaultStrings): String = when {
    state.totalEntries == 0 -> ""
    state.cards.size == state.totalEntries -> "${state.cards.size} ${strings.entriesSuffix}"
    else -> "${state.cards.size} / ${state.totalEntries} ${strings.entriesSuffix}"
}
