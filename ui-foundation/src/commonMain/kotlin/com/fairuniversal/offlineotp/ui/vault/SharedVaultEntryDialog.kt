package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDialogKind
import com.fairuniversal.offlineotp.ui.portable.PortableInputBounds

/**
 * Shared entry-action dialog router across Android, Desktop and iOS.
 *
 * Hosts own session/generation checks and vault mutations; this composable owns only the shared
 * PIN-entry and counter/delete confirmation presentation.
 */
@Composable
fun SharedVaultEntryDialog(
    kind: PortableEntryDialogKind,
    entryId: Long,
    inputKey: Any,
    failed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onPinSubmit: (String) -> Unit,
    pinTitle: String = "Enter PIN",
    pinLabel: String = "PIN",
    pinConfirmLabel: String = "Save",
    pinFailure: String = "The PIN could not be saved.",
    pinMaxLength: Int = PortableInputBounds.MAX_TAG_CHARS,
    counterTitle: String = "Advance counter?",
    counterMessage: String = "Generate the next HOTP code for this account?",
    counterConfirmLabel: String = "Advance",
    counterFailure: String = "The counter could not be saved.",
    deleteTitle: String = "Delete account?",
    deleteMessage: String = "Remove this account from the vault? This cannot be undone.",
    deleteConfirmLabel: String = "Delete",
    deleteFailure: String = "The deletion could not be saved.",
    cancelLabel: String = "Cancel"
) {
    when (kind) {
        PortableEntryDialogKind.PIN -> SharedEntryPinDialog(
            entryId = entryId,
            title = pinTitle,
            inputLabel = pinLabel,
            confirmLabel = pinConfirmLabel,
            dismissLabel = cancelLabel,
            maxLength = pinMaxLength,
            onSubmit = onPinSubmit,
            onDismiss = onDismiss,
            error = if (failed) pinFailure else null,
            inputKey = inputKey
        )
        PortableEntryDialogKind.COUNTER -> EntryConfirmationDialog(
            title = counterTitle,
            message = counterMessage,
            confirmLabel = counterConfirmLabel,
            dismissLabel = cancelLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            error = if (failed) counterFailure else null
        )
        PortableEntryDialogKind.DELETE -> EntryConfirmationDialog(
            title = deleteTitle,
            message = deleteMessage,
            confirmLabel = deleteConfirmLabel,
            dismissLabel = cancelLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            error = if (failed) deleteFailure else null
        )
    }
}
