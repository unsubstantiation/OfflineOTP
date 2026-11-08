package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable

/** Shared dialog routing and wording; the host only executes and validates commands. */
@Composable
fun VaultEntryDialog(
    request: VaultDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onPinSubmit: (String) -> Unit,
    strings: VaultDialogStrings = VaultDialogStrings()
) {
    when (request.kind) {
        VaultDialogKind.PIN -> EntryPinDialog(
            entryId = request.entryId,
            inputKey = request,
            title = strings.pinTitle,
            inputLabel = strings.pinLabel,
            confirmLabel = strings.save,
            dismissLabel = strings.cancel,
            onSubmit = onPinSubmit,
            onDismiss = onDismiss,
            error = if (request.failed) strings.pinFailure else null
        )
        VaultDialogKind.COUNTER -> EntryConfirmationDialog(
            title = strings.counterTitle,
            message = strings.counterMessage,
            confirmLabel = strings.counterConfirm,
            dismissLabel = strings.cancel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            error = if (request.failed) strings.counterFailure else null
        )
        VaultDialogKind.DELETE -> EntryConfirmationDialog(
            title = strings.deleteTitle,
            message = strings.deleteMessage,
            confirmLabel = strings.deleteConfirm,
            dismissLabel = strings.cancel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            error = if (request.failed) strings.deleteFailure else null
        )
    }
}
