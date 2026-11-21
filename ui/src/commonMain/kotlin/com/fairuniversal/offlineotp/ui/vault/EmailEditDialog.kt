package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.EmailFieldInput
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.ui.portable.PortableEmailEditDraft
import com.fairuniversal.offlineotp.ui.portable.PortableEmailField

/** Thin `:ui` adapter mapping [EmailEditInput] onto [SharedEmailEditDialog] in `:ui-foundation`. */
@Composable
fun EmailEditDialog(
    inputKey: Any,
    initial: EmailEditInput,
    error: String?,
    onSubmit: (EmailEditInput) -> Unit,
    onDismiss: () -> Unit
) {
    SharedEmailEditDialog(
        inputKey = inputKey,
        initial = initial.toPortable(),
        thumbnailNames = ThumbnailCatalog.NAMES,
        error = error,
        onSubmit = { draft -> onSubmit(draft.toModel()) },
        onDismiss = onDismiss
    )
}

private fun EmailFieldInput.toPortable(): PortableEmailField = PortableEmailField(
    name = name,
    value = value,
    kind = kind.name
)

private fun PortableEmailField.toModel(): EmailFieldInput = EmailFieldInput(
    name = name,
    value = value,
    kind = EmailFieldInput.Kind.values().firstOrNull { it.name == kind } ?: EmailFieldInput.Kind.TEXT
)

private fun EmailEditInput.toPortable(): PortableEmailEditDraft = PortableEmailEditDraft(
    issuer = issuer,
    label = label,
    hiddenLabel = hiddenLabel,
    tags = tags,
    thumbnail = thumbnail,
    account = account.map { it.toPortable() },
    identities = identities.map { list -> list.map { it.toPortable() } }
)

private fun PortableEmailEditDraft.toModel(): EmailEditInput = EmailEditInput(
    issuer = issuer,
    label = label,
    hiddenLabel = hiddenLabel,
    tags = tags,
    thumbnail = thumbnail,
    account = account.map { it.toModel() },
    identities = identities.map { list -> list.map { it.toModel() } }
)
