package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.ui.portable.PortableEmailAccountDraft

/** Thin `:ui` adapter mapping [EmailEntryInput] onto [SharedEmailAccountDialog] in `:ui-foundation`. */
@Composable
fun EmailEntryDialog(
    inputKey: Any,
    error: String?,
    onSubmit: (EmailEntryInput) -> Unit,
    onDismiss: () -> Unit,
    submitNotice: String? = null
) {
    SharedEmailAccountDialog(
        inputKey = inputKey,
        error = error,
        submitNotice = submitNotice,
        maxTextChars = InputPolicy.MAX_TEXT_CHARS,
        maxCredentialChars = EmailMaterial.MAX_CREDENTIAL_CHARS,
        onSubmit = { draft ->
            onSubmit(
                EmailEntryInput(
                    name = draft.name,
                    address = draft.address,
                    protocol = draft.protocol,
                    incomingHost = draft.incomingHost,
                    incomingPort = draft.incomingPort,
                    incomingEncryption = draft.incomingEncryption,
                    incomingAuth = draft.incomingAuth,
                    incomingUser = draft.incomingUser,
                    incomingPassword = draft.incomingPassword,
                    outgoingHost = draft.outgoingHost,
                    outgoingPort = draft.outgoingPort,
                    outgoingEncryption = draft.outgoingEncryption,
                    outgoingAuth = draft.outgoingAuth,
                    outgoingUser = draft.outgoingUser,
                    outgoingPassword = draft.outgoingPassword,
                    tags = draft.tags
                )
            )
        },
        onDismiss = onDismiss
    )
}
