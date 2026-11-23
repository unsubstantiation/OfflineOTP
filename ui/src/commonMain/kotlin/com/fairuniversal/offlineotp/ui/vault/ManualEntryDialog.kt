package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import com.fairuniversal.offlineotp.ui.portable.PortableEntryDraft

enum class EntryCreationMode { MANUAL, PASSWORD, EMAIL }

/** Thin `:ui` adapter mapping [ManualEntryInput] onto [SharedManualEntryDialog] in `:ui-foundation`. */
@Composable
fun ManualEntryDialog(
    inputKey: Any,
    error: String?,
    onSubmit: (ManualEntryInput) -> Unit,
    onDismiss: () -> Unit,
    initial: ManualEntryInput = ManualEntryInput(),
    editing: Boolean = false,
    initialCreationMode: EntryCreationMode = EntryCreationMode.MANUAL,
    artwork: VaultArtwork? = null,
    onPasswordSubmit: ((PasswordEntryInput) -> Unit)? = null,
    onEmailSubmit: ((EmailEntryInput) -> Unit)? = null,
    onSwitchToUriImport: (() -> Unit)? = null,
    onModeChanged: (() -> Unit)? = null,
    submitNotice: String? = null,
    onShowQrCode: (() -> Unit)? = null,
    onShareKeystrokes: (() -> Unit)? = null,
    onManageTags: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val resolvedInitialMode = when {
        editing -> EntryCreationMode.MANUAL
        initialCreationMode == EntryCreationMode.PASSWORD && onPasswordSubmit != null -> EntryCreationMode.PASSWORD
        initialCreationMode == EntryCreationMode.EMAIL && onEmailSubmit != null -> EntryCreationMode.EMAIL
        else -> EntryCreationMode.MANUAL
    }
    var mode by remember(inputKey) { mutableStateOf(resolvedInitialMode) }
    var switchEpoch by remember(inputKey) { mutableStateOf(0) }
    fun switchMode(next: EntryCreationMode) {
        mode = next
        switchEpoch++
        onModeChanged?.invoke()
    }
    if (mode == EntryCreationMode.EMAIL && !editing && onEmailSubmit != null) {
        EmailEntryDialog(
            inputKey = inputKey to switchEpoch,
            error = error,
            onSubmit = onEmailSubmit,
            onDismiss = onDismiss,
            submitNotice = submitNotice,
            artwork = artwork,
            onSwitchToManual = { switchMode(EntryCreationMode.MANUAL) },
            onSwitchToPassword = if (onPasswordSubmit != null) {
                { switchMode(EntryCreationMode.PASSWORD) }
            } else null,
            onSwitchToUriImport = onSwitchToUriImport
        )
        return
    }
    if (mode == EntryCreationMode.PASSWORD && !editing && onPasswordSubmit != null) {
        PasswordEntryDialog(
            inputKey = inputKey to switchEpoch,
            initial = PasswordEntryInput(),
            failed = error != null,
            errorText = error,
            editing = false,
            submitNotice = submitNotice,
            artwork = artwork,
            onSwitchToManual = { switchMode(EntryCreationMode.MANUAL) },
            onSwitchToEmail = if (onEmailSubmit != null) {
                { switchMode(EntryCreationMode.EMAIL) }
            } else null,
            onSwitchToUriImport = onSwitchToUriImport,
            onSubmit = onPasswordSubmit,
            onDismiss = onDismiss
        )
        return
    }
    val unchangedLabel = if (editing) "Keep current artwork" else "Automatic from issuer"
    SharedManualEntryDialog(
        inputKey = inputKey to switchEpoch,
        initial = initial.toPortable(),
        editing = editing,
        error = error,
        submitNotice = submitNotice,
        thumbnailNames = ThumbnailCatalog.NAMES,
        artwork = artwork,
        artworkPicker = { selected, onSelect, onCancel ->
            ThumbnailPicker(
                selected = selected.ifEmpty { null },
                artwork = artwork,
                unchangedLabel = unchangedLabel,
                onSelect = { chosen -> onSelect(chosen.orEmpty()) },
                onCancel = onCancel
            )
        },
        onSwitchToPassword = if (!editing && onPasswordSubmit != null) {
            { switchMode(EntryCreationMode.PASSWORD) }
        } else null,
        onSwitchToEmail = if (!editing && onEmailSubmit != null) {
            { switchMode(EntryCreationMode.EMAIL) }
        } else null,
        onSwitchToUriImport = if (!editing) onSwitchToUriImport else null,
        onShowQrCode = onShowQrCode,
        onShareKeystrokes = onShareKeystrokes,
        onManageTags = if (editing) onManageTags else null,
        onViewDetails = if (editing) onViewDetails else null,
        onDelete = onDelete,
        onSubmit = { draft -> onSubmit(draft.toModel()) },
        onDismiss = onDismiss
    )
}

private fun ManualEntryInput.toPortable(): PortableEntryDraft = PortableEntryDraft(
    kind = type.name,
    issuer = issuer,
    label = label,
    hiddenLabel = hiddenLabel,
    secret = if (type == Entry.OTPType.SSH_KEY) "" else secret,
    digits = digits,
    period = if (type == Entry.OTPType.HOTP) "30" else timing,
    counter = if (type == Entry.OTPType.HOTP) timing else "0",
    algorithm = algorithm.name,
    pin = pin,
    publicKey = publicKey,
    privateKey = if (type == Entry.OTPType.SSH_KEY) secret else "",
    sshHost = sshHost,
    sshPort = sshPort,
    sshUsername = sshUser,
    sshPassword = sshPassword,
    tags = tags,
    thumbnail = thumbnailName.orEmpty(),
    clearHiddenLabel = clearHiddenLabel,
    clearPin = clearPin,
    clearSshPassword = clearSshPassword
)

private fun PortableEntryDraft.toModel(): ManualEntryInput {
    val resolvedType = Entry.OTPType.values().firstOrNull { it.name == kind } ?: Entry.OTPType.TOTP
    val resolvedAlgorithm = TokenCalculator.HashAlgorithm.values().firstOrNull { it.name == algorithm }
        ?: TokenCalculator.HashAlgorithm.SHA1
    return ManualEntryInput(
        type = resolvedType,
        issuer = issuer,
        label = label,
        secret = if (resolvedType == Entry.OTPType.SSH_KEY) privateKey else secret,
        digits = digits,
        timing = if (resolvedType == Entry.OTPType.HOTP) counter else period,
        algorithm = resolvedAlgorithm,
        pin = pin,
        publicKey = publicKey,
        hiddenLabel = hiddenLabel,
        tags = tags,
        sshHost = sshHost,
        sshPort = sshPort,
        sshUser = sshUsername,
        sshPassword = sshPassword,
        thumbnailName = thumbnail.ifEmpty { null },
        clearPin = clearPin,
        clearSshPassword = clearSshPassword,
        clearHiddenLabel = clearHiddenLabel
    )
}
