package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.View.EntryListPresentation

/**
 * Builds one [VaultCardState] from one borrowed entry.
 *
 * [VaultProjection] uses this to turn a whole frame at once; a host that paints one row at a time —
 * the Swing table asks for a card as it draws each cell — asks for one card instead of re-deriving
 * the same rules from the entry. That is the part the two clients used to duplicate: which code a
 * row shows, how it is grouped, whether the step is running out, which tags belong to the row and
 * which artwork identifies it.
 *
 * The clock is an argument, so the same row shows the same second whether it was reached through a
 * frame or through a paint, and a test can pin it.
 *
 * This reads the token the entry has already cached and never computes one: the entry is borrowed,
 * not owned. A host with a tick refreshes tokens there (as the desktop vault state and the Android
 * adapter do) and [VaultProjection] refreshes for the frame it is building; painting must not, or a
 * repaint could block the UI thread on a slow token calculation.
 */
object VaultCards {

    /** Whether [entry] carries a one-time code at all, as opposed to content shown on demand. */
    fun hasCode(entry: Entry): Boolean = entry.isTimeBased() || entry.isCounterBased()

    /** The shared rule for which tab an entry belongs to, and therefore how its card is drawn. */
    fun kindOf(entry: Entry): VaultEntryKind = when (EntryListPresentation.categoryOf(entry)) {
        EntryListPresentation.Category.OTP -> VaultEntryKind.OTP
        EntryListPresentation.Category.NOTES -> VaultEntryKind.NOTES
        EntryListPresentation.Category.SSH_KEYS -> VaultEntryKind.SSH_KEYS
        EntryListPresentation.Category.PASSWORDS -> VaultEntryKind.PASSWORDS
        EntryListPresentation.Category.EMAILS -> VaultEntryKind.EMAILS
    }

    /**
     * One row at [nowMillis]. [revealed] is the host's reveal state for this entry: only a revealed
     * row carries [VaultCardState.token] in a form a card may print unmasked, and only a revealed
     * row carries [VaultCardState.revealedContent].
     */
    fun of(
        entry: Entry,
        nowMillis: Long,
        revealed: Boolean = false,
        appearance: VaultAppearance = VaultAppearance()
    ): VaultCardState {
        val kind = kindOf(entry)
        val hasCode = hasCode(entry)
        val pinRequired = entry.getPin().isEmpty() && entry.getType() == Entry.OTPType.MOTP
        val rawToken = if (hasCode && !pinRequired) entry.getCurrentOTP().orEmpty() else ""
        val token = if (rawToken.isEmpty()) null else TokenDisplay.group(rawToken, appearance.groupTokens, appearance.tokenGroupSize)
        // Only a time-based code has a step to count down; an HOTP entry keeps a period field but
        // never expires.
        val stepSeconds = if (hasCode && !pinRequired && entry.isTimeBased()) entry.getTokenStepSeconds() else 0
        val remaining = if (stepSeconds > 0) TokenDisplay.remainingSeconds(stepSeconds, nowMillis) else null
        return VaultCardState(
            id = entry.getListId(),
            kind = kind,
            issuer = if (appearance.showIssuer) entry.getIssuer() else "",
            label = entry.getLabel(),
            thumbnailName = entry.getThumbnailName(),
            tags = entry.getTags().toList(),
            token = token,
            previousToken = if (appearance.showPreviousToken && token != null && entry.isTimeBased() && entry.getType() != Entry.OTPType.MOTP) {
                entry.getPrevOTP()?.takeIf { it.isNotEmpty() }
                    ?.let { TokenDisplay.group(it, appearance.groupTokens, appearance.tokenGroupSize) }
            } else {
                null
            },
            counter = if (entry.isCounterBased()) entry.getCounter() else null,
            showCountdown = (appearance.showIndividualCountdowns || entry.hasNonDefaultTokenStep()) &&
                (!appearance.hideCountdownWhenMasked || revealed),
            remainingSeconds = remaining,
            progress = if (remaining != null) TokenDisplay.progress(stepSeconds, nowMillis) else null,
            expiring = remaining != null && remaining <= appearance.expiringSeconds,
            revealed = revealed,
            revealedContent = if (revealed) revealedContent(entry, kind) else null,
            pinRequired = pinRequired,
            copyable = token != null && entry.hasCopyableToken()
        )
    }

    /**
     * The one non-code line a revealed card may print. Only public-facing metadata is used: a note
     * body, the account's mail address, the public half of an SSH key, or a password entry's site.
     * A password entry's stored columns are named, never valued, so an unrecognized export cannot
     * leak a password into the list.
     */
    fun revealedContent(entry: Entry, kind: VaultEntryKind): String? = when (kind) {
        VaultEntryKind.NOTES -> entry.getNote()
        VaultEntryKind.EMAILS -> entry.getEmailAccount().let { account ->
            account.address.ifBlank { account.name }
        }
        VaultEntryKind.SSH_KEYS -> entry.getSshKeys().publicKey.lineSequence().firstOrNull().orEmpty()
        VaultEntryKind.PASSWORDS -> entry.getPasswordFields().let { fields ->
            val site = listOf("url", "host", "site").firstNotNullOfOrNull { name ->
                fields.value(name).takeIf { it.isNotBlank() }
            }
            site ?: fields.columns().joinToString(", ")
        }
        VaultEntryKind.OTP -> null
    }
}
