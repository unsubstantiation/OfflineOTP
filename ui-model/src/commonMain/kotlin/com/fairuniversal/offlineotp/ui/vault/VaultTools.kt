package com.fairuniversal.offlineotp.ui.vault

/** Secondary actions, ordered and grouped without retaining callbacks or session data. */
enum class VaultTool(val group: Group) {
    IMPORT_URI(Group.IMPORT), IMPORT_PASSWORDS(Group.IMPORT), IMPORT_EMAIL(Group.IMPORT), IMPORT_LINSCP(Group.IMPORT),
    EXPORT_ENCRYPTED(Group.BACKUP), RESTORE_ENCRYPTED(Group.BACKUP), EXPORT_LINSCP(Group.BACKUP), IMPORT_OPENPGP(Group.BACKUP), EXPORT_OPENPGP(Group.BACKUP),
    CARD_LAYOUT(Group.SETTINGS), AUTO_LOCK(Group.SETTINGS);

    enum class Group { IMPORT, BACKUP, SETTINGS }

    fun label(strings: VaultStrings): String = when (this) {
        IMPORT_URI -> strings.importUri
        IMPORT_PASSWORDS -> strings.importPasswords
        IMPORT_EMAIL -> strings.importEmail
        IMPORT_LINSCP -> strings.importLinScp
        EXPORT_OPENPGP -> strings.exportOpenPgp
        IMPORT_OPENPGP -> strings.importOpenPgp
        EXPORT_LINSCP -> strings.exportLinScp
        EXPORT_ENCRYPTED -> strings.exportEncrypted
        RESTORE_ENCRYPTED -> strings.restoreEncrypted
        CARD_LAYOUT -> strings.cardLayout
        AUTO_LOCK -> strings.autoLock
    }
    private fun callback(actions: VaultActions): (() -> Unit)? = when (this) {
        IMPORT_URI -> actions.onImportUri
        IMPORT_PASSWORDS -> actions.onImportPasswords
        IMPORT_EMAIL -> actions.onImportEmail
        IMPORT_LINSCP -> actions.onImportLinScp
        EXPORT_OPENPGP -> actions.onExportOpenPgp
        IMPORT_OPENPGP -> actions.onImportOpenPgp
        EXPORT_LINSCP -> actions.onExportLinScp
        EXPORT_ENCRYPTED -> actions.onExportEncrypted
        RESTORE_ENCRYPTED -> actions.onRestoreEncrypted
        CARD_LAYOUT -> actions.onConfigureLayout
        AUTO_LOCK -> actions.onConfigureAutoLock
    }
    fun invoke(actions: VaultActions) { callback(actions)?.invoke() }
    companion object {
        fun available(actions: VaultActions): List<VaultTool> = values().filter { it.callback(actions) != null }
    }
}
