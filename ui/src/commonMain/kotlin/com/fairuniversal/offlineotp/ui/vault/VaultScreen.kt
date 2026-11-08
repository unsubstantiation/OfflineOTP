package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette

/**
 * The vault screen, shared by every Compose host.
 *
 * The composable is stateless: [state] is one immutable frame and every interaction goes back out
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
    appearance: VaultAppearance = VaultAppearance()
) {
    Column(modifier = modifier.fillMaxSize().background(ForestPalette.backgroundDeep)) {
        if (appearance.showControls) {
        VaultTopBar(
            title = strings.appTitle,
            countLabel = countLabel(state, strings),
            lockLabel = strings.lockAction,
            onLock = actions.onLock
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VaultSearchField(
                value = state.filter.query,
                hint = strings.searchHint,
                onValueChange = actions.onQueryChange
            )
            VaultCategoryTabs(
                tabs = state.categories,
                strings = strings,
                onSelect = actions.onSelectCategory
            )
            if (state.tags.isNotEmpty()) {
                VaultTagChips(
                    chips = state.tags,
                    strings = strings,
                    clearLabel = strings.clearFilters,
                    showClear = state.filter.hasTagFilter || state.filter.hasQuery,
                    actions = actions
                )
            }
            VaultSortBar(
                options = state.sortOptions,
                label = strings.sortAction,
                strings = strings,
                onSelect = actions.onSelectSort
            )
        }
        }
        Spacer(modifier = Modifier.height(2.dp))
        if (state.isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VaultEmptyState(title = state.emptyTitle, description = state.emptyDescription)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = state.cards, key = { card -> card.id }) { card ->
                    VaultEntryCard(
                        card = card,
                        strings = strings,
                        appearance = appearance,
                        actions = actions
                    )
                }
            }
        }
    }
}

/** `12 entries` / `3 of 12` in the app bar. */
private fun countLabel(state: VaultUiState, strings: VaultStrings): String = when {
    state.totalEntries == 0 -> ""
    state.cards.size == state.totalEntries -> "${state.cards.size} ${strings.entriesSuffix}"
    else -> "${state.cards.size} / ${state.totalEntries} ${strings.entriesSuffix}"
}
