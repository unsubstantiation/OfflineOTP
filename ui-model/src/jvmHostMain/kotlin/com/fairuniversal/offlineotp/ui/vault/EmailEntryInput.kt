package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog

/** Creation only: never reconstruct an imported account and lose its extra identities/metadata. */
data class EmailEntryInput(
    val name: String = "", val address: String = "", val protocol: Int = 0,
    val incomingHost: String = "", val incomingPort: String = "993", val incomingEncryption: Int = 0,
    val incomingAuth: String = "1", val incomingUser: String = "", val incomingPassword: String = "",
    val outgoingHost: String = "", val outgoingPort: String = "465", val outgoingEncryption: Int = 0,
    val outgoingAuth: String = "1", val outgoingUser: String = "", val outgoingPassword: String = "",
    val tags: String = "",
    val thumbnailName: String? = null
) {
    fun build(): Entry {
        listOf(name, address, incomingHost, outgoingHost).forEach { InputPolicy.text(it, InputPolicy.MAX_TEXT_CHARS) }
        require(thumbnailName.isNullOrEmpty() || thumbnailName in ThumbnailCatalog.NAMES) { "Unknown thumbnail" }
        val parsedTags = tags.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(parsedTags.size <= InputPolicy.MAX_TAGS)
        parsedTags.forEach { InputPolicy.text(it, InputPolicy.MAX_TAG_CHARS) }
        fun number(text: String): Int = text.toIntOrNull() ?: throw IllegalArgumentException("Invalid number")
        val account = EmailMaterial.create(name.ifBlank { address }, address,
            incomingHost, number(incomingPort), incomingEncryption, protocol,
            incomingUser, incomingPassword, number(incomingAuth),
            outgoingHost, number(outgoingPort), outgoingEncryption,
            outgoingUser, outgoingPassword, number(outgoingAuth))
        val entry = Entry.email(account.account, account.identity, account.identities, ArrayList(parsedTags))
        try {
            if (!thumbnailName.isNullOrEmpty()) {
                entry.setThumbnailName(thumbnailName)
            }
            entry.validate()
            return entry
        } catch (error: Exception) {
            entry.destroy()
            throw error
        }
    }
    fun withoutSecrets() = copy(incomingPassword = "", outgoingPassword = "")
    override fun toString() = "EmailEntryInput(redacted)"
}
