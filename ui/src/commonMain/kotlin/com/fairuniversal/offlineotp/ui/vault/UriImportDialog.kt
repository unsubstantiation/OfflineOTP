package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fairuniversal.offlineotp.Utilities.InputPolicy

/** URI text contains seeds; never save it or echo parser details into the result text. */
@Composable
fun UriImportDialog(inputKey: Any, message: String?, collecting: Boolean,
                    onSubmit: (String, Boolean) -> Unit, onDismiss: () -> Unit,
                    imageBusy: Boolean = false, imageReady: Boolean = false, imageFailed: Boolean = false,
                    onChooseImage: (() -> Unit)? = null, onDiscardImage: () -> Unit = {},
                    onImportImage: (Boolean) -> Unit = {}) {
    var uri by remember(inputKey) { mutableStateOf("") }
    var allowAdditional by remember(inputKey) { mutableStateOf(false) }
    fun dismiss() { uri = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Import OTP URI") },
        text = { Column {
            Text(text = "Paste an otpauth:// URI or each part of an otpauth-migration:// export. Import saves accounts to this vault.")
            onChooseImage?.let { choose -> ForestTextAction(text = "Choose QR image", enabled = !imageBusy,
                onClick = { uri = ""; choose() }) }
            if (imageBusy) Text(text = "Reading QR image…")
            if (imageReady) {
                Text(text = "A QR payload is ready (hidden). Choose Import to validate and save it.")
                ForestTextAction(text = "Discard image payload", onClick = onDiscardImage)
            }
            if (imageFailed) Text(text = "Could not read a supported QR image. Check the image and its size.")
            OutlinedTextField(value = uri, enabled = !imageBusy && !imageReady, onValueChange = { if (it.length <= InputPolicy.MAX_URI_CHARS) uri = it },
                label = { Text(text = "Secret OTP URI") }, visualTransformation = PasswordVisualTransformation())
            ForestTextAction(text = if (allowAdditional) "Additional accounts for existing issuers: allowed" else "Additional accounts for existing issuers: excluded",
                enabled = !collecting, onClick = { allowAdditional = !allowAdditional })
            if (collecting) Text(text = "Issuer policy is fixed for this batch. Cancel discards all buffered parts.")
            if (message != null) Text(text = message)
        } },
        confirmButton = { ForestTextAction(text = "Import", enabled = !imageBusy && (imageReady || uri.isNotBlank()), onClick = {
            if (imageReady) { uri = ""; onImportImage(allowAdditional) }
            else { val submitted = uri; uri = ""; onSubmit(submitted, allowAdditional) }
        }) },
        dismissButton = { ForestTextAction(text = "Close / cancel batch", onClick = { dismiss() }) })
}
