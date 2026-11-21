package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
 * The unlocked vault, drawn from values the host has already projected. No entry, session, file or
 * engine type appears here: the iOS adapter builds [PortableVaultState] and re-checks every action.
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
    cardLayout: PortableCardLayout = PortableCardLayout.DEFAULT
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
            val edge = 56f
            val scroll = when {
                pointerY < info.viewportStartOffset + edge -> -12f
                pointerY > info.viewportEndOffset - edge -> 12f
                else -> 0f
            }
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
    Column(Modifier.fillMaxSize().background(ForestPalette.background)) {
        Row(
            Modifier.fillMaxWidth().background(ForestPalette.appBar).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OfflineOTP", style = ForestType.title, color = ForestPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            ForestTextAction("Add", onClick = { onTool("add") }, contentDescription = "Add an entry")
            ForestTextAction("Sort", onClick = { onTool("sort") }, contentDescription = "Sort entries")
            ForestTextAction("Import", onClick = { onTool("backups") }, contentDescription = "Backups and links")
            ForestTextAction("Settings", onClick = { onTool("settings") }, contentDescription = "Settings")
            ForestTextAction("Lock", onClick = { onTool("lock") }, contentDescription = "Lock the vault")
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            state.tabs.forEachIndexed { position, tab ->
                SharedVaultTab(
                    label = if (tab.count >= 0) "${tab.label} (${tab.count})" else tab.label,
                    selected = tab.selected,
                    onClick = { onTab(position) }
                )
            }
        }
        SharedVaultSearchField(
            value = state.query,
            hint = "Search",
            onValueChange = onQuery,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        if (state.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ForestTextAction(
                    text = state.tagRule,
                    onClick = { onTool("tagRule") },
                    color = ForestPalette.textSecondary,
                    contentDescription = "How selected tags combine"
                )
                state.tags.forEach { tag ->
                    SharedVaultTagChip(
                        label = if (tag.count >= 0) "${tag.label} (${tag.count})" else tag.label,
                        selected = tag.selected,
                        onClick = { onTag(tag.label) }
                    )
                }
                if (state.includeUntagged || state.tags.any { it.selected }) {
                    ForestTextAction("Clear", onClick = onClearTags, color = ForestPalette.warning,
                        contentDescription = "Clear the search and tag filters")
                }
            }
        }
        if (state.cards.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.emptyMessage, style = ForestType.body, color = ForestPalette.textSecondary)
                ForestTextAction("Add an entry", onClick = { onTool("add") },
                    contentDescription = "Add an entry")
            }
        } else {
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
                    Box(
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    val top = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == card.index }?.offset?.toFloat() ?: 0f
                                    translationY = pointerY - grabOffset - top
                                    shadowElevation = 12f
                                }
                            }
                    ) {
                        PortableCardView(card, state.hiddenTitleEnabled, cardLayout, onCard)
                    }
                }
            }
        }
    }
    if (state.toast.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                state.toast,
                style = ForestType.body,
                color = ForestPalette.textPrimary,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ForestPalette.surfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clickable(onClick = onDismissToast)
            )
        }
    }
}

@Composable
private fun PortableCardView(
    card: PortableCard,
    hideIssuer: Boolean,
    cardLayout: PortableCardLayout,
    onCard: (Int, String) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val displayTitle = card.visibleTitle(hideIssuer)
    val verticalPad = if (cardLayout == PortableCardLayout.COMPACT) 8.dp else 12.dp
    val maxLines = if (cardLayout == PortableCardLayout.FULL) 3 else 1
    val stackCodeBelow = cardLayout == PortableCardLayout.FULL
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (card.revealed) ForestPalette.surfaceElevated else ForestPalette.surface)
            .border(1.dp, ForestPalette.divider, shape)
            .clickable { onCard(card.index, "reveal") }
            .padding(horizontal = 14.dp, vertical = verticalPad)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VaultLetterTile(seed = card.mark, title = card.mark, sizeDp = 38f)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayTitle, style = ForestType.body, color = ForestPalette.textPrimary, maxLines = maxLines)
                if (card.subtitle.isNotEmpty()) {
                    Text(card.subtitle, style = ForestType.label, color = ForestPalette.textSecondary, maxLines = maxLines)
                }
            }
            if (!stackCodeBelow) {
                PortableCardCodeArea(card = card)
            }
        }
        if (stackCodeBelow && (card.hasCode || (card.pinRequired && card.revealed))) {
            Spacer(Modifier.height(6.dp))
            PortableCardCodeArea(card = card)
        }
        if (card.previousCode.isNotEmpty()) {
            Text("Previous " + card.previousCode, style = ForestType.label,
                color = ForestPalette.textSecondary,
                modifier = Modifier.semantics {
                    contentDescription = "Previous token " + card.previousCode.toCharArray().joinToString(" ")
                })
        }
        if (card.metadata.isNotEmpty()) {
            Text(
                card.metadata,
                style = ForestType.label,
                color = if (card.isExpiring && card.revealed) ForestPalette.warning else ForestPalette.textSecondary
            )
        }
        if (card.revealed && card.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                card.tags.forEach { tag ->
                    Text(
                        tag,
                        style = ForestType.label,
                        color = ForestPalette.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ForestPalette.surfaceVariant)
                            .border(1.dp, ForestPalette.divider, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            if (card.canStep) ForestTextAction("Step", onClick = { onCard(card.index, "step") },
                contentDescription = "Step the counter")
            if (card.revealed) {
                ForestTextAction("Edit", onClick = { onCard(card.index, "edit") },
                    contentDescription = "Edit " + displayTitle)
            }
        }
    }
}

@Composable
private fun PortableCardCodeArea(card: PortableCard) {
    if (card.hasCode) {
        if (card.revealed) {
            val codeColor = if (card.isExpiring) ForestPalette.warning else ForestPalette.otpAmber
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.secondsLeft > 0) {
                    CountdownRing(
                        progress = card.countdownProgress,
                        expiring = card.isExpiring,
                        diameter = 16.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(card.visibleCode(), style = ForestType.code, color = codeColor,
                    modifier = Modifier.semantics {
                        contentDescription = "Code " + card.visibleCode().toCharArray().joinToString(" ")
                    })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.secondsLeft > 0) {
                    CountdownRing(
                        progress = card.countdownProgress,
                        expiring = card.isExpiring,
                        diameter = 16.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text("••• •••", style = ForestType.code, color = ForestPalette.otpAmberMuted)
            }
        }
    } else if (card.pinRequired && card.revealed) {
        Text("PIN required", style = ForestType.label, color = ForestPalette.warning)
    }
}

/**
 * One renderer for every portable dialog: settings, backup/transfer, migration, entry editing and
 * detail sheets all arrive as sections of rows. The host validates every change and action.
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
