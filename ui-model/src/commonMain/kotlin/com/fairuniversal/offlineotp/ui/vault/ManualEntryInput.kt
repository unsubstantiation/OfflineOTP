package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.Utilities.SshConnection
import com.fairuniversal.offlineotp.Utilities.TokenCalculator

/** Ephemeral form values, never saveable or logged. Strings cannot be zeroized. */
data class ManualEntryInput(
    val type: Entry.OTPType = Entry.OTPType.TOTP,
    val issuer: String = "", val label: String = "", val secret: String = "",
    val digits: String = "6", val timing: String = "30",
    val algorithm: TokenCalculator.HashAlgorithm = TokenCalculator.HashAlgorithm.SHA1,
    val pin: String = "", val publicKey: String = "", val hiddenLabel: String = "",
    val tags: String = "", val sshHost: String = "", val sshPort: String = "",
    val sshUser: String = "", val sshPassword: String = ""
) {
    fun build(): Entry {
        require(type in EntryDraft.offerableTypes())
        val steam = type == Entry.OTPType.STEAM
        return EntryDraft.create(
            type, issuer, label, EntryDraft.sanitizeSecret(type, secret),
            if (steam) "5" else digits, if (steam) "30" else timing,
            if (steam) TokenCalculator.HashAlgorithm.SHA1 else algorithm,
            pin = pin, publicKey = publicKey,
            sshConnection = if (type == Entry.OTPType.SSH_KEY)
                SshConnection.of(sshHost, SshConnection.port(sshPort), sshUser, sshPassword) else null,
            tags = ArrayList(tags.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()),
            hiddenLabel = hiddenLabel, newEntry = true
        )
    }

    fun withoutSecrets(): ManualEntryInput = copy(secret = "", pin = "", publicKey = "", sshPassword = "", hiddenLabel = "")
    override fun toString(): String = "ManualEntryInput(redacted)"
}
