package com.fairuniversal.offlineotp.ui.vault

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestPalette
import com.fairuniversal.offlineotp.ui.theme.ForestType

/**
 * Shared QR-code presentation dialog.
 *
 * The host generates and owns the [qrBitmap] for an explicitly revealed OTP entry and detaches the
 * composition on vault lock so the image never outlives the session.
 */
@Composable
fun QrCodeDialog(
    title: String,
    qrBitmap: ImageBitmap,
    confirmLabel: String,
    onDismiss: () -> Unit
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
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = title,
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, onClick = onDismiss)
        }
    )
}
