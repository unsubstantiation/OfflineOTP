package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fairuniversal.offlineotp.ui.portable.*
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType
import kotlinx.coroutines.delay

/**
 * Portable unlocked vault screen built on the same [SharedVaultTopBar], [SharedVaultEmptyState],
 * [SharedVaultEntryCard], and [SharedVaultCodeArea] declarations used by Android and Desktop.
 */
@Composable
fun PortableVaultScreen(
    state: PortableVaultState,
    onTab: (Int) -> Unit,
    onQuery: (String) -> Unit,
    onTag: (String) -> Unit,
    onClearTags: () -> Unit,
    onTool: (String) -> Unit,
    onCard: (Int, String) -> Unit,
    onDismissToast: () -> Unit,
    cardLayout: PortableCardLayout = PortableCardLayout.DEFAULT,
    artwork: VaultArtwork? = null
) {
    val listState = rememberLazyListState()
    val latestCards by rememberUpdatedState(state.cards)
    val latestOnCard by rememberUpdatedState(onCard)
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var pointerY by remember { mutableStateOf(0f) }
    var grabOffset by remember { mutableStateOf(0f) }
    fun endDrag() {
        draggingIndex = null
    }
    fun moveIfNeeded() {
        val activeIndex = draggingIndex ?: return
        val card = latestCards.firstOrNull { it.index == activeIndex } ?: run { endDrag(); return }
        if (!card.canReorder) { endDrag(); return }
        val order = latestCards.map { it.index.toLong() }
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val keyIndex = (item.key as? Int)?.toLong() ?: (item.key as? Long)
            keyIndex?.let { PortableEntryDragBounds(it, item.offset.toFloat(), item.size.toFloat()) }
        }
        val direction = portableEntryDragDirection(activeIndex.toLong(), pointerY, order, visible)
        if (direction == 0) return
        val pos = latestCards.indexOfFirst { it.index == activeIndex }
        val targetPos = pos + direction
        if (pos < 0 || targetPos !in latestCards.indices) return
        val neighborIndex = latestCards[targetPos].index
        draggingIndex = neighborIndex
        latestOnCard(activeIndex, if (direction < 0) "moveUp" else "moveDown")
    }
    LaunchedEffect(draggingIndex != null) {
        while (draggingIndex != null) {
            val info = listState.layoutInfo
            val scroll = sharedVaultViewportScroll(
                pointerY = pointerY,
                viewportStartOffset = info.viewportStartOffset,
                viewportEndOffset = info.viewportEndOffset
            )
            if (scroll != 0f) listState.scrollBy(scroll)
            moveIfNeeded()
            delay(16)
        }
    }
    val filterKey = listOf(
        state.tabs.indexOfFirst { it.selected },
        state.query,
        state.sortLabel,
        state.includeUntagged,
        state.tags.filter { it.selected }.joinToString("\n") { it.label }
    )
    LaunchedEffect(filterKey) {
        endDrag()
    }
    SharedVaultScaffold(
        isEmpty = state.cards.isEmpty(),
        backgroundColor = ForestPalette.background,
        centerEmpty = false,
        toast = state.toast,
        onDismissToast = onDismissToast,
        headerContent = {
            SharedVaultTopBar(
                title = "OfflineOTP",
                countLabel = state.countLabel,
                horizontalPaddingDp = 12,
                verticalPaddingDp = 8,
                trailingActions = {
                    ForestTextAction("Add", onClick = { onTool("add") }, contentDescription = "Add an entry")
                    ForestTextAction("Sort", onClick = { onTool("sort") }, contentDescription = "Sort entries")
                    ForestTextAction("Import", onClick = { onTool("backups") }, contentDescription = "Backups and links")
                    ForestTextAction("Settings", onClick = { onTool("settings") }, contentDescription = "Settings")
                    ForestTextAction("Lock", onClick = { onTool("lock") }, contentDescription = "Lock the vault")
                }
            )
            if (!state.hideGlobalTimeout && state.cards.isNotEmpty() && state.globalProgress > 0f) {
                SharedVaultGlobalProgressBar(
                    progress = state.globalProgress,
                    expiring = state.highlightExpiringTokens && state.globalExpiring
                )
            }
            SharedVaultFilterHeader(
                horizontalPaddingDp = 12,
                verticalPaddingDp = 6,
                itemSpacingDp = 8,
                searchContent = {
                    SharedVaultSearchField(
                        value = state.query,
                        hint = "Search",
                        onValueChange = onQuery,
                        focusOnStart = state.focusSearchOnStart
                    )
                },
                categoryContent = {
                    SharedVaultCategoryBar(
                        tabs = state.tabs.map { tab ->
                            (if (tab.count >= 0) "${tab.label} (${tab.count})" else tab.label) to tab.selected
                        },
                        onSelect = onTab,
                        horizontalPaddingDp = 0
                    )
                },
                tagContent = if (state.tags.isNotEmpty()) {
                    {
                        SharedVaultTagBar(
                            chips = state.tags.map { tag ->
                                (if (tag.count >= 0) "${tag.label} (${tag.count})" else tag.label) to tag.selected
                            },
                            onToggleChip = { index -> state.tags.getOrNull(index)?.let { onTag(it.label) } },
                            horizontalPaddingDp = 0,
                            leadingContent = {
                                ForestTextAction(
                                    text = state.tagRule,
                                    onClick = { onTool("tagRule") },
                                    color = ForestPalette.textSecondary,
                                    contentDescription = "How selected tags combine"
                                )
                            },
                            trailingContent = if (state.includeUntagged || state.tags.any { it.selected }) {
                                {
                                    ForestTextAction("Clear", onClick = onClearTags, color = ForestPalette.warning,
                                        contentDescription = "Clear the search and tag filters")
                                }
                            } else null
                        )
                    }
                } else null,
                sortContent = if (state.sortOptions.isNotEmpty()) {
                    {
                        SharedVaultSortBar(
                            label = "Sort",
                            options = state.sortOptions.map { option -> option.label to option.selected },
                            onSelect = { index -> onTool("sortIndex:$index") }
                        )
                    }
                } else null
            )
        },
        emptyContent = {
            SharedVaultEmptyState(
                title = "",
                description = state.emptyMessage,
                actionLabel = if (state.query.isEmpty()) "Add an entry" else null,
                actionDescription = "Add an entry",
                onAction = if (state.query.isEmpty()) {
                    { onTool("add") }
                } else null
            )
        },
        listContent = {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    position.y >= it.offset && position.y < it.offset + it.size
                                }
                                val hitKey = (hit?.key as? Int) ?: (hit?.key as? Long)?.toInt()
                                val card = latestCards.firstOrNull { it.index == hitKey }
                                if (card != null && card.canReorder) {
                                    draggingIndex = card.index
                                    pointerY = position.y
                                    grabOffset = position.y - checkNotNull(hit).offset
                                }
                            },
                            onDrag = { change, amount ->
                                if (draggingIndex != null) {
                                    change.consume()
                                    pointerY += amount.y
                                    moveIfNeeded()
                                }
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() }
                        )
                    }
            ) {
                items(state.cards, key = { it.index }) { card ->
                    val dragging = draggingIndex == card.index
                    SharedVaultDraggedItemBox(
                        dragging = dragging,
                        pointerY = pointerY,
                        grabOffset = grabOffset,
                        itemOffsetPx = {
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == card.index }?.offset?.toFloat() ?: 0f
                        },
                        activeShadowElevation = 12f
                    ) {
                        PortableCardView(
                            card = card,
                            hideIssuer = state.hiddenTitleEnabled,
                            cardLayout = cardLayout,
                            labelDisplay = state.labelDisplay,
                            showThumbnails = state.showThumbnails,
                            customThumbnailSizeDp = state.customThumbnailSizeDp,
                            labelSizeSp = state.labelSizeSp.takeIf { it > 0 },
                            highlightExpiringTokens = state.highlightExpiringTokens,
                            tapSingle = state.tapSingle,
                            tapDouble = state.tapDouble,
                            artwork = artwork,
                            onCard = onCard
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun PortableCardView(
    card: PortableCard,
    hideIssuer: Boolean,
    cardLayout: PortableCardLayout,
    labelDisplay: PortableLabelDisplay,
    showThumbnails: Boolean,
    customThumbnailSizeDp: Int,
    labelSizeSp: Int?,
    highlightExpiringTokens: Boolean,
    tapSingle: PortableTapMode,
    tapDouble: PortableTapMode,
    artwork: VaultArtwork?,
    onCard: (Int, String) -> Unit
) {
    val displayTitle = card.visibleTitle(hideIssuer)
    val displayMark = card.visibleMark(hideIssuer)
    val resolvedThumbSizeDp = cardLayout.effectiveThumbnailSizeDp(customThumbnailSizeDp)
    val verticalPad = cardLayout.cardVerticalPaddingDp
    val maxLines = labelDisplay.labelMaxLines(cardLayout)
    val stackCodeBelow = cardLayout.codeBelowIdentity
    val showCodeArea = card.hasCode || (card.pinRequired && card.revealed) || (card.counterLabel.isNotEmpty() && card.revealed)
    fun dispatchTap(mode: PortableTapMode) {
        when (mode) {
            PortableTapMode.NOTHING -> Unit
            PortableTapMode.REVEAL -> onCard(card.index, "reveal")
            PortableTapMode.COPY -> if (card.canCopy) onCard(card.index, "copy")
        }
    }
    val defaultRevealClick = if (tapSingle == PortableTapMode.REVEAL && tapDouble == PortableTapMode.NOTHING) {
        Modifier.clickable { onCard(card.index, "reveal") }
    } else {
        null
    }
    SharedVaultEntryCard(
        title = displayTitle,
        subtitle = card.subtitle,
        revealed = card.revealed,
        showThumbnail = showThumbnails,
        thumbnailSizeDp = resolvedThumbSizeDp,
        tileSeed = displayMark,
        artworkSlot = if (artwork != null) {
            {
                SharedIssuerArtwork(
                    thumbnailName = if (card.revealed || !hideIssuer) card.mark else "",
                    title = displayTitle,
                    sizeDp = resolvedThumbSizeDp,
                    artwork = artwork,
                    seed = displayMark
                )
            }
        } else null,
        scrollLabels = labelDisplay.scrollLabels,
        labelSizeSp = labelSizeSp,
        labelMaxLines = maxLines,
        verticalPaddingDp = verticalPad,
        outerVerticalPaddingDp = 4,
        codeBelowIdentity = stackCodeBelow,
        showCodeArea = showCodeArea,
        pillTags = if (card.revealed) card.tags else emptyList(),
        customPeriodLabel = card.customPeriodLabel.ifEmpty { null },
        detailLabel = card.visibleDetailLabel().ifEmpty { null },
        metadataLabel = card.metadata,
        metadataWarning = highlightExpiringTokens && card.isExpiring && card.revealed,
        revealedContent = card.visibleRevealedContent().ifEmpty { null },
        isNote = card.isNote,
        interactionModifier = defaultRevealClick,
        onClick = if (defaultRevealClick == null && (tapSingle != PortableTapMode.NOTHING || tapDouble != PortableTapMode.NOTHING)) {
            { dispatchTap(tapSingle) }
        } else null,
        onDoubleClick = if (defaultRevealClick == null && tapDouble != PortableTapMode.NOTHING) {
            { dispatchTap(tapDouble) }
        } else null,
        codeAreaContent = {
            PortableCardCodeArea(card = card, highlightExpiringTokens = highlightExpiringTokens)
        },
        actionsContent = {
            ForestTextAction(if (card.revealed) "Hide" else "Reveal",
                onClick = { onCard(card.index, "reveal") },
                contentDescription = "Show or hide the code for " + displayTitle)
            if (card.canCopy) {
                ForestTextAction(if (card.hasCode) "Copy Token" else "Copy",
                    onClick = { onCard(card.index, "copy") },
                    contentDescription = "Copy Token for " + displayTitle)
            }
            if (card.canCopyAccount) {
                ForestTextAction("Copy Account",
                    onClick = { onCard(card.index, "copyAccount") },
                    contentDescription = "Copy Account for " + displayTitle)
            }
            if (card.canCopyPassword) {
                ForestTextAction("Copy Password",
                    onClick = { onCard(card.index, "copyPassword") },
                    contentDescription = "Copy Password for " + displayTitle)
            }
            if (card.pinRequired) {
                ForestTextAction("Enter PIN", onClick = { onCard(card.index, "pin") },
                    contentDescription = "Enter PIN for " + displayTitle)
            }
            if (card.canReorder) {
                ForestTextAction("Move up", onClick = { onCard(card.index, "moveUp") },
                    contentDescription = "Move " + displayTitle + " up")
                ForestTextAction("Move down", onClick = { onCard(card.index, "moveDown") },
                    contentDescription = "Move " + displayTitle + " down")
            }
            if (card.canStep) ForestTextAction("Step",
                onClick = { onCard(card.index, "step") },
                onLongClick = { onCard(card.index, "counter") },
                contentDescription = "Step the counter")
            if (card.isEmail && card.revealed) {
                ForestTextAction("Connection details",
                    onClick = { onCard(card.index, "emailDetails") },
                    contentDescription = "Connection details for " + displayTitle)
            }
            if (card.revealed) {
                ForestTextAction("Edit", onClick = { onCard(card.index, "edit") },
                    contentDescription = "Edit " + displayTitle)
            }
        }
    )
}

@Composable
private fun PortableCardCodeArea(card: PortableCard, highlightExpiringTokens: Boolean = true) {
    val visibleCode = card.visibleCode()
    SharedVaultCodeArea(
        displayedToken = if (card.hasCode) {
            if (card.revealed) visibleCode else "••• •••"
        } else null,
        tokenVisible = card.revealed && card.hasCode,
        showsExpiry = highlightExpiringTokens && card.isExpiring,
        previousToken = card.previousCode.ifEmpty { null },
        showCountdown = card.hasCode && card.showCountdown && card.secondsLeft > 0,
        progress = card.countdownProgress,
        remainingSeconds = card.secondsLeft,
        showSecondsText = true,
        pinRequired = card.pinRequired && card.revealed,
        pinRequiredLabel = "PIN required",
        counterText = if (card.revealed) card.counterLabel.ifEmpty { null } else null,
        contentDescription = "Code " + card.visibleCode().toCharArray().joinToString(" "),
        previousSemanticsModifier = Modifier.semantics {
            contentDescription = "Previous token " + card.previousCode.toCharArray().joinToString(" ")
        }
    )
}

/**
 * One renderer for remaining portable row-based dialogs (such as encrypted backup sheets); the host
 * validates every change and action.
 */
@Composable
fun PortableSurfaceDialog(
    state: PortableSurfaceState,
    onChange: (String, String) -> Unit,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title, style = ForestType.title, color = ForestPalette.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.sections.forEach { section ->
                    if (section.title.isNotEmpty()) {
                        Text(section.title, style = ForestType.label, color = ForestPalette.accent)
                        Spacer(Modifier.height(4.dp))
                    }
                    section.rows.forEach { row -> PortableRowView(row, onChange, onAction) }
                    Spacer(Modifier.height(10.dp))
                }
                if (state.message.isNotEmpty()) {
                    Text(state.message, style = ForestType.body, color = ForestPalette.warning)
                }
            }
        },
        confirmButton = {
            if (state.showConfirm) ForestTextAction(
                state.confirmLabel, enabled = !state.busy, onClick = { onAction("confirm") }
            )
        },
        dismissButton = {
            ForestTextAction(state.dismissLabel, enabled = !state.busy, onClick = onDismiss)
        }
    )
}

@Composable
private fun PortableRowView(
    row: PortableRow,
    onChange: (String, String) -> Unit,
    onAction: (String) -> Unit
) {
    when (row.kind) {
        PortableRow.KIND_INFO -> {
            Text(row.title, style = ForestType.body, color = ForestPalette.textPrimary)
            if (row.value.isNotEmpty()) {
                Text(row.value, style = ForestType.label, color = ForestPalette.textSecondary)
            }
        }
        PortableRow.KIND_TOGGLE -> Row(
            Modifier.fillMaxWidth().clickable(row.enabled) { onAction(row.key) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.title, style = ForestType.body, color = ForestPalette.textPrimary,
                modifier = Modifier.weight(1f))
            Checkbox(checked = row.checked, onCheckedChange = null, enabled = row.enabled)
        }
        PortableRow.KIND_TEXT, PortableRow.KIND_SECURE -> OutlinedTextField(
            value = row.value,
            onValueChange = { onChange(row.key, it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            singleLine = true,
            enabled = row.enabled,
            label = { Text(row.title, style = ForestType.label) },
            visualTransformation = if (row.kind == PortableRow.KIND_SECURE) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None
        )
        else -> Row(
            Modifier.fillMaxWidth().clickable(row.enabled) { onAction(row.key) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = ForestType.body,
                    color = if (row.enabled) ForestPalette.textPrimary else ForestPalette.textHint)
                if (row.detail.isNotEmpty()) {
                    Text(row.detail, style = ForestType.label, color = ForestPalette.textSecondary)
                }
            }
            if (row.value.isNotEmpty()) {
                Text(row.value, style = ForestType.label, color = ForestPalette.textSecondary)
            }
        }
    }
}
