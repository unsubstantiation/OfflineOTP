package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * The pieces the vault screen is built from.
 *
 * They are deliberately small and stateless: each takes plain values and callbacks, so the same
 * row renders identically whether it was produced by the Android session or by the desktop vault,
 * and a screen-level change is a change here rather than in two toolkits. No component reads the
 * clock, the settings store or the vault — that all arrives in the [VaultUiState].
 */

/** App bar: window title, entry count and the lock action. */
@Composable
internal fun VaultTopBar(
    title: String,
    countLabel: String,
    lockLabel: String,
    onLock: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForestPalette.appBar)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary)
            if (countLabel.isNotEmpty()) {
                Text(text = countLabel, style = ForestType.label, color = ForestPalette.accent)
            }
        }
        ForestTextAction(text = lockLabel, onClick = onLock)
    }
}

/** Search box. The query is host state, so a rebuild after a lock clears the field with it. */
@Composable
internal fun VaultSearchField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(text = hint, style = ForestType.body, color = ForestPalette.textHint) },
        shape = RoundedCornerShape(12.dp)
    )
}

/** The five kind tabs, in `EntryListPresentation.Category` order. */
@Composable
internal fun VaultCategoryTabs(
    tabs: List<VaultCategoryTab>,
    strings: VaultStrings,
    onSelect: (EntryListPresentation.Category) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (tab in tabs) {
            VaultTab(
                label = strings.categoryCount(tab.category, tab.count),
                selected = tab.selected,
                onClick = { onSelect(tab.category) }
            )
        }
    }
}

@Composable
private fun VaultTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) ForestPalette.accent else ForestPalette.textSecondary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = ForestType.tab, color = color)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .background(if (selected) ForestPalette.accent else Color.Transparent)
        )
    }
}

/**
 * Order selector: one tappable label per sort mode, the active one highlighted.
 *
 * A dropdown would need a popup surface; a strip keeps the whole menu visible, matches the tag
 * chips beside it, and lets the same list be reached with a keyboard on desktop and a screen reader
 * on Android. The host decides whether the choice is persisted.
 */
@Composable
internal fun VaultSortBar(
    options: List<VaultSortOption>,
    label: String,
    strings: VaultStrings,
    onSelect: (Constants.SortMode) -> Unit
) {
    if (options.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = ForestType.label,
            color = ForestPalette.textHint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        for (option in options) {
            Text(
                text = strings.sortTitle(option.mode),
                style = ForestType.label,
                color = if (option.selected) ForestPalette.accent else ForestPalette.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(option.mode) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Tag filter chips. The untagged pseudo chip reports through its own callback, so it can never be
 * confused with a real tag that happens to be called "Untagged".
 */
@Composable
internal fun VaultTagChips(
    chips: List<VaultTagChip>,
    strings: VaultStrings,
    clearLabel: String,
    showClear: Boolean,
    actions: VaultActions
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (chip in chips) {
            VaultTagChipView(
                label = strings.tagCount(chip.name, chip.count),
                selected = chip.selected,
                onClick = {
                    if (chip.isUntagged) actions.onToggleUntagged() else actions.onToggleTag(chip.name)
                }
            )
        }
        if (showClear) {
            ForestTextAction(text = clearLabel, onClick = actions.onClearFilters)
        }
    }
}

@Composable
private fun VaultTagChipView(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = ForestType.label,
        color = if (selected) ForestPalette.onPrimary else ForestPalette.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ForestPalette.surfaceVariant)
            .background(if (selected) ForestPalette.accent else Color.Transparent)
            .border(1.dp, if (selected) ForestPalette.accent else ForestPalette.divider, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/**
 * One vault row: brand tile, issuer/label, tags, the code area and the row actions.
 *
 * A code is obscured until the row is revealed (`tokenVisible`), which is the privacy default both
 * existing clients already enforce; copying is still available, because the host holds the rows and
 * the user asked to copy this one.
 */
@Composable
internal fun VaultEntryCard(
    card: VaultCardState,
    strings: VaultStrings,
    appearance: VaultAppearance,
    actions: VaultActions
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (card.revealed) ForestPalette.surfaceElevated else ForestPalette.surface)
            .border(1.dp, ForestPalette.divider, shape)
            .clickable { actions.onToggleReveal(card) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (appearance.showThumbnails) {
                VaultLetterTile(seed = card.thumbnailName.ifBlank { card.title }, title = card.title)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = ForestType.title,
                    color = ForestPalette.textPrimary,
                    maxLines = labelLines(appearance),
                    overflow = TextOverflow.Ellipsis
                )
                if (card.subtitle.isNotEmpty()) {
                    Text(
                        text = card.subtitle,
                        style = ForestType.subtitle,
                        color = ForestPalette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (card.tags.isNotEmpty()) {
                    Text(
                        text = card.tags.joinToString(" · "),
                        style = ForestType.label,
                        color = ForestPalette.textHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Read once into a local: a property from another module cannot be smart-cast.
                val revealedContent = card.revealedContent
                if (revealedContent != null) {
                    Text(
                        text = revealedContent,
                        style = ForestType.mono,
                        color = ForestPalette.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            VaultCodeArea(card = card, strings = strings)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (card.hasToken) {
                ForestTextAction(
                    text = strings.copyAction,
                    enabled = card.copyable,
                    onClick = { actions.onCopyToken(card) }
                )
            }
            val nextCounter = actions.onNextCounter
            if (card.counter != null && nextCounter != null) {
                ForestTextAction(text = strings.nextCode, onClick = { nextCounter(card) })
            }
            if (card.hasToken || card.kind != VaultEntryKind.OTP) {
                ForestTextAction(
                    text = if (card.revealed) strings.hideAction else strings.revealAction,
                    onClick = { actions.onToggleReveal(card) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            val entryActions = actions.onEntryActions
            if (entryActions != null) {
                ForestTextAction(
                    text = strings.entryActions,
                    color = ForestPalette.textSecondary,
                    onClick = { entryActions(card) }
                )
            }
        }
    }
}

/** Code, previous code, countdown ring, HOTP counter or mOTP PIN prompt. */
@Composable
private fun VaultCodeArea(card: VaultCardState, strings: VaultStrings) {
    val codeColor = if (card.showsExpiry) ForestPalette.warning else ForestPalette.otpAmber
    // Locals, because these properties come from :ui-model and cannot be smart-cast here.
    val token = card.token
    val previousToken = card.previousToken
    Column(horizontalAlignment = Alignment.End) {
        when {
            token != null -> {
                Text(
                    text = if (card.tokenVisible) token else TokenDisplay.mask(token),
                    style = ForestType.code,
                    color = if (card.tokenVisible) codeColor else ForestPalette.textHint,
                    maxLines = 1
                )
                if (previousToken != null && card.tokenVisible) {
                    Text(
                        text = previousToken,
                        style = ForestType.previousCode,
                        color = ForestPalette.otpAmberMuted,
                        maxLines = 1
                    )
                }
                if (card.hasCountdown) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountdownRing(
                            progress = card.progress ?: 0f,
                            expiring = card.showsExpiry,
                            diameter = 16.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${card.remainingSeconds}s",
                            style = ForestType.label,
                            color = ForestPalette.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
            card.pinRequired -> Text(
                text = strings.pinRequired,
                style = ForestType.label,
                color = ForestPalette.textHint,
                maxLines = 1
            )
            card.counter != null -> Text(
                text = "${strings.counterLabel} ${card.counter}",
                style = ForestType.mono,
                color = ForestPalette.textSecondary,
                maxLines = 1
            )
            else -> Text(
                text = strings.noCode,
                style = ForestType.label,
                color = ForestPalette.textHint,
                maxLines = 1
            )
        }
    }
}

/** Circular "time left in this step" indicator. */
@Composable
internal fun CountdownRing(progress: Float, expiring: Boolean, diameter: Dp) {
    val sweepColor = if (expiring) ForestPalette.warning else ForestPalette.otpAmber
    Canvas(modifier = Modifier.size(diameter)) {
        val strokeWidth = size.minDimension / 7f
        val inset = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        drawArc(
            color = ForestPalette.otpAmberMuted,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = sweepColor,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/** Issuer artwork stand-in: a coloured tile with the account's initial. */
@Composable
private fun VaultLetterTile(seed: String, title: String) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(ForestPalette.letterTileTint(seed)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.trim().take(1).uppercase(),
            style = ForestType.title,
            color = ForestPalette.thumbnailBackground
        )
    }
}

/** A clickable label, the only button style the shared UI uses. */
@Composable
internal fun ForestTextAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = ForestPalette.accent
) {
    Text(
        text = text,
        style = ForestType.label,
        color = if (enabled) color else ForestPalette.textHint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Centred message for an empty vault, a filtered miss or an empty tab. */
@Composable
internal fun VaultEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = description, style = ForestType.body, color = ForestPalette.textSecondary)
    }
}

/** How many lines a title may take, from the user's label-display setting. */
private fun labelLines(appearance: VaultAppearance): Int = when (appearance.labelDisplay) {
    Constants.LabelDisplay.MULTILINE -> 2
    Constants.LabelDisplay.TRUNCATE, Constants.LabelDisplay.SCROLL -> 1
}
