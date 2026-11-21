package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Shared search input used by Android, Desktop and iOS vault screens. */
@Composable
fun SharedVaultSearchField(
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
