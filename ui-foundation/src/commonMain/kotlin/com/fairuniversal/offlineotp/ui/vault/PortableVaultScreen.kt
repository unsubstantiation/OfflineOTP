package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.*
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

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
    val filterKey = listOf(
        state.tabs.indexOfFirst { it.selected },
        state.query,
        state.sortLabel,
        state.includeUntagged,
        state.tags.filter { it.selected }.joinToString("\n") { it.label }
    )
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
                    if (state.toolGroups.isEmpty()) {
                        ForestTextAction("Add", onClick = { onTool("add") }, contentDescription = "Add an entry")
                        ForestTextAction("Sort", onClick = { onTool("sort") }, contentDescription = "Sort entries")
                        ForestTextAction("Import", onClick = { onTool("backups") }, contentDescription = "Backups and links")
                        ForestTextAction("Settings", onClick = { onTool("settings") }, contentDescription = "Settings")
                    }
                    ForestTextAction("Lock", onClick = { onTool("lock") }, contentDescription = "Lock the vault")
                }
            )
            if (!state.hideGlobalTimeout && state.cards.isNotEmpty() && state.globalProgress > 0f) {
                SharedVaultGlobalProgressBar(
                    progress = state.globalProgress,
                    expiring = state.highlightExpiringTokens && state.globalExpiring
                )
            }
            if (state.toolGroups.isNotEmpty()) {
                SharedVaultToolsBar(
                    addLabel = "+ Add",
                    onAdd = { onTool("add") },
                    toolsLabel = "Tools",
                    closeLabel = "Close",
                    groups = state.toolGroups,
                    onSelectTool = onTool,
                    horizontalPaddingDp = 12,
                    addContentDescription = "Add an entry",
                    toolsContentDescription = "Tools and settings"
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
                            trailingContent = if (state.hasActiveFilter) {
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
                title = state.emptyTitle,
                description = state.emptyMessage,
                actionLabel = if (!state.hasActiveFilter) state.emptyActionLabel else null,
                actionDescription = "Add an entry",
                onAction = if (!state.hasActiveFilter) {
                    { onTool("add") }
                } else null
            )
        },
        listContent = {
            SharedVaultList(cards = state.cards, key = { it.stableId }, filterKey = filterKey,
                canMove = { card, _ -> card.canReorder },
                onMove = { card, direction -> onCard(card.index, if (direction < 0) "moveUp" else "moveDown") },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                spacingDp = 0, shadow = 12f
            ) { card ->
                SharedVaultRenderedCard(portableCardContent(card, state, cardLayout), artwork) { command -> onCard(card.index, command) }
            }
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
