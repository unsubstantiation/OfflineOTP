package org.shadowice.flocke.andotp.Database

import org.shadowice.flocke.andotp.Utilities.EntryUsage
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

/**
 * Turns the values of a manual-entry form into an [Entry].
 *
 * The Android dialog and the desktop dialog collect the same fields in different widgets, and both
 * used to assemble the entry themselves: which constructor a type uses, what a blank period means,
 * whether digits apply to a note, what an unparsable counter does. Two copies of that meant a value
 * one form accepted could be rejected by the other. The assembly lives here instead, so a type the
 * model accepts is accepted everywhere, and the two forms only differ where the platform does
 * (widgets, field-level error messages, a PIN field the desktop offers and the phone does not).
 *
 * Every constructor here validates, so a rejected draft throws [IllegalArgumentException] before any
 * caller can publish or persist it.
 */
object EntryDraft {

    private val WHITESPACE = "\\s+".toRegex()

    /**
     * A secret is Base32 or a raw mOTP/OTP seed: whitespace is never part of it, and a pasted secret
     * is commonly broken across lines. Notes and SSH keys are free text and keep every character.
     */
    @JvmStatic
    fun sanitizeSecret(type: Entry.OTPType?, raw: String): String =
        if (type == Entry.OTPType.NOTE || type == Entry.OTPType.SSH_KEY) raw
        else raw.replace(WHITESPACE, "")

    /** Digits for a form that typed them in; the same wording whichever dialog asked. */
    @JvmStatic
    fun digits(text: String): Int =
        text.trim().toIntOrNull() ?: throw IllegalArgumentException("Digits must be a number")

    /** Period for a form that typed it in. */
    @JvmStatic
    fun period(text: String): Int =
        text.trim().toIntOrNull() ?: throw IllegalArgumentException("Period must be a number")

    /** HOTP counter for a form that typed it in. */
    @JvmStatic
    fun counter(text: String): Long =
        text.trim().toLongOrNull() ?: throw IllegalArgumentException("HOTP counter must be a number")

    /**
     * Builds (and validates) one entry without touching its usage stamp.
     *
     * @param secret the encoded secret; for notes the note text, for SSH keys the private key.
     * @param timingText the period for TOTP/Steam, the counter for HOTP, ignored otherwise.
     * @param newEntry stamp the draft as used now, as a new entry is; an edit passes `false` and
     *   keeps the stamp the entry already carries.
     */
    @JvmStatic
    fun create(
        type: Entry.OTPType,
        issuer: String,
        label: String,
        secret: String?,
        digitsText: String,
        timingText: String,
        algorithm: TokenCalculator.HashAlgorithm,
        pin: String = "",
        publicKey: String = "",
        sshConnection: SshConnection.Details? = null,
        tags: MutableList<String> = ArrayList(),
        newEntry: Boolean = false
    ): Entry {
        // Notes, SSH keys and mOTP entries carry no typed OTP parameter set, so a digits field
        // they cannot show must not be able to reject them.
        if (type == Entry.OTPType.NOTE || type == Entry.OTPType.SSH_KEY || type == Entry.OTPType.MOTP) {
            val draft = when (type) {
                Entry.OTPType.NOTE -> Entry(type, secret ?: "", issuer, label, tags)
                Entry.OTPType.SSH_KEY ->
                    Entry.sshKey(publicKey, secret ?: "", issuer, label, tags, sshConnection)
                // mOTP seeds are raw strings rather than Base32; the simple constructor stores them
                // verbatim and validate() enforces the seed bounds.
                else -> Entry(type, secret ?: "", issuer, label, tags).apply {
                    if (pin.isNotEmpty()) setPin(pin)
                }
            }
            if (newEntry) EntryUsage.markImported(draft)
            return draft
        }

        val digits = digits(digitsText)
        val entry = when (type) {
            Entry.OTPType.PASSWORD, Entry.OTPType.PASSWORD_ARCHIVE ->
                throw IllegalArgumentException("Use password import")
            Entry.OTPType.EMAIL ->
                throw IllegalArgumentException("Email entries must be managed through FairEmail import/export")
            Entry.OTPType.HOTP ->
                Entry(type, secret, counter(timingText), digits, issuer, label, algorithm, tags)
            Entry.OTPType.TOTP, Entry.OTPType.STEAM ->
                Entry(type, secret, period(timingText), digits, issuer, label, algorithm, tags)
            // Handled above; listed so a new kind cannot silently fall through.
            Entry.OTPType.NOTE, Entry.OTPType.SSH_KEY, Entry.OTPType.MOTP ->
                throw IllegalStateException("unreachable")
        }
        // A hand-typed entry leads a "last used" list, exactly as an imported one does.
        if (newEntry) EntryUsage.markImported(entry)
        return entry
    }
}
