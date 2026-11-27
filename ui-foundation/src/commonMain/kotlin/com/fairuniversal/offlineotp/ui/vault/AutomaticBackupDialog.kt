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
import com.fairuniversal.offlineotp.ui.portable.PortableAutomaticBackupPolicy

/** Session-scoped credentials must never enter saveable state or portable preferences. */
@Composable
fun AutomaticBackupDialog(inputKey: Any, initialFolder: String, enabled: Boolean, busy: Boolean, status: String,
    onEnable: (String, String, String, Int, Int) -> Boolean, onDisable: () -> Unit, onRunNow: () -> Unit, onDismiss: () -> Unit,
    remembered: Boolean = false,
    initialMinutes: Int = PortableAutomaticBackupPolicy.DEFAULT_MINUTES,
    initialRetention: Int = PortableAutomaticBackupPolicy.DEFAULT_RETENTION,
    openPgpEnabled: Boolean = false,
    signingEnabled: Boolean = false,
    recipientNames: List<String> = emptyList(),
    onChooseRecipient: (() -> Unit)? = null,
    onClearRecipients: () -> Unit = {},
    onEnableRecipients: ((String, Int, Int) -> Boolean)? = null,
    initialSigningRequired: Boolean = false,
    signingKeyName: String = "",
    onChooseSigningKey: (() -> Unit)? = null,
    onEnableSignedRecipients: ((String, Int, Int, String) -> Boolean)? = null,
    vaultCopyEnabled: Boolean = false,
    initialVaultCopy: Boolean = true,
    onEnableSnapshots: ((String, Int, Int) -> Boolean)? = null) {
    var folder by remember(inputKey, initialFolder) { mutableStateOf(initialFolder) }
    var password by remember(inputKey) { mutableStateOf("") }
    var confirmation by remember(inputKey) { mutableStateOf("") }
    var minutes by remember(inputKey, initialMinutes) { mutableStateOf(initialMinutes.toString()) }
    var keep by remember(inputKey, initialRetention) { mutableStateOf(initialRetention.toString()) }
    var useSnapshots by remember(inputKey, initialVaultCopy) { mutableStateOf(onEnableSnapshots != null && initialVaultCopy) }
    var useOpenPgp by remember(inputKey, openPgpEnabled) { mutableStateOf(openPgpEnabled) }
    var requireSigning by remember(inputKey, initialSigningRequired) { mutableStateOf(initialSigningRequired) }
    var signingPassphrase by remember(inputKey) { mutableStateOf("") }
    var invalid by remember(inputKey) { mutableStateOf(false) }
    fun clear() { password = ""; confirmation = ""; signingPassphrase = "" }
    AlertDialog(onDismissRequest = { clear(); onDismiss() }, title = { Text("Automatic backups and exports") },
        text = { ScrollableDialogBody {
            Text("Runs after successful vault saves (coalesced), at the selected interval, and on request while this vault is unlocked. The configuration, including the password or selected public certificates, is remembered encrypted under this vault's key, outside portable preferences, and resumes after unlock. Vault key/password changes may require reconfiguration. No locked-vault or OS background scheduling.")
            Text("Writes to an OfflineOTP-automatic subfolder. Retention removes only matching automatic-backup files, after a successful new write. Keep a separate verified backup. Disabling does not delete backups.")
            SharedStatusMessage(status)
            if (!enabled) {
                OutlinedTextField(enabled = !busy, value = folder, onValueChange = { if (it.length <= 4096) folder = it }, label = { Text("Existing private destination folder") })
                if (onEnableSnapshots != null) {
                    ForestTextAction(if (useSnapshots) "Use a separately encrypted export instead" else "Use full vault file copies instead", enabled = !busy,
                        onClick = { clear(); invalid = false; useSnapshots = !useSnapshots })
                    if (useSnapshots) Text("Copies the saved self-contained encrypted vault unchanged. No extra backup password, recipients, serialization or encryption layer. Recover copies with the vault recovery password used when each copy was made. Migrate legacy vaults in Backups first. Includes entries, not preferences or device unlock credentials.")
                }
                if (!useSnapshots) {
                    if (onEnableRecipients != null) ForestTextAction(
                        if (useOpenPgp) "Use a backup password instead" else "Use OpenPGP public certificates instead", enabled = !busy,
                        onClick = { clear(); invalid = false; useOpenPgp = !useOpenPgp })
                    if (useOpenPgp && onEnableRecipients != null) {
                        Text("Encrypts the full vault to every selected public certificate, with signing only when explicitly enabled below. ANY matching secret key can decrypt it. Verify recipients independently; filenames are not fingerprints. Certificates are snapshotted into the vault-encrypted configuration, not re-read from paths. Automatic mode accepts up to 32 KiB combined certificate bytes. Test a restore before relying on it.")
                        if (onEnableSignedRecipients != null) {
                            ForestTextAction(if (requireSigning) "Signing required for this session — turn off" else "Require a signature for these backups",
                                enabled = !busy, onClick = { requireSigning = !requireSigning; signingPassphrase = "" })
                            if (requireSigning) {
                                Text("Signing key and passphrase stay in memory only, until lock/close. After unlock, signed backups pause until you reload them; they never silently become unsigned. The current in-app restore does not verify signer identity. Use a trusted OpenPGP tool and an independently verified signing certificate to verify the signature.")
                                Text(signingKeyName.ifEmpty { "No signing private key selected" })
                                ForestTextAction("Load session signing private key", enabled = !busy, onClick = { onChooseSigningKey?.invoke() })
                                OutlinedTextField(enabled = !busy, value = signingPassphrase, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) signingPassphrase = it },
                                    label = { Text("Session signing-key passphrase (may be empty)") }, visualTransformation = PasswordVisualTransformation())
                            }
                        }
                        recipientNames.forEach { Text(it) }
                        ForestTextAction("Add recipient public certificate", enabled = !busy, onClick = { onChooseRecipient?.invoke() })
                        ForestTextAction("Clear recipients", enabled = !busy, onClick = onClearRecipients)
                    } else {
                    OutlinedTextField(enabled = !busy, value = password, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) password = it }, label = { Text("Separate backup password") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(enabled = !busy, value = confirmation, onValueChange = { if (it.length <= PortableInputBounds.MAX_TEXT_CHARS) confirmation = it }, label = { Text("Confirm backup password") }, visualTransformation = PasswordVisualTransformation())
                    }
                }
                OutlinedTextField(enabled = !busy, value = minutes, onValueChange = { if (it.length <= 4) minutes = it }, label = { Text("Interval minutes (1–1440)") })
                OutlinedTextField(enabled = !busy, value = keep, onValueChange = { if (it.length <= 3) keep = it }, label = { Text("Backups to retain (1–100)") })
            }
            if (invalid) SharedStatusMessage("Check the destination, matching nonempty passwords or selected public certificates (exports only), portable-vault migration, interval and retention limits.")
            if (enabled) {
                Text("Encryption: ${if (vaultCopyEnabled) "vault recovery password (exact file copy)" else if (openPgpEnabled) if (signingEnabled) "OpenPGP (signed)" else "OpenPGP (unsigned)" else "backup password"}")
                Text("Destination: $initialFolder")
                Text("Periodic interval: $initialMinutes minutes. Retain up to $initialRetention automatic backups.")
                ForestTextAction("Back up now", enabled = !busy, onClick = onRunNow)

            }
            if (enabled || remembered) ForestTextAction("Disable and forget saved configuration", onClick = { clear(); onDisable() })
        } }, confirmButton = {
            if (!enabled) ForestTextAction("Enable and remember encrypted", enabled = !busy, onClick = {
                val interval = minutes.toIntOrNull(); val retention = keep.toIntOrNull()
                if (useSnapshots && onEnableSnapshots != null) {
                    invalid = interval == null || retention == null || interval !in 1..1440 || retention !in 1..100
                    if (!invalid) invalid = !onEnableSnapshots(folder, interval!!, retention!!)
                    clear()
                } else if (useOpenPgp && onEnableRecipients != null) {
                    invalid = interval == null || retention == null || interval !in 1..1440 || retention !in 1..100 || recipientNames.isEmpty()
                    if (!invalid) {
                        invalid = if (requireSigning && onEnableSignedRecipients != null) signingKeyName.isEmpty() ||
                            !onEnableSignedRecipients(folder, interval!!, retention!!, signingPassphrase)
                            else !onEnableRecipients(folder, interval!!, retention!!)
                    }
                    clear()
                } else {
                    invalid = interval == null || retention == null || !PortableAutomaticBackupPolicy.valid(
                        interval, retention, password.length, password == confirmation)
                    if (!invalid) { invalid = !onEnable(folder, password, confirmation, interval!!, retention!!); clear() }
                }
            })
        }, dismissButton = { ForestTextAction("Close", onClick = { clear(); onDismiss() }) })
}
