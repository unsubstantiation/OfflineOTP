package org.shadowice.flocke.andotp.Utilities

import java.util.Locale

/**
 * Optional SSH connection metadata stored beside an SSH key entry's key material.
 *
 * An SSH key entry used to carry only the keys themselves, so the host, port, login name and
 * password a user actually needs when connecting had to live somewhere else. These four fields are
 * stored inside the encrypted vault and its encrypted backups and never appear in an `otpauth://`
 * URI or QR export.
 *
 * Every field is optional and independent: an entry without connection data serializes exactly like
 * the documents written before these fields existed, so existing vaults stay byte-identical and
 * older builds simply ignore the extra keys.
 */
object SshConnection {
    /** A stored port is 1..65535; 0 means "not stored", and the UIs suggest [DEFAULT_PORT]. */
    const val MIN_PORT = 1
    const val MAX_PORT = 65535
    const val DEFAULT_PORT = 22

    const val MAX_HOST_CHARS = 255
    const val MAX_USERNAME_CHARS = 128
    const val MAX_PASSWORD_CHARS = 256

    private val INTEGER_TEXT = Regex("[0-9]+")

    /**
     * Validated connection metadata. Not a data class: the generated `toString` would print the
     * password, so [toString] is redacted instead. Build values through [of].
     */
    class Details internal constructor(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    ) {
        /** True when at least one field carries a value. */
        fun isSet(): Boolean =
            host.isNotEmpty() || port != 0 || username.isNotEmpty() || password.isNotEmpty()

        /**
         * `user@host:port` for a card face, or null when there is no host to show. A user name or
         * port without a host is kept in the vault but is not a usable connection summary.
         */
        fun summary(): String? {
            if (host.isEmpty()) return null
            val target = if (port == 0 || port == DEFAULT_PORT) host else "$host:$port"
            return if (username.isEmpty()) target else "$username@$target"
        }

        /**
         * Identity of the connection target. The password is deliberately excluded: it is a secret
         * that changes independently of which server/user a key belongs to.
         */
        fun sameIdentity(other: Details?): Boolean =
            other != null && host == other.host && port == other.port && username == other.username

        /** Stable, non-secret identity used by `Entry.equals`/`hashCode`. */
        internal fun identityKey(): List<String> = listOf(host, port.toString(), username)

        override fun toString(): String = "SshConnection.Details(redacted)"
    }

    /**
     * Validates the four fields and returns the metadata, or null when every field is empty (so a
     * blank form stores nothing at all). Throws [IllegalArgumentException] with a user-facing
     * message when a value is out of bounds.
     */
    @JvmStatic
    fun of(host: String?, port: Int, username: String?, password: String?): Details? {
        val normalizedHost = normalize(host, MAX_HOST_CHARS, "Host name")
        val normalizedUsername = normalize(username, MAX_USERNAME_CHARS, "User name")
        val normalizedPassword = normalizePassword(password)
        require(port == 0 || port in MIN_PORT..MAX_PORT) {
            "Port must be between $MIN_PORT and $MAX_PORT"
        }
        if (normalizedHost.isEmpty() && port == 0 &&
            normalizedUsername.isEmpty() && normalizedPassword.isEmpty()) return null
        return Details(normalizedHost, port, normalizedUsername, normalizedPassword)
    }

    /**
     * Parses a port typed into a text field: blank means "not stored" (0), and anything else must
     * be a whole number in 1..65535. Rejects rather than silently defaulting, so a typo cannot
     * quietly store the wrong server.
     */
    @JvmStatic
    fun port(text: String?): Int {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return 0
        require(INTEGER_TEXT.matches(value)) { "Port must be a whole number" }
        val port = value.toIntOrNull() ?: throw IllegalArgumentException("Port must be a whole number")
        require(port in MIN_PORT..MAX_PORT) { "Port must be between $MIN_PORT and $MAX_PORT" }
        return port
    }

    /** The port a UI should show for [port]: the stored value, or the SSH default when unset. */
    @JvmStatic
    fun displayPort(port: Int): String =
        String.format(Locale.ROOT, "%d", if (port == 0) DEFAULT_PORT else port)

    /** Trimmed host/user names may not contain whitespace or control characters. */
    private fun normalize(value: String?, limit: Int, label: String): String {
        val text = value?.trim().orEmpty()
        require(text.length <= limit) { "$label is too long" }
        require(text.none { it.isWhitespace() || it.isISOControl() }) {
            "$label cannot contain spaces or control characters"
        }
        return text
    }

    /**
     * Passwords may contain spaces, but not control characters: they must survive a copy/paste into
     * a terminal and the JSON vault without silently changing. Whitespace-only input is treated as
     * "no password" rather than as a one-space secret typed by accident.
     */
    private fun normalizePassword(value: String?): String {
        val text = value.orEmpty()
        if (text.isBlank()) return ""
        require(text.length <= MAX_PASSWORD_CHARS) { "SSH password is too long" }
        require(text.none { it.isISOControl() }) {
            "SSH password cannot contain control characters"
        }
        return text
    }
}
