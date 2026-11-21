package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.ui.portable.PortablePasswordEditDraft

/** Thin `:ui` adapter mapping [PasswordEntryInput] onto [SharedPasswordEntryDialog] in `:ui-foundation`. */
@Composable
fun PasswordEntryDialog(
    inputKey: Any,
    initial: PasswordEntryInput,
    failed: Boolean,
    onSubmit: (PasswordEntryInput) -> Unit,
    onDismiss: () -> Unit
) {
    SharedPasswordEntryDialog(
        inputKey = inputKey,
        initial = PortablePasswordEditDraft(url = initial.url, username = initial.username, password = initial.password),
        failed = failed,
        maxFieldChars = BrowserPasswordCsv.MAX_FIELD_CHARS,
        onSubmit = { draft ->
            onSubmit(PasswordEntryInput(url = draft.url, username = draft.username, password = draft.password))
        },
        onDismiss = onDismiss
    )
}
