package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.EmailMaterial

/**
 * Headless FairEmail-account support for the desktop client.
 *
 * Email entries store one IMAP/POP account plus its SMTP identities as JSON inside the encrypted
 * vault (see [EmailMaterial]); Android can create them by hand or import a FairEmail backup. This
 * class supplies the same three pieces without touching Swing, so the Swing shell only renders
 * rows and file choosers while the parsing, mapping and validation stay unit-tested:
 *
 *  - [details] turns an entry into the shared [EmailMaterial.Detail] rows for the read-only
 *    "connection details" view, masking passwords unless they were explicitly revealed;
 *  - [importEntries] maps a FairEmail file backup onto new vault entries and wipes anything it
 *    created when the file turns out to be invalid;
 *  - [exportBackup] writes the selected entries back in FairEmail's passwordless restore format.
 */
object EmailAccounts {
    /**
     * Largest FairEmail backup read from disk. `EmailMaterial` applies the same 8 MiB ceiling to
     * its own import path; keeping the reader at that bound means an oversized file is rejected
     * before it is parsed rather than after.
     */
    const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    /** The email entries in [all], in vault order; other entry kinds are ignored. */
    @JvmStatic
    fun entries(all: List<Entry>): List<Entry> = all.filter { it.isEmail() }

    /**
     * Detail rows for one entry, or `null` when the stored account cannot be decoded (a corrupt
     * entry must produce a message, never a crash or a partially revealed dialog). The rows come
     * from [EmailMaterial.details], the same builder Android's connection-details dialog uses.
     */
    @JvmStatic
    fun details(entry: Entry, revealSecrets: Boolean): List<EmailMaterial.Detail>? {
        if (!entry.isEmail()) return null
        return try {
            EmailMaterial.details(entry.getEmailAccount(), revealSecrets)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build one new email entry from the manual form. Values are validated exactly as FairEmail
     * and the Android "Add email account" dialog validate them, so an impossible port or an
     * address without `@` is refused instead of being stored and failing on import elsewhere.
     *
     * @throws IllegalArgumentException when a field is out of range or missing.
     */
    @JvmStatic
    fun create(name: String, address: String, protocol: Int,
               incomingHost: String, incomingPort: Int, incomingEncryption: Int,
               incomingAuth: Int, incomingUser: String, incomingPassword: String,
               outgoingHost: String, outgoingPort: Int, outgoingEncryption: Int,
               outgoingAuth: Int, outgoingUser: String, outgoingPassword: String,
               tags: List<String> = emptyList()): Entry {
        val account = EmailMaterial.create(
            name.ifBlank { address }, address,
            incomingHost, incomingPort, incomingEncryption, protocol,
            incomingUser, incomingPassword, incomingAuth,
            outgoingHost, outgoingPort, outgoingEncryption,
            outgoingUser, outgoingPassword, outgoingAuth
        )
        return Entry.email(account.account, account.identity, account.identities, ArrayList(tags))
    }

    /**
     * Map a FairEmail backup (plain JSON, the gzip agent form, or a password-encrypted export) to
     * new vault entries. The caller owns the returned entries: pass them to
     * [EntryEditor.importEntries] or destroy them.
     *
     * @throws IllegalArgumentException when the file is not a FairEmail backup, is oversized, or
     *   (for an encrypted export) needs a password that is missing or wrong.
     */
    @JvmStatic
    fun importEntries(backup: ByteArray, password: String?): List<Entry> {
        require(backup.isNotEmpty() && backup.size <= MAX_IMPORT_BYTES) { "Backup too large" }
        val created = ArrayList<Entry>()
        try {
            for (account in EmailMaterial.importBackup(backup, password)) {
                created.add(Entry.email(account.account, account.identity, account.identities))
            }
            return created
        } catch (error: Exception) {
            // Never leave half-built decrypted account material behind on a failed import.
            created.forEach { it.destroy() }
            throw error
        }
    }

    /**
     * Serialize [selected] as FairEmail's unencrypted JSON restore file.
     * The caller owns the returned bytes and must wipe them after writing.
     *
     * @throws IllegalArgumentException when the selection is empty or an entry is inconsistent.
     */
    @JvmStatic
    fun exportBackup(selected: List<Entry>): ByteArray {
        require(selected.isNotEmpty()) { "No email accounts selected" }
        require(selected.all { it.isEmail() }) { "Only email entries can be exported" }
        return EmailMaterial.exportBackup(selected.map { it.getEmailAccount() })
    }

    /** Clamp an account name for display; never returns a blank label for a stored account. */
    @JvmStatic
    fun displayLabel(entry: Entry): String {
        val label = entry.getLabel()
        val issuer = entry.getIssuer()
        val name = label.ifBlank { issuer }.ifBlank { "Email account" }
        return if (issuer.isBlank() || issuer == name) name else "$name ($issuer)"
    }
}
