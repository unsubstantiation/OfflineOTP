package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fairuniversal.offlineotp.ui.portable.PortableEmailPasswordDraft

/** Thin `:ui` adapter mapping [EmailPasswordInput] onto [SharedEmailPasswordDialog] in `:ui-foundation`. */
@Composable
fun EmailPasswordDialog(
    inputKey: Any,
    initial: EmailPasswordInput,
    failed: Boolean,
    onSubmit: (EmailPasswordInput) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(inputKey) { mutableStateOf(initial.withoutSecrets()) }
    SharedEmailPasswordDialog(
        inputKey = inputKey,
        initial = PortableEmailPasswordDraft(incoming = input.incoming, outgoing = input.outgoing),
        failed = failed,
        visualTransformation = PasswordVisualTransformation(),
        onSubmit = { draft ->
            val submitted = EmailPasswordInput(incoming = draft.incoming, outgoing = draft.outgoing)
            input = input.withoutSecrets(); onSubmit(submitted)
        },
        onDismiss = onDismiss
    )
}
