package com.fairuniversal.offlineotp.Utilities

/** Readiness policy for the external OpenPGP service; restore does not require recipients. */
data class OpenPgpAvailability(val canRestore: Boolean, val canEncrypt: Boolean) {
    companion object {
        @JvmStatic
        fun evaluate(providerConfigured: Boolean, serviceReady: Boolean, recipientCount: Int): OpenPgpAvailability {
            val ready = providerConfigured && serviceReady
            return OpenPgpAvailability(canRestore = ready, canEncrypt = ready && recipientCount > 0)
        }
    }
}

/** Reject empty/whitespace-only provider output before a backup document is created or reported. */
object OpenPgpOutputPolicy {
    @JvmStatic
    fun hasContent(output: String?): Boolean = !output.isNullOrBlank()
}

/**
 * Which engine produces an OpenPGP backup: an external provider app (OpenKeychain) reached over
 * intents, or the bundled rpgp backend working on key files the user selected.
 *
 * [PROVIDER] stays the default so an existing installation keeps its configured provider and can
 * still read the files it wrote. Both modes emit and accept standard OpenPGP messages, so a backup
 * written in one mode restores in the other.
 */
enum class OpenPgpMode {
    PROVIDER,
    KEY_FILE;

    companion object {
        const val PROVIDER_VALUE = "provider"
        const val KEY_FILE_VALUE = "key_file"

        /** Unknown or absent values fall back to the provider mode, never to a silent switch. */
        @JvmStatic
        fun parse(value: String?): OpenPgpMode = if (value == KEY_FILE_VALUE) KEY_FILE else PROVIDER

        @JvmStatic
        fun preferenceValue(mode: OpenPgpMode): String =
            if (mode == KEY_FILE) KEY_FILE_VALUE else PROVIDER_VALUE
    }
}

/**
 * Readiness and input rules for the in-app key-file mode.
 *
 * Recipients are public certificates, so persisting the user's document grants is appropriate.
 * The secret key is also referenced by a user-chosen document, but its passphrase is never stored:
 * it is asked for at restore time and lives only for that one operation.
 */
object OpenPgpKeyFilePolicy {

    /** Matches the recipient ceiling enforced by the native backend. */
    const val MAX_RECIPIENTS = 64

    /** Only bounded `content:` document URIs are accepted; raw paths are never read. */
    @JvmStatic
    fun isUsableKeyUri(value: String?): Boolean =
        value != null && value.startsWith("content://") &&
            value.length in 11..InputPolicy.MAX_URI_CHARS

    /**
     * Normalizes the stored recipient grants: drops anything unusable, de-duplicates, and orders
     * them so a backup's recipient set does not depend on preference-set iteration order.
     */
    @JvmStatic
    fun recipients(stored: Set<String>): List<String> =
        stored.filter { isUsableKeyUri(it) }.distinct().sorted().take(MAX_RECIPIENTS)

    /**
     * Encrypting needs the backend and at least one recipient; restoring needs the backend and a
     * secret key. Neither button is enabled while the native library is missing, so the UI reports
     * that plainly instead of failing after the user has picked a destination.
     */
    @JvmStatic
    fun availability(backendAvailable: Boolean, recipientCount: Int, secretKeyConfigured: Boolean): OpenPgpAvailability =
        OpenPgpAvailability(
            canRestore = backendAvailable && secretKeyConfigured,
            canEncrypt = backendAvailable && recipientCount in 1..MAX_RECIPIENTS
        )
}
