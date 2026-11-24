package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/** Provider policy presentation only; no provider binding, key resolution or cryptography. */
@Composable
fun OpenPgpProviderOptionsDialog(
    inputKey: Any, initialRecipients: String, initialVerify: Boolean,
    title: String, recipientsLabel: String, recipientsDescription: String,
    verifyLabel: String, verifyDescription: String, confirmLabel: String, dismissLabel: String,
    onSave: (String, Boolean) -> Unit, onDismiss: () -> Unit
) {
    var recipients by remember(inputKey) { mutableStateOf(initialRecipients) }
    var verify by remember(inputKey) { mutableStateOf(initialVerify) }
    fun dismiss() { recipients = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(title) },
        text = { ScrollableDialogBody {
            Text(recipientsDescription)
            OutlinedTextField(value = recipients,
                onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) recipients = it },
                label = { Text(recipientsLabel) })
            Text(verifyLabel)
            Checkbox(checked = verify, onCheckedChange = { verify = it })
            Text(verifyDescription)
        } },
        confirmButton = { ForestTextAction(text = confirmLabel,
            enabled = recipients.length <= PortableInputBounds.MAX_TEXT_CHARS,
            onClick = { val value = recipients; recipients = ""; onSave(value, verify) }) },
        dismissButton = { ForestTextAction(text = dismissLabel, onClick = { dismiss() }) })
}
