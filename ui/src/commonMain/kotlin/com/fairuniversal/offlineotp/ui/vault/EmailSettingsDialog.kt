package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.EmailIdentityInput
import com.fairuniversal.offlineotp.ui.portable.PortableEmailConnectionDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailIdentityDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailSettingsDraft

/** Thin `:ui` adapter mapping [EmailSettingsInput] onto [SharedEmailSettingsDialog] in `:ui-foundation`. */
@Composable
fun EmailSettingsDialog(
    inputKey: Any,
    initial: EmailSettingsInput,
    failed: Boolean,
    onSubmit: (EmailSettingsInput) -> Unit,
    onDismiss: () -> Unit
) {
    SharedEmailSettingsDialog(
        inputKey = inputKey,
        initial = initial.toPortable(),
        failed = failed,
        onSubmit = { draft -> onSubmit(draft.toModel()) },
        onDismiss = onDismiss
    )
}

private fun EmailConnectionInput.toPortable(): PortableEmailConnectionDraft = PortableEmailConnectionDraft(
    host = host,
    port = port,
    encryption = encryption,
    username = username,
    authentication = authentication
)

private fun PortableEmailConnectionDraft.toModel(): EmailConnectionInput = EmailConnectionInput(
    host = host,
    port = port,
    encryption = encryption,
    username = username,
    authentication = authentication
)

private fun EmailSettingsInput.toPortable(): PortableEmailSettingsDraft = PortableEmailSettingsDraft(
    protocol = protocol,
    incoming = incoming.toPortable(),
    outgoing = outgoing.map { it.toPortable() },
    identities = identities?.map { PortableEmailIdentityDraft(name = it.name, address = it.address) },
    accountName = accountName,
    removedIdentities = removedIdentities,
    removableIdentities = removableIdentities
)

private fun PortableEmailSettingsDraft.toModel(): EmailSettingsInput = EmailSettingsInput(
    protocol = protocol,
    incoming = incoming.toModel(),
    outgoing = outgoing.map { it.toModel() },
    identities = identities?.map { EmailIdentityInput(name = it.name, address = it.address) },
    accountName = accountName,
    removedIdentities = removedIdentities,
    removableIdentities = removableIdentities
)
