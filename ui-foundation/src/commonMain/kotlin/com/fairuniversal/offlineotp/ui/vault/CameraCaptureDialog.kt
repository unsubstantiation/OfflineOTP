package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** App-owned capture consent/configuration; the host owns the actual camera and cancellation. */
@Composable
fun CameraCaptureDialog(inputKey: Any, defaultDevice: String, onCapture: (String) -> Unit, onDismiss: () -> Unit) {
    var device by remember(inputKey) { mutableStateOf(defaultDevice) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Capture camera QR") },
        text = { ScrollableDialogBody {
            Text("Capture one frame, then review the hidden QR payload before importing. No image is saved. Position the QR code before capture; this adapter has no live preview.")
            Text("Requires locally installed FFmpeg with camera support and camera permission. Linux: /dev/video0; macOS: numeric camera index; Windows: exact DirectShow camera name. Image/URI import remains available if capture is unsupported.")
            OutlinedTextField(value = device, onValueChange = { if (it.length <= 256 && it.none(Char::isISOControl)) device = it },
                label = { Text("Camera device") }, singleLine = true)
        } }, confirmButton = { ForestTextAction("Capture one frame", enabled = device.isNotBlank(), onClick = { onCapture(device) }) },
        dismissButton = { ForestTextAction("Cancel", onClick = onDismiss) })
}
