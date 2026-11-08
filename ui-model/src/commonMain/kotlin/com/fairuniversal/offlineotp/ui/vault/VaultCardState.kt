package com.fairuniversal.offlineotp.ui.vault

/** The five vault tabs, mirroring `EntryListPresentation.Category` at the UI boundary. */
enum class VaultEntryKind { OTP, NOTES, SSH_KEYS, PASSWORDS, EMAILS }

/**
 * How one vault row is drawn, independent of the toolkit that draws it.
 *
 * A card is built from a borrowed [com.fairuniversal.offlineotp.Database.Entry] by
 * [VaultProjection] and then forgotten; it holds strings for the current frame only. It is
 * deliberately not saveable and never enters a Bundle/saved state: [token], [previousToken] and
 * [revealedContent] are live secrets, and [toString] redacts them so a stray log line or a failed
 * assertion message cannot print a code, a note body or a stored password.
 */
data class VaultCardState(
    /** Stable list id owned by the vault entry, not a row position. */
    val id: Long,
    val kind: VaultEntryKind,
    val issuer: String,
    val label: String,
    val thumbnailName: String,
    val tags: List<String> = emptyList(),
    /** Grouped for display; null when the entry has no code or the PIN is still missing. */
    val token: String? = null,
    /** Previous step's code, dimmed under the current one when the layout shows it. */
    val previousToken: String? = null,
    /** HOTP counter, null for every other kind. */
    val counter: Long? = null,
    /** Seconds left in the step, null when the code does not expire (HOTP, notes, ...). */
    val remainingSeconds: Int? = null,
    /** Remaining fraction of the step in `(0, 1]`, null when there is no step. */
    val progress: Float? = null,
    /** True while the code is about to roll over, so the card can warn without re-deriving time. */
    val expiring: Boolean = false,
    /** Whether this row's protected content is currently revealed. */
    val revealed: Boolean = false,
    /** Note body, mail address or password URL; only present while [revealed]. */
    val revealedContent: String? = null,
    /** mOTP entry whose PIN has not been entered yet. */
    val pinRequired: Boolean = false,
    /** Whether the host may copy [token] (HOTP needs an explicit calculation first). */
    val copyable: Boolean = false,
    val detailsVisible: Boolean = true,
    /** Protected auxiliary label; absent on concealed rows. */
    val detailLabel: String? = null,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false
) {
    /** Brand first, falling back to the account name when the entry has no issuer. */
    val title: String get() = issuer.ifBlank { label }

    /** Account name, or empty when it would repeat [title] on the same card. */
    val subtitle: String get() = if (label == title) "" else label

    /** Whether the row shows a one-time code at all. */
    val hasToken: Boolean get() = token != null

    /** True when the row carries a step countdown. */
    val hasCountdown: Boolean get() = remainingSeconds != null && progress != null

    /** Codes run out at the end of their step; HOTP and static secrets never warn. */
    val showsExpiry: Boolean get() = expiring && hasToken

    /** Whether [token] may be drawn as digits; otherwise the card shows [TokenDisplay.mask]. */
    val tokenVisible: Boolean get() = token != null && revealed

    // A card carries live secrets: keep them out of logs, assertion messages and crash reports.
    override fun toString(): String =
        "VaultCardState(id=$id, kind=$kind, redacted)"
}
