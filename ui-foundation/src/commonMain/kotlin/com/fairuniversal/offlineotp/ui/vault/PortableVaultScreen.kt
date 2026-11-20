package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.portable.*
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

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
    onDismissToast: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(ForestPalette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)
        ) {
            state.tabs.forEachIndexed { position, tab ->
                ForestTextAction(
                    text = if (tab.count >= 0) "${tab.label} (${tab.count})" else tab.label,
                    onClick = { onTab(position) },
                    color = if (tab.selected) ForestPalette.accent else ForestPalette.textSecondary
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text("Search", style = ForestType.label) }
        )
        if (state.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ForestTextAction(
                    text = state.tagRule,
                    onClick = { onTool("tagRule") },
                    color = ForestPalette.textSecondary,
                    contentDescription = "How selected tags combine"
                )
                state.tags.forEach { tag ->
                    ForestTextAction(
                        text = if (tag.count >= 0) "${tag.label} (${tag.count})" else tag.label,
                        onClick = { onTag(tag.label) },
                        color = if (tag.selected) ForestPalette.accent else ForestPalette.textSecondary
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
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                items(state.cards, key = { it.index }) { card ->
                    PortableCardView(card, state.hiddenTitleEnabled, onCard)
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
    onCard: (Int, String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ForestPalette.surface)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ForestPalette.letterTileTint(card.mark))
            ) { Text(card.mark.take(1), color = ForestPalette.onPrimary) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(card.visibleTitle(hideIssuer), style = ForestType.body,
                    color = ForestPalette.textPrimary, maxLines = 1)
                Text(card.subtitle, style = ForestType.label, color = ForestPalette.textSecondary,
                    maxLines = 1)
            }
            if (card.hasCode) {
                Text(card.visibleCode(), style = ForestType.code, color = ForestPalette.otpAmber,
                    modifier = Modifier.semantics {
                        contentDescription = "Code " + card.visibleCode().toCharArray().joinToString(" ")
                    })
            }
        }
        if (card.previousCode.isNotEmpty()) {
            Text("Previous " + card.previousCode, style = ForestType.label,
                color = ForestPalette.textSecondary,
                modifier = Modifier.semantics {
                    contentDescription = "Previous token " + card.previousCode.toCharArray().joinToString(" ")
                })
        }
        if (card.metadata.isNotEmpty()) {
            Text(card.metadata, style = ForestType.label, color = ForestPalette.textSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.hasCode) {
                ForestTextAction(if (card.revealed) "Hide" else "Reveal",
                    onClick = { onCard(card.index, "reveal") },
                    contentDescription = "Show or hide the code for " + card.visibleTitle(hideIssuer))
                ForestTextAction("Copy", onClick = { onCard(card.index, "copy") },
                    contentDescription = "Copy Token for " + card.visibleTitle(hideIssuer))
            }
            if (card.canReorder) {
                ForestTextAction("Move up", onClick = { onCard(card.index, "moveUp") },
                    contentDescription = "Move " + card.visibleTitle(hideIssuer) + " up")
                ForestTextAction("Move down", onClick = { onCard(card.index, "moveDown") },
                    contentDescription = "Move " + card.visibleTitle(hideIssuer) + " down")
            }
            if (card.canStep) ForestTextAction("Step", onClick = { onCard(card.index, "step") },
                contentDescription = "Step the counter")
            ForestTextAction("Details", onClick = { onCard(card.index, "details") },
                contentDescription = "Shows entry details")
            ForestTextAction("Edit", onClick = { onCard(card.index, "edit") },
                contentDescription = "Edit " + card.visibleTitle(hideIssuer))
            ForestTextAction("More", onClick = { onCard(card.index, "more") },
                contentDescription = "More actions for " + card.visibleTitle(hideIssuer))
        }
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
