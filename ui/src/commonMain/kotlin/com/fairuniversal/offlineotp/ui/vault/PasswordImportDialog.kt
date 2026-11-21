package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordImportMode

/** Thin `:ui` adapter mapping [PasswordImport.Mode] onto [SharedPasswordImportDialog] in `:ui-foundation`. */
@Composable
fun PasswordImportDialog(
    inputKey: Any,
    busy: Boolean,
    count: Int?,
    failed: Boolean,
    onRead: (PasswordImport.Mode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SharedPasswordImportDialog(
        inputKey = inputKey,
        busy = busy,
        count = count,
        failed = failed,
        onRead = { mode ->
            onRead(
                when (mode) {
                    PortablePasswordImportMode.INDIVIDUAL -> PasswordImport.Mode.INDIVIDUAL
                    PortablePasswordImportMode.ARCHIVE -> PasswordImport.Mode.ARCHIVE
                }
            )
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
