package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.InputPolicy

/** Delegates to [SharedEntryPinDialog] in `:ui-foundation` with `:core`'s [InputPolicy] bound. */
@Composable
fun EntryPinDialog(
    entryId: Long,
    title: String,
    inputLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    inputKey: Any = entryId
) {
    SharedEntryPinDialog(
        entryId = entryId,
        title = title,
        inputLabel = inputLabel,
        confirmLabel = confirmLabel,
        dismissLabel = dismissLabel,
        maxLength = InputPolicy.MAX_TAG_CHARS,
        onSubmit = onSubmit,
        onDismiss = onDismiss,
        error = error,
        inputKey = inputKey
    )
}
