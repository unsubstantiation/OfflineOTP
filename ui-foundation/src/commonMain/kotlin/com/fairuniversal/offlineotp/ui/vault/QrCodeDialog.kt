package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared QR-code presentation dialog.
 *
 * The host generates and owns the [qrBitmap] or bit-matrix for an explicitly revealed OTP entry and
 * detaches the composition on vault lock so the code never outlives the session.
 */
@Composable
fun QrCodeDialog(
    title: String,
    qrBitmap: ImageBitmap,
    confirmLabel: String,
    onDismiss: () -> Unit
) {
    QrCodeDialogShell(title = title, confirmLabel = confirmLabel, onDismiss = onDismiss) {
        Image(
            bitmap = qrBitmap,
            contentDescription = title,
            modifier = Modifier.size(220.dp),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * Matrix-backed overload of [QrCodeDialog] for native hosts (such as iOS) that supply a compact
 * `'1'`/`'0'` module grid rather than a platform bitmap.
 */
@Composable
fun QrCodeDialog(
    title: String,
    matrixSize: Int,
    matrixBits: String,
    confirmLabel: String,
    onDismiss: () -> Unit
) {
    QrCodeDialogShell(title = title, confirmLabel = confirmLabel, onDismiss = onDismiss) {
        Canvas(modifier = Modifier.size(220.dp)) {
            drawRect(color = Color(0xFFFFFFFF), topLeft = Offset.Zero, size = size)
            if (matrixSize > 0 && matrixBits.length >= matrixSize * matrixSize) {
                val cellWidth = size.width / matrixSize.toFloat()
                val cellHeight = size.height / matrixSize.toFloat()
                val dark = Color(0xFF000000)
                for (row in 0 until matrixSize) {
                    val rowOffset = row * matrixSize
                    for (col in 0 until matrixSize) {
                        if (matrixBits[rowOffset + col] == '1') {
                            drawRect(
                                color = dark,
                                topLeft = Offset(col * cellWidth, row * cellHeight),
                                size = Size(cellWidth + 0.5f, cellHeight + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeDialogShell(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = ForestType.title, color = ForestPalette.textPrimary) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFFFFF))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    content()
                }
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, onClick = onDismiss)
        }
    )
}
