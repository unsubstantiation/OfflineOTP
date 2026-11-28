package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.*
import kotlinx.coroutines.delay

/** Sole card/action/code renderer. Native/JVM adapters supply values and commands, not widgets. */
@Composable
fun SharedVaultRenderedCard(content: VaultCardContent, artwork: VaultArtwork?, dispatch: (String) -> Unit) {
    SharedVaultEntryCard(
        title = content.title, subtitle = content.subtitle, revealed = content.revealed,
        showThumbnail = content.showThumbnail, thumbnailSizeDp = content.thumbnailSizeDp,
        tileSeed = content.tileSeed,
        artworkSlot = if (content.showThumbnail) { {
            SharedIssuerArtwork(thumbnailName = content.thumbnailName, title = content.title,
                sizeDp = content.thumbnailSizeDp, artwork = artwork, seed = content.tileSeed)
        } } else null,
        scrollLabels = content.scrollLabels, labelSizeSp = content.labelSizeSp, labelMaxLines = content.labelMaxLines,
        verticalPaddingDp = content.verticalPaddingDp, outerVerticalPaddingDp = content.outerVerticalPaddingDp,
        codeBelowIdentity = content.codeBelowIdentity, showCodeArea = content.showCodeArea,
        pillTags = content.tags, customPeriodLabel = content.customPeriodLabel,
        detailLabel = content.detailLabel, metadataLabel = content.metadataLabel.orEmpty(), metadataWarning = content.metadataWarning,
        revealedContent = content.revealedContent, isNote = content.isNote,
        onClick = content.onTap?.let { key -> { dispatch(key) } },
        onDoubleClick = content.onDoubleTap?.let { key -> { dispatch(key) } },
        codeAreaContent = {
            SharedVaultCodeArea(
                displayedToken = content.token, tokenVisible = content.tokenVisible, showsExpiry = content.expiring,
                previousToken = content.previousToken, showCountdown = content.showCountdown,
                progress = content.progress, remainingSeconds = content.remainingSeconds,
                showSecondsText = content.showSecondsText, pinRequired = content.pinRequired,
                pinRequiredLabel = content.pinRequiredLabel, counterText = content.counterText, noCodeLabel = content.noCodeLabel,
                contentDescription = if (content.tokenVisible) content.token?.let { "Code " + it.toCharArray().joinToString(" ") } else null
            )
        },
        actionsContent = {
            content.actions.forEach { command ->
                ForestTextAction(text = command.label, enabled = command.enabled, contentDescription = command.description,
                    onClick = { if (command.enabled) dispatch(command.key) },
                    onLongClick = command.longPress?.let { key -> { if (command.enabled) dispatch(key) } })
            }
        }
    )
}

/** Sole production list/drag implementation. Keys must be frame-stable identities, not visible row
 * positions. Filter/session changes cancel gestures. Callers own native mutation authorization.
 */
@Composable
fun <T> SharedVaultList(
    cards: List<T>, key: (T) -> Long, filterKey: Any?,
    canMove: (T, Int) -> Boolean, onMove: (T, Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
    spacingDp: Int = 8, shadow: Float = 8f,
    cardContent: @Composable (T) -> Unit
) {
    val listState = rememberLazyListState()
    val latestCards by rememberUpdatedState(cards)
    val latestCanMove by rememberUpdatedState(canMove)
    val latestOnMove by rememberUpdatedState(onMove)
    val drag = remember { VaultDragSession() }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var pointerY by remember { mutableStateOf(0f) }
    var grabOffset by remember { mutableStateOf(0f) }
    fun endDrag() { drag.cancel(); draggingId = null }
    fun moveIfNeeded() {
        val id = draggingId ?: return
        val current = latestCards
        val item = current.firstOrNull { key(it) == id } ?: run { endDrag(); return }
        val order = current.map(key)
        if (!drag.adopt(order)) { if (drag.held == null) endDrag(); return }
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            (info.key as? Long)?.let { PortableEntryDragBounds(it, info.offset.toFloat(), info.size.toFloat()) }
        }
        val direction = portableEntryDragDirection(id, pointerY, order, visible)
        if (direction != 0 && latestCanMove(item, direction) && drag.request(direction, order)) latestOnMove(item, direction)
    }
    LaunchedEffect(filterKey) { endDrag() }
    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            val info = listState.layoutInfo
            val scroll = sharedVaultViewportScroll(pointerY, info.viewportStartOffset, info.viewportEndOffset)
            if (!drag.waiting && scroll != 0f) listState.scrollBy(scroll)
            moveIfNeeded(); delay(16)
        }
    }
    LazyColumn(state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(spacingDp.dp),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
                    val item = latestCards.firstOrNull { key(it) == hit?.key }
                    if (item != null && (latestCanMove(item, -1) || latestCanMove(item, 1)) && drag.start(key(item), latestCards.map(key))) {
                        draggingId = key(item); pointerY = position.y; grabOffset = position.y - checkNotNull(hit).offset
                    }
                },
                onDrag = { change, amount -> if (draggingId != null) { change.consume(); pointerY += amount.y; moveIfNeeded() } },
                onDragEnd = { endDrag() }, onDragCancel = { endDrag() }
            )
        }
    ) {
        items(items = cards, key = key) { card ->
            SharedVaultDraggedItemBox(dragging = draggingId == key(card), pointerY = pointerY, grabOffset = grabOffset,
                itemOffsetPx = { (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key(card) }?.offset ?: 0).toFloat() },
                activeShadowElevation = shadow) { cardContent(card) }
        }
    }
}
