package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Shared app bar across Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultTopBar(
    title: String,
    countLabel: String = "",
    horizontalPaddingDp: Int = 16,
    verticalPaddingDp: Int = 12,
    trailingActions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForestPalette.appBar)
            .padding(horizontal = horizontalPaddingDp.dp, vertical = verticalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary)
            if (countLabel.isNotEmpty()) {
                Text(text = countLabel, style = ForestType.label, color = ForestPalette.accent)
            }
        }
        trailingActions?.invoke(this)
    }
}

/** Shared search input used by Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultSearchField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = false,
    focusKey: Any = Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusOnStart, focusKey) {
        if (focusOnStart) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = true,
        placeholder = { Text(text = hint, style = ForestType.body, color = ForestPalette.textHint) },
        shape = RoundedCornerShape(12.dp)
    )
}

/** Shared underlined category tab used by Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultTab(label: String, selected: Boolean, onClick: () -> Unit) {
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

/** Shared horizontal category-tab strip across Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultCategoryBar(
    tabs: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    horizontalPaddingDp: Int = 8
) {
    if (tabs.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPaddingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        tabs.forEachIndexed { index, (label, selected) ->
            SharedVaultTab(
                label = label,
                selected = selected,
                onClick = { onSelect(index) }
            )
        }
    }
}

/** Shared rounded pill tag filter chip used by Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultTagChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

/** Shared horizontal tag-filter strip across Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultTagBar(
    chips: List<Pair<String, Boolean>>,
    onToggleChip: (Int) -> Unit,
    horizontalPaddingDp: Int = 16,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    if (chips.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPaddingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingContent?.invoke()
        chips.forEachIndexed { index, (label, selected) ->
            SharedVaultTagChip(
                label = label,
                selected = selected,
                onClick = { onToggleChip(index) }
            )
        }
        trailingContent?.invoke()
    }
}

/**
 * Shared filter/navigation header container across Android, Desktop and iOS vault screens,
 * keeping search, category tabs, tag filter chips and sort options in one consistent layout.
 */
@Composable
fun SharedVaultFilterHeader(
    horizontalPaddingDp: Int = 16,
    verticalPaddingDp: Int = 10,
    itemSpacingDp: Int = 10,
    searchContent: @Composable () -> Unit,
    categoryContent: @Composable () -> Unit,
    tagContent: (@Composable () -> Unit)? = null,
    sortContent: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPaddingDp.dp, vertical = verticalPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(itemSpacingDp.dp)
    ) {
        searchContent()
        categoryContent()
        tagContent?.invoke()
        sortContent?.invoke()
    }
}

/** Shared horizontal sort-mode strip across Compose vault screens. */
@Composable
fun SharedVaultSortBar(
    label: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit
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
        options.forEachIndexed { index, (optionLabel, selected) ->
            Text(
                text = optionLabel,
                style = ForestType.label,
                color = if (selected) ForestPalette.accent else ForestPalette.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/** Shared centred empty/filtered vault message across Android, Desktop and iOS. */
@Composable
fun SharedVaultEmptyState(
    title: String,
    description: String = "",
    actionLabel: String? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (title.isNotEmpty()) {
            Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary)
        }
        if (description.isNotEmpty()) {
            if (title.isNotEmpty()) Spacer(modifier = Modifier.height(6.dp))
            Text(text = description, style = ForestType.body, color = ForestPalette.textSecondary)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            ForestTextAction(
                text = actionLabel,
                onClick = onAction,
                contentDescription = actionDescription
            )
        }
    }
}

/** Sharedvault card shell across Android, Desktop and iOS. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedVaultEntryCard(
    title: String,
    subtitle: String = "",
    revealed: Boolean,
    showThumbnail: Boolean = true,
    thumbnailSizeDp: Float = 38f,
    tileSeed: String = title,
    artworkSlot: (@Composable () -> Unit)? = null,
    scrollLabels: Boolean = false,
    labelSizeSp: Int? = null,
    labelMaxLines: Int = 1,
    verticalPaddingDp: Int = 12,
    outerVerticalPaddingDp: Int = 0,
    codeBelowIdentity: Boolean = false,
    showCodeArea: Boolean = false,
    inlineTagsSummary: String = "",
    pillTags: List<String> = emptyList(),
    customPeriodLabel: String? = null,
    detailLabel: String? = null,
    metadataLabel: String = "",
    metadataWarning: Boolean = false,
    revealedContent: String? = null,
    isNote: Boolean = false,
    interactionModifier: Modifier? = null,
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    codeAreaContent: (@Composable () -> Unit)? = null,
    actionsContent: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val textModifier = if (scrollLabels) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
    val clickMod = when {
        interactionModifier != null -> interactionModifier
        onClick != null && onDoubleClick != null -> Modifier.combinedClickable(
            onClick = onClick,
            onDoubleClick = onDoubleClick
        )
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = outerVerticalPaddingDp.dp)
            .clip(shape)
            .background(if (revealed) ForestPalette.surfaceElevated else ForestPalette.surface)
            .border(1.dp, ForestPalette.divider, shape)
            .let { base -> if (clickMod === Modifier) base else base.then(clickMod) }
            .padding(horizontal = 14.dp, vertical = verticalPaddingDp.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (showThumbnail) {
                if (artworkSlot != null) {
                    artworkSlot()
                } else {
                    VaultLetterTile(seed = tileSeed, title = title, sizeDp = thumbnailSizeDp)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier = textModifier,
                    fontSize = labelSizeSp?.sp ?: TextUnit.Unspecified,
                    style = ForestType.title,
                    color = ForestPalette.textPrimary,
                    maxLines = labelMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        modifier = textModifier,
                        fontSize = labelSizeSp?.sp ?: TextUnit.Unspecified,
                        style = ForestType.subtitle,
                        color = ForestPalette.textSecondary,
                        maxLines = labelMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (inlineTagsSummary.isNotEmpty()) {
                    Text(
                        text = inlineTagsSummary,
                        fontSize = labelSizeSp?.let { (it * 0.75f).sp } ?: TextUnit.Unspecified,
                        style = ForestType.label,
                        color = ForestPalette.textHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!customPeriodLabel.isNullOrEmpty()) {
                    Text(text = customPeriodLabel, style = ForestType.label, color = ForestPalette.textHint)
                }
                if (!detailLabel.isNullOrEmpty()) {
                    Text(text = detailLabel, style = ForestType.label, color = ForestPalette.textSecondary)
                }
                if (!revealedContent.isNullOrEmpty()) {
                    Text(
                        text = revealedContent,
                        style = ForestType.mono,
                        color = ForestPalette.textSecondary,
                        maxLines = if (isNote) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showCodeArea && !codeBelowIdentity && codeAreaContent != null) {
                Spacer(modifier = Modifier.width(12.dp))
                codeAreaContent()
            }
        }
        if (showCodeArea && codeBelowIdentity && codeAreaContent != null) {
            Spacer(modifier = Modifier.height(8.dp))
            codeAreaContent()
        }
        if (metadataLabel.isNotEmpty()) {
            Text(
                text = metadataLabel,
                style = ForestType.label,
                color = if (metadataWarning) ForestPalette.warning else ForestPalette.textSecondary
            )
        }
        if (pillTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pillTags.forEach { tag ->
                    Text(
                        text = tag,
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
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actionsContent
        )
    }
}

/** Shared OTP code, previous code, countdown ring, HOTP counter and mOTP PIN display across platforms. */
@Composable
fun SharedVaultCodeArea(
    displayedToken: String?,
    tokenVisible: Boolean,
    showsExpiry: Boolean = false,
    previousToken: String? = null,
    showCountdown: Boolean = false,
    progress: Float = 0f,
    remainingSeconds: Int = 0,
    showSecondsText: Boolean = true,
    pinRequired: Boolean = false,
    pinRequiredLabel: String = "PIN required",
    counterText: String? = null,
    noCodeLabel: String = "",
    contentDescription: String? = null,
    previousSemanticsModifier: Modifier? = null
) {
    val codeColor = if (showsExpiry) ForestPalette.warning else ForestPalette.otpAmber
    Column(horizontalAlignment = Alignment.End) {
        when {
            displayedToken != null -> {
                Text(
                    text = displayedToken,
                    style = ForestType.code,
                    color = if (tokenVisible) codeColor else ForestPalette.textHint,
                    maxLines = 1,
                    modifier = if (tokenVisible) {
                        val desc = contentDescription ?: ("Code " + displayedToken.toCharArray().joinToString(" "))
                        Modifier.semantics { this.contentDescription = desc }
                    } else {
                        Modifier
                    }
                )
                if (!previousToken.isNullOrEmpty() && tokenVisible) {
                    val prevModifier = previousSemanticsModifier ?: Modifier.semantics {
                        this.contentDescription = "Previous token " + previousToken.toCharArray().joinToString(" ")
                    }
                    Text(
                        text = previousToken,
                        style = ForestType.previousCode,
                        color = ForestPalette.otpAmberMuted,
                        maxLines = 1,
                        modifier = prevModifier
                    )
                }
                if (showCountdown) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountdownRing(
                            progress = progress,
                            expiring = showsExpiry,
                            diameter = 16.dp
                        )
                        if (showSecondsText) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${remainingSeconds}s",
                                style = ForestType.label,
                                color = ForestPalette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            pinRequired -> Text(
                text = pinRequiredLabel,
                style = ForestType.label,
                color = ForestPalette.warning,
                maxLines = 1
            )
            counterText != null -> Text(
                text = counterText,
                style = ForestType.mono,
                color = ForestPalette.textSecondary,
                maxLines = 1
            )
            noCodeLabel.isNotEmpty() -> Text(
                text = noCodeLabel,
                style = ForestType.label,
                color = ForestPalette.textHint,
                maxLines = 1
            )
        }
    }
}

/** Shared circular "time left in this step" indicator across Android, Desktop and iOS. */
@Composable
fun CountdownRing(progress: Float, expiring: Boolean, diameter: Dp) {
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

/** Shared 30-second vault rollover progress bar across Android, Desktop and iOS. */
@Composable
fun SharedVaultGlobalProgressBar(
    progress: Float,
    expiring: Boolean,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val barColor = if (expiring) ForestPalette.warning else ForestPalette.otpAmber
    Canvas(modifier = modifier.fillMaxWidth().height(3.dp)) {
        drawRect(color = ForestPalette.otpAmberMuted, size = size)
        drawRect(
            color = barColor,
            size = Size(size.width * clamped, size.height)
        )
    }
}

/** Shared circular initial tile used when an entry has no resolved brand mark. */
@Composable
fun VaultLetterTile(seed: String, title: String, sizeDp: Float) {
    Box(
        modifier = Modifier
            .size(sizeDp.coerceAtLeast(0f).dp)
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
