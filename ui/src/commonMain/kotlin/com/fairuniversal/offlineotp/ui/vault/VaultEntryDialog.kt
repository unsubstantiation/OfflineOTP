package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDialogKind

/** Thin `:ui` adapter delegating entry-action dialog routing to [SharedVaultEntryDialog] in `:ui-foundation`. */
@Composable
fun VaultEntryDialog(
    request: VaultDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onPinSubmit: (String) -> Unit,
    strings: VaultDialogStrings = VaultDialogStrings()
) {
    val portableKind = when (request.kind) {
        VaultDialogKind.PIN -> PortableEntryDialogKind.PIN
        VaultDialogKind.COUNTER -> PortableEntryDialogKind.COUNTER
        VaultDialogKind.DELETE -> PortableEntryDialogKind.DELETE
    }
    SharedVaultEntryDialog(
        kind = portableKind,
        entryId = request.entryId,
        inputKey = request,
        failed = request.failed,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        onPinSubmit = onPinSubmit,
        pinTitle = strings.pinTitle,
        pinLabel = strings.pinLabel,
        pinConfirmLabel = strings.save,
        pinFailure = strings.pinFailure,
        pinMaxLength = InputPolicy.MAX_TAG_CHARS,
        counterTitle = strings.counterTitle,
        counterMessage = strings.counterMessage,
        counterConfirmLabel = strings.counterConfirm,
        counterFailure = strings.counterFailure,
        deleteTitle = strings.deleteTitle,
        deleteMessage = strings.deleteMessage,
        deleteConfirmLabel = strings.deleteConfirm,
        deleteFailure = strings.deleteFailure,
        cancelLabel = strings.cancel
    )
}
