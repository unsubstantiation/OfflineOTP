package org.shadowice.flocke.andotp.Utilities

/** Normalizes the comma-separated OpenPGP recipient IDs before passing them across the provider API. */
object OpenPgpRecipientIds {
    @JvmStatic
    fun parse(value: String?): List<String> {
        if (value == null || value.length > InputPolicy.MAX_TEXT_CHARS) return emptyList()
        val recipients = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (recipients.any { it.length > InputPolicy.MAX_TEXT_CHARS }) emptyList() else recipients
    }
}
