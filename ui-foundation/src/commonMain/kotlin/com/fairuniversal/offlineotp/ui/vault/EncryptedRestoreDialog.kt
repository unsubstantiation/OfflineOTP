package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

@Composable
fun EncryptedRestoreDialog(inputKey: Any, busy: Boolean, count: Int?, failed: Boolean,
                           onDecode: (String) -> Unit, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit,
                           onDecodeSelected: ((String, String, String) -> Unit)? = null,
                           decodeDescription: String = "Open the selected full backup. You will confirm before entries are changed.") {
    var password by remember(inputKey) { mutableStateOf("") }
    var format by remember(inputKey) { mutableStateOf("vault-copy") }
    var variant by remember(inputKey) { mutableStateOf("encrypted") }
    var replace by remember(inputKey, count) { mutableStateOf(false) }
    fun dismiss() { password = ""; onDismiss() }
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(text = "Restore full backup") },
        text = { ScrollableDialogBody {
            if (count == null) {
                if (onDecodeSelected != null) FullBackupFormatSelection(format, variant, busy) { selected, encoding ->
                    format = selected; variant = encoding; password = ""
                }
                Text(text = decodeDescription)
                Text(if (format == "vault-copy") "Use the vault recovery password used when this file was copied." else "Use the password chosen when exporting this backup. Plaintext andOTP JSON needs no password.")
                if (!(onDecodeSelected != null && format == "andotp" && variant == "plain")) OutlinedTextField(value = password,
                    onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) password = it },
                    enabled = !busy, singleLine = true, label = { Text(text = "Backup password") },
                    visualTransformation = PasswordVisualTransformation())
            } else {
                Text(text = "Backup contains $count entries.")
                RestoreModeSelection(replace = replace, busy = busy, onReplaceChange = { replace = it })
            }
            if (busy) Text(text = "Decrypting…")
            if (failed) SharedStatusMessage("Could not restore the backup. Check the password, file and vault storage, then try again.")
        } },
        confirmButton = { ForestTextAction(text = if (count == null) "Open backup" else if (replace) "Replace all entries" else "Merge entries",
            enabled = !busy && (count != null || password.isNotEmpty() || onDecodeSelected != null && format == "andotp" && variant == "plain"), onClick = {
                if (count != null) onConfirm(replace)
                else { val text = password; password = ""; if (onDecodeSelected != null) onDecodeSelected(text, format, variant) else onDecode(text) }
            }) },
        dismissButton = { ForestTextAction(text = "Cancel", onClick = { dismiss() }) })
}

/** Stable wire identifiers shared with native codecs. Three source families, no trial decryption. */
@Composable
fun FullBackupFormatSelection(format: String, variant: String, busy: Boolean, onSelect: (String, String) -> Unit) {
    Text("Backup source format")
    listOf("vault-copy" to "1. Vault file copy (current)", "offlineotp" to "2. Previous OfflineOTP portable backup",
        "andotp" to "3. andOTP portable backup").forEach { (id, title) ->
        ForestTextAction(if (format == id) "$title — selected" else title, enabled = !busy, onClick = { onSelect(id, "encrypted") })
    }
    if (format == "andotp") {
        Text("andOTP backup encoding")
        listOf("encrypted" to "Password-encrypted (andOTP 0.6.3 and later)", "plain" to "Unencrypted JSON",
            "pre063" to "Legacy encrypted (before andOTP 0.6.3)").forEach { (id, title) ->
            ForestTextAction(if (variant == id) "$title — selected" else title, enabled = !busy, onClick = { onSelect(format, id) })
        }
        if (variant == "pre063") Text("Legacy recovery only: this old format uses an unsalted SHA-256 password key. Nothing will be written in this format. Never retry this option automatically after a newer-format failure.")
        if (variant == "plain") Text("This file contains unencrypted secrets. Keep it private and remove unneeded plaintext copies after verifying recovery.")
        Text("OpenPGP-encrypted andOTP backups use the separate OpenPGP restore action.")
    }
}
