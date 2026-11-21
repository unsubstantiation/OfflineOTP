package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.ui.portable.PortableDetailRow

/** Thin `:ui` adapter mapping [EmailMaterial.Detail] rows onto [SharedEmailDetailsDialog] in `:ui-foundation`. */
@Composable
fun EmailDetailsDialog(
    rows: List<EmailMaterial.Detail>?,
    secretsVisible: Boolean,
    onToggleSecrets: () -> Unit,
    onDismiss: () -> Unit,
    onEditPasswords: (() -> Unit)? = null,
    onEditSettings: (() -> Unit)? = null
) {
    SharedEmailDetailsDialog(
        rows = rows?.map { PortableDetailRow(label = it.label, value = it.value) },
        secretsVisible = secretsVisible,
        onToggleSecrets = onToggleSecrets,
        onDismiss = onDismiss,
        onEditPasswords = onEditPasswords,
        onEditSettings = onEditSettings
    )
}
