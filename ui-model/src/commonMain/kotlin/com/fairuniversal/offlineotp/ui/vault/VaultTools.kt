package com.fairuniversal.offlineotp.ui.vault

/** Secondary actions, ordered and grouped without retaining callbacks or session data. */
enum class VaultTool(val group: Group) {
    IMPORT_URI(Group.IMPORT), IMPORT_PASSWORDS(Group.IMPORT), IMPORT_EMAIL(Group.IMPORT), IMPORT_LINSCP(Group.IMPORT),
    EXPORT_ARCHIVES(Group.BACKUP), EXPORT_PASSWORDS(Group.BACKUP), EXPORT_EMAIL(Group.BACKUP), EXPORT_ENCRYPTED(Group.BACKUP), RESTORE_ENCRYPTED(Group.BACKUP), EXPORT_LINSCP(Group.BACKUP), IMPORT_OPENPGP(Group.BACKUP), EXPORT_OPENPGP(Group.BACKUP),
    REMOVE_HARDWARE_UNLOCK(Group.SETTINGS), HARDWARE_COPY(Group.SETTINGS), HARDWARE_OATH(Group.SETTINGS), STORAGE_LOCATION(Group.SETTINGS), CHANGE_PASSWORD(Group.SETTINGS), CARD_LAYOUT(Group.SETTINGS), AUTO_LOCK(Group.SETTINGS), EXPORT_PREFERENCES(Group.SETTINGS), IMPORT_PREFERENCES(Group.SETTINGS);

    enum class Group { IMPORT, BACKUP, SETTINGS }

    fun label(strings: VaultStrings): String = when (this) {
        IMPORT_URI -> strings.importUri
        IMPORT_PASSWORDS -> strings.importPasswords
        IMPORT_EMAIL -> strings.importEmail
        IMPORT_LINSCP -> strings.importLinScp
        EXPORT_OPENPGP -> strings.exportOpenPgp
        IMPORT_OPENPGP -> strings.importOpenPgp
        EXPORT_LINSCP -> strings.exportLinScp
        EXPORT_ARCHIVES -> strings.exportArchives
        EXPORT_PASSWORDS -> strings.exportPasswords
        EXPORT_EMAIL -> strings.exportEmail
        EXPORT_ENCRYPTED -> strings.exportEncrypted
        RESTORE_ENCRYPTED -> strings.restoreEncrypted
        REMOVE_HARDWARE_UNLOCK -> strings.removeHardwareUnlock
        HARDWARE_COPY -> strings.hardwareCopy
        HARDWARE_OATH -> strings.hardwareOath
        STORAGE_LOCATION -> strings.storageLocation
        CHANGE_PASSWORD -> strings.changePassword
        CARD_LAYOUT -> strings.cardLayout
        IMPORT_PREFERENCES -> strings.importPreferences
        EXPORT_PREFERENCES -> strings.exportPreferences
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
        EXPORT_ARCHIVES -> actions.onExportArchives
        EXPORT_PASSWORDS -> actions.onExportPasswords
        EXPORT_EMAIL -> actions.onExportEmail
        EXPORT_ENCRYPTED -> actions.onExportEncrypted
        RESTORE_ENCRYPTED -> actions.onRestoreEncrypted
        REMOVE_HARDWARE_UNLOCK -> actions.onRemoveHardwareUnlock
        HARDWARE_COPY -> actions.onHardwareCopy
        HARDWARE_OATH -> actions.onHardwareOath
        STORAGE_LOCATION -> actions.onStorageLocation
        CHANGE_PASSWORD -> actions.onChangePassword
        CARD_LAYOUT -> actions.onConfigureLayout
        IMPORT_PREFERENCES -> actions.onImportPreferences
        EXPORT_PREFERENCES -> actions.onExportPreferences
        AUTO_LOCK -> actions.onConfigureAutoLock
    }
    fun invoke(actions: VaultActions) { callback(actions)?.invoke() }
    companion object {
        fun available(actions: VaultActions): List<VaultTool> = values().filter { it.callback(actions) != null }
    }
}
