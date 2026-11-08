package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.Utilities.SshConnection
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog

/** Ephemeral form values, never saveable or logged. Strings cannot be zeroized. */
data class ManualEntryInput(
    val type: Entry.OTPType = Entry.OTPType.TOTP,
    val issuer: String = "", val label: String = "", val secret: String = "",
    val digits: String = "6", val timing: String = "30",
    val algorithm: TokenCalculator.HashAlgorithm = TokenCalculator.HashAlgorithm.SHA1,
    val pin: String = "", val publicKey: String = "", val hiddenLabel: String = "",
    val tags: String = "", val sshHost: String = "", val sshPort: String = "",
    val sshUser: String = "", val sshPassword: String = "",
    /** Null means automatic on creation, preserve current artwork on edit. Default means letter tile. */
    val thumbnailName: String? = null
) {
    fun build(existing: Entry? = null): Entry {
        require(type in EntryDraft.offerableTypes())
        require(thumbnailName == null || thumbnailName in ThumbnailCatalog.NAMES) { "Unknown thumbnail" }
        require(existing == null || existing.getType() == type) { "Entry type cannot change" }
        val priorKeys = existing?.takeIf { it.isSshKey() }?.getSshKeys()
        val entered = EntryDraft.sanitizeSecret(type, secret)
        val resolvedSecret = entered.ifEmpty {
            when {
                existing == null -> ""
                type == Entry.OTPType.NOTE -> existing.getNote()
                type == Entry.OTPType.SSH_KEY -> priorKeys?.privateKey.orEmpty()
                else -> existing.getSecretEncoded()
            }
        }
        val steam = type == Entry.OTPType.STEAM
        return EntryDraft.create(
            type, issuer, label, resolvedSecret,
            if (steam) "5" else digits, if (steam) "30" else timing,
            if (steam) TokenCalculator.HashAlgorithm.SHA1 else algorithm,
            pin = pin.ifEmpty { existing?.getPin().orEmpty() }, publicKey = publicKey.ifEmpty { priorKeys?.publicKey.orEmpty() },
            sshConnection = if (type == Entry.OTPType.SSH_KEY)
                SshConnection.of(sshHost, SshConnection.port(sshPort), sshUser,
                    sshPassword.ifEmpty { existing?.getSshConnection()?.password.orEmpty() }) else null,
            tags = ArrayList(tags.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()),
            hiddenLabel = hiddenLabel.ifEmpty { existing?.getHiddenLabel().orEmpty() }, newEntry = existing == null
        ).also { replacement ->
            if (existing != null) {
                replacement.setDetailsVisible(existing.areDetailsVisible())
                replacement.setLastUsed(existing.getLastUsed())
                replacement.setUsedFrequency(existing.getUsedFrequency())
                replacement.setThumbnailName(existing.getThumbnailName())
            }
            thumbnailName?.let { replacement.setThumbnailName(it) }
        }
    }

    companion object {
        /** Seeds editable metadata only. Never preload OTP seeds, notes, keys, PINs or passwords. */
        fun fromEntry(entry: Entry): ManualEntryInput {
            require(entry.getType() in EntryDraft.offerableTypes())
            val connection = entry.takeIf { it.isSshKey() }?.getSshConnection()
            return ManualEntryInput(type = entry.getType(), issuer = entry.getIssuer(), label = entry.getLabel(),
                digits = entry.getDigits().toString(),
                timing = if (entry.isCounterBased()) entry.getCounter().toString() else entry.getPeriod().toString(),
                algorithm = entry.getAlgorithm(), tags = entry.getTags().joinToString("\n"),
                thumbnailName = entry.getThumbnailName(),
                sshHost = connection?.host.orEmpty(), sshUser = connection?.username.orEmpty(),
                sshPort = connection?.port?.takeIf { it != 0 }?.toString().orEmpty())
        }
    }

    fun withoutSecrets(): ManualEntryInput = copy(secret = "", pin = "", publicKey = "", sshPassword = "", hiddenLabel = "")
    override fun toString(): String = "ManualEntryInput(redacted)"
}
