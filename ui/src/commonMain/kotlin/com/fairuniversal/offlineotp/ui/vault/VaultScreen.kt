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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    artwork: VaultArtwork? = null
) {
    val listState = rememberLazyListState()
    val latestCards by rememberUpdatedState(state.cards)
    val latestActions by rememberUpdatedState(actions)
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var pointerY by remember { mutableStateOf(0f) }
    var grabOffset by remember { mutableStateOf(0f) }
    var dragOrder by remember { mutableStateOf<List<Long>?>(null) }
    var awaitingOrder by remember { mutableStateOf<List<Long>?>(null) }
    var expectedOrder by remember { mutableStateOf<List<Long>?>(null) }
    fun endDrag() { draggingId = null; dragOrder = null; awaitingOrder = null; expectedOrder = null }
    fun moveIfNeeded() {
        val id = draggingId ?: return
        val card = latestCards.firstOrNull { it.id == id } ?: run { endDrag(); return }
        if (!card.canMoveUp && !card.canMoveDown) { endDrag(); return }
        val order = latestCards.map { it.id }
        // Wait for host adoption before issuing another move. Failure never loops/retries writes.
        if (awaitingOrder != null) {
            if (order == awaitingOrder) return
            if (order != expectedOrder) { endDrag(); return }
            dragOrder = order
            awaitingOrder = null; expectedOrder = null
        }
        if (order != dragOrder) { endDrag(); return }
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            (item.key as? Long)?.let { EntryDragBounds(it, item.offset.toFloat(), item.size.toFloat()) }
        }
        val direction = entryDragDirection(id, pointerY, order, visible)
        val action = when {
            direction < 0 && card.canMoveUp -> latestActions.onMoveUp
            direction > 0 && card.canMoveDown -> latestActions.onMoveDown
            else -> null
        } ?: return
        val index = order.indexOf(id)
        val proposed = order.toMutableList()
        proposed[index] = order[index + direction]; proposed[index + direction] = id
        awaitingOrder = order; expectedOrder = proposed
        action(card) // Current frame and host revalidate identity, manual ordering and session.
    }
    // Long lists scroll under the held pointer, for touch and mouse alike.
    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            val info = listState.layoutInfo
            val edge = 56f
            val scroll = when {
                pointerY < info.viewportStartOffset + edge -> -12f
                pointerY > info.viewportEndOffset - edge -> 12f
                else -> 0f
            }
            if (awaitingOrder == null && scroll != 0f) listState.scrollBy(scroll)
            moveIfNeeded()
            delay(16)
        }
    }
    // Filter/sort changes cancel rather than carrying a gesture into another view.
    LaunchedEffect(state.filter) { endDrag() }
    SharedVaultScaffold(
        isEmpty = state.isEmpty,
        modifier = modifier,
        backgroundColor = ForestPalette.backgroundDeep,
        centerEmpty = true,
        headerContent = {
            if (appearance.showControls) {
                VaultTopBar(
                    title = strings.appTitle,
                    countLabel = countLabel(state, strings),
                    lockLabel = strings.lockAction,
                    onLock = actions.onLock
                )
                VaultToolsBar(actions = actions, strings = strings)
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
                            tagRuleLabel = strings.tagRuleTitle(state.filter.tagFunctionality),
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
        },
        emptyContent = {
            VaultEmptyState(title = state.emptyTitle, description = state.emptyDescription)
        },
        listContent = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                position.y >= it.offset && position.y < it.offset + it.size
                            }
                            val card = latestCards.firstOrNull { it.id == hit?.key }
                            if (card != null && ((card.canMoveUp && latestActions.onMoveUp != null) ||
                                (card.canMoveDown && latestActions.onMoveDown != null))) {
                                dragOrder = latestCards.map { it.id }
                                draggingId = card.id; pointerY = position.y
                                grabOffset = position.y - checkNotNull(hit).offset
                            }
                        },
                        onDrag = { change, amount ->
                            if (draggingId != null) {
                                change.consume()
                                pointerY += amount.y
                                moveIfNeeded()
                            }
                        },
                        onDragEnd = { endDrag() },
                        onDragCancel = { endDrag() }
                    )
                },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = state.cards, key = { card -> card.id }) { card ->
                    val held = draggingId == card.id
                    val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == card.id }?.offset ?: 0
                    Box(modifier = Modifier.zIndex(if (held) 1f else 0f).graphicsLayer {
                        translationY = if (held) pointerY - grabOffset - offset else 0f
                        shadowElevation = if (held) 8f else 0f
                    }) {
                        VaultEntryCard(
                            card = card,
                            strings = strings,
                            appearance = appearance,
                            actions = actions,
                            artwork = artwork
                        )
                    }
                }
            }
        }
    )
}

/** `12 entries` / `3 of 12` in the app bar. */
private fun countLabel(state: VaultUiState, strings: VaultStrings): String = when {
    state.totalEntries == 0 -> ""
    state.cards.size == state.totalEntries -> "${state.cards.size} ${strings.entriesSuffix}"
    else -> "${state.cards.size} / ${state.totalEntries} ${strings.entriesSuffix}"
}
