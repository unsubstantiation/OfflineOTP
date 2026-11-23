package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.KeyValueSettings
import com.fairuniversal.offlineotp.Utilities.PasswordVault
import com.fairuniversal.offlineotp.Utilities.VaultReadError
import com.fairuniversal.offlineotp.Utilities.VaultSettingsKeys
import com.fairuniversal.offlineotp.Utilities.VaultWriteError
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult

import java.util.ArrayList
import javax.crypto.SecretKey

/**
 * Headless application controller over the portable [PasswordVault]. The Compose host only sees
 * [Session] outcomes and never touches crypto or storage types directly, which keeps every branch
 * testable without a display.
 */
class VaultController(private val vault: PasswordVault) {
    private val writeGate = DesktopWriteGate()
    val persistenceBusy: Boolean get() = writeGate.busy
    private fun exclusiveSession(operation: () -> Session): Session =
        writeGate.exclusive<Session>(Session.Error(Failure.IO_FAILURE), operation)


    /** UI-facing failure reasons; the wording lives in the UI layer. */
    enum class Failure { MISSING, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE, ALREADY_EXISTS, INVALID_INPUT }

    sealed class Session {
        data class Loaded(val entries: ArrayList<Entry>, val key: SecretKey) : Session()
        data class Error(val failure: Failure) : Session()
    }

    fun isInitialized(): Boolean = vault.isInitialized()

    /**
     * The portable preference store beside the vault, exposed for the preferences export/import.
     * It is available whether or not the vault is unlocked: these are app settings, not secrets.
     */
    val preferences: KeyValueSettings get() = vault.preferences

    /** Provisions a new empty vault and immediately unlocks it so the window can render. */
    fun createVault(password: String): Session = createVault(password, ArrayList())

    /** Creates the first vault with already-decoded encrypted-backup entries in one vault write. */
    fun createVault(password: String, initialEntries: ArrayList<Entry>): Session = exclusiveSession {
        when (val result = vault.create(password, initialEntries)) {
            is VaultWriteResult.Saved -> openUnchecked(password)
            is VaultWriteResult.Rejected -> Session.Error(mapWrite(result.error))
            is VaultWriteResult.RecoveryRequired -> Session.Error(Failure.IO_FAILURE)
        }
    }

    fun openVault(password: String): Session = exclusiveSession { openUnchecked(password) }

    private fun openUnchecked(password: String): Session = when (val outcome = vault.open(password)) {
        is PasswordVault.Open.Unlocked -> Session.Loaded(outcome.entries, outcome.key)
        is PasswordVault.Open.Failed -> Session.Error(mapRead(outcome.error))
    }

    /**
     * Re-keys the vault from [currentPassword] to [newPassword] using fresh Argon2id parameters
     * and returns the preserved entries + new session key so an open session stays valid.
     */
    fun changePassword(currentPassword: String, newPassword: String): Session = exclusiveSession {
        when (val outcome = vault.changePassword(currentPassword, newPassword)) {
            is PasswordVault.ReKey.Changed -> Session.Loaded(outcome.entries, outcome.key)
            is PasswordVault.ReKey.Failed -> Session.Error(mapWrite(outcome.error))
        }
    }

    /**
     * Opens the vault from a hardware-derived key (FIDO2 PRF envelope). The key never touches the
     * settings verifier; GCM authentication is the access check, exactly as on Android's
     * hardware-unlock path.
     */
    fun unlockWithKey(key: SecretKey): Session = exclusiveSession {
        when (val outcome = vault.openWithKey(key)) {
            is PasswordVault.Open.Unlocked -> Session.Loaded(outcome.entries, outcome.key)
            is PasswordVault.Open.Failed -> Session.Error(mapRead(outcome.error))
        }
    }

    fun save(key: SecretKey, entries: ArrayList<Entry>): Boolean =
        writeGate.exclusive(false) { vault.save(key, entries) is VaultWriteResult.Saved }

    /** The worker already owns this controller's lease; do not recursively call save(). */
    fun detachedSave(worker: java.util.concurrent.Executor, dispatch: (Runnable) -> Unit): DesktopDetachedSave =
        DesktopDetachedSave(worker, dispatch, writeGate) { key, entries ->
            vault.save(key, entries) is VaultWriteResult.Saved
        }

    /**
     * The list order chosen last time, mirroring Android's persisted sort setting. A missing or
     * unreadable value falls back to the vault order, never to an exception.
     */
    fun savedSortMode(): Constants.SortMode = try {
        Constants.SortMode.valueOf(vault.preferences.getString(SORT_MODE_KEY, Constants.SortMode.UNSORTED.name))
    } catch (_: RuntimeException) {
        Constants.SortMode.UNSORTED
    }

    /** Remembers the list order beside the vault; a write failure only loses the preference. */
    fun saveSortMode(mode: Constants.SortMode) {
        try {
            vault.preferences.putString(SORT_MODE_KEY, mode.name)
        } catch (_: Exception) {
            // The order still applies to the open session.
        }
    }

    /** Configured idle auto-lock timeout in seconds (defaults to 10 minutes; `0` disables auto-lock). */
    fun savedInactivityLockSeconds(): Int = try {
        vault.preferences.getInt(
            VaultSettingsKeys.UI_INACTIVITY_LOCK_SECONDS,
            com.fairuniversal.offlineotp.ui.vault.AutoLockOptions.DEFAULT_SECONDS
        ).coerceAtLeast(0)
    } catch (_: RuntimeException) {
        com.fairuniversal.offlineotp.ui.vault.AutoLockOptions.DEFAULT_SECONDS
    }

    /** Persists the idle auto-lock timeout (`0` explicitly disables auto-lock). */
    fun saveInactivityLockSeconds(seconds: Int) {
        trySaveInactivityLockSeconds(seconds.coerceAtLeast(0))
    }

    /** Reports failure so the Compose settings form does not claim an unsaved change. */
    fun trySaveInactivityLockSeconds(seconds: Int): Boolean {
        if (seconds < 0) return false
        return try {
            vault.preferences.putInt(VaultSettingsKeys.UI_INACTIVITY_LOCK_SECONDS, seconds)
            true
        } catch (_: Exception) { false }
    }

    fun savedCardLayout(): Constants.CardLayouts = try {
        Constants.CardLayouts.valueOf(vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_CARD_LAYOUT, Constants.CardLayouts.DEFAULT.name))
    } catch (_: RuntimeException) { Constants.CardLayouts.DEFAULT }

    fun trySaveCardLayout(layout: Constants.CardLayouts): Boolean = try {
        vault.preferences.putString(com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_CARD_LAYOUT, layout.name)
        true
    } catch (_: Exception) { false }

    fun savedTagFunctionality(): Constants.TagFunctionality = try {
        Constants.TagFunctionality.valueOf(vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAG_FUNCTIONALITY,
            Constants.TagFunctionality.OR.name
        ))
    } catch (_: RuntimeException) { Constants.TagFunctionality.OR }

    fun saveTagFunctionality(functionality: Constants.TagFunctionality) {
        try {
            vault.preferences.putString(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAG_FUNCTIONALITY,
                functionality.name
            )
        } catch (_: Exception) {
            // The tag rule still applies to the open session.
        }
    }

    fun savedHideIssuer(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_HIDE_ISSUER,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveHideIssuer(hideIssuer: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_HIDE_ISSUER,
            hideIssuer.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedShowPreviousToken(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SHOW_PREV_TOKEN,
            "true"
        ).toBooleanStrictOrNull() ?: true
    } catch (_: RuntimeException) { true }

    fun trySaveShowPreviousToken(showPrevious: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SHOW_PREV_TOKEN,
            showPrevious.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedShowIndividualTimeouts(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SHOW_INDIVIDUAL_TIMEOUTS,
            "true"
        ).toBooleanStrictOrNull() ?: true
    } catch (_: RuntimeException) { true }

    fun trySaveShowIndividualTimeouts(showIndividual: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SHOW_INDIVIDUAL_TIMEOUTS,
            showIndividual.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedHideGlobalTimeout(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_HIDE_GLOBAL_TIMEOUT,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveHideGlobalTimeout(hideGlobal: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_HIDE_GLOBAL_TIMEOUT,
            hideGlobal.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedLabelDisplay(): Constants.LabelDisplay = try {
        Constants.LabelDisplay.valueOf(vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_LABEL_DISPLAY,
            Constants.LabelDisplay.TRUNCATE.name
        ))
    } catch (_: RuntimeException) { Constants.LabelDisplay.TRUNCATE }

    fun trySaveLabelDisplay(labelDisplay: Constants.LabelDisplay): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_LABEL_DISPLAY,
            labelDisplay.name
        )
        true
    } catch (_: Exception) { false }

    fun savedShowThumbnails(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SHOW_THUMBNAILS,
            "true"
        ).toBooleanStrictOrNull() ?: true
    } catch (_: RuntimeException) { true }

    fun trySaveShowThumbnails(showThumbnails: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SHOW_THUMBNAILS,
            showThumbnails.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedLabelSizeSp(): Int = try {
        val raw = vault.preferences.getInt(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_LABEL_SIZE_SP,
            0
        )
        if (raw == 0 || raw in 12..24) raw else 0
    } catch (_: RuntimeException) { 0 }

    fun trySaveLabelSizeSp(sizeSp: Int): Boolean {
        if (sizeSp != 0 && sizeSp !in 12..24) return false
        return try {
            vault.preferences.putInt(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_LABEL_SIZE_SP,
                sizeSp
            )
            true
        } catch (_: Exception) { false }
    }

    fun savedHighlightToken(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_HIGHLIGHT_TOKEN,
            "true"
        ).toBooleanStrictOrNull() ?: true
    } catch (_: RuntimeException) { true }

    fun trySaveHighlightToken(highlight: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_HIGHLIGHT_TOKEN,
            highlight.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedTapSingle(): com.fairuniversal.offlineotp.ui.portable.PortableTapMode = try {
        com.fairuniversal.offlineotp.ui.portable.PortableTapMode.fromName(
            vault.preferences.getString(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAP_SINGLE,
                "REVEAL"
            ),
            fallback = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL
        )
    } catch (_: RuntimeException) { com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL }

    fun trySaveTapSingle(mode: com.fairuniversal.offlineotp.ui.portable.PortableTapMode): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAP_SINGLE,
            mode.name
        )
        true
    } catch (_: Exception) { false }

    fun savedTapDouble(): com.fairuniversal.offlineotp.ui.portable.PortableTapMode = try {
        com.fairuniversal.offlineotp.ui.portable.PortableTapMode.fromName(
            vault.preferences.getString(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAP_DOUBLE,
                "NOTHING"
            ),
            fallback = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING
        )
    } catch (_: RuntimeException) { com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING }

    fun trySaveTapDouble(mode: com.fairuniversal.offlineotp.ui.portable.PortableTapMode): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAP_DOUBLE,
            mode.name
        )
        true
    } catch (_: Exception) { false }

    fun savedTapToRevealTimeoutSeconds(): Int = try {
        val raw = vault.preferences.getInt(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAP_TO_REVEAL_TIMEOUT,
            0
        )
        com.fairuniversal.offlineotp.ui.portable.PortableTapMode.normalizeRevealTimeoutSeconds(raw)
    } catch (_: RuntimeException) { 0 }

    fun trySaveTapToRevealTimeoutSeconds(seconds: Int): Boolean {
        if (seconds != 0 && seconds !in 5..600) return false
        return try {
            vault.preferences.putInt(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_TAP_TO_REVEAL_TIMEOUT,
                seconds
            )
            true
        } catch (_: Exception) { false }
    }

    fun savedThumbnailSizeDp(): Int = try {
        val raw = vault.preferences.getInt(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_THUMBNAIL_SIZE_DP,
            0
        )
        if (raw == 0 || raw in 20..96) raw else 0
    } catch (_: RuntimeException) { 0 }

    fun trySaveThumbnailSizeDp(sizeDp: Int): Boolean {
        if (sizeDp != 0 && sizeDp !in 20..96) return false
        return try {
            vault.preferences.putInt(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_THUMBNAIL_SIZE_DP,
                sizeDp
            )
            true
        } catch (_: Exception) { false }
    }

    fun savedFocusSearchOnStart(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_FOCUS_SEARCH_ON_START,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveFocusSearchOnStart(enabled: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_FOCUS_SEARCH_ON_START,
            enabled.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedMinimizeOnCopy(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_MINIMIZE_ON_COPY,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveMinimizeOnCopy(enabled: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_MINIMIZE_ON_COPY,
            enabled.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedSelectedTags(): List<String> = try {
        val raw = vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SELECTED_TAGS,
            ""
        )
        if (raw.isBlank()) {
            emptyList()
        } else {
            raw.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
    } catch (_: RuntimeException) { emptyList() }

    fun trySaveSelectedTags(tags: List<String>): Boolean = try {
        val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) {
            vault.preferences.remove(com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SELECTED_TAGS)
        } else {
            vault.preferences.putString(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SELECTED_TAGS,
                cleaned.joinToString("\n")
            )
        }
        true
    } catch (_: Exception) { false }

    fun savedUntaggedSelected(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_UNTAGGED_SELECTED,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveUntaggedSelected(selected: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_UNTAGGED_SELECTED,
            selected.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedRelockOnBackground(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_RELOCK_BACKGROUND,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveRelockOnBackground(enabled: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_RELOCK_BACKGROUND,
            enabled.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedBackupAppendDateTime(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_BACKUP_APPEND_DATE_TIME,
            "true"
        ).toBooleanStrictOrNull() ?: true
    } catch (_: RuntimeException) { true }

    fun trySaveBackupAppendDateTime(enabled: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_BACKUP_APPEND_DATE_TIME,
            enabled.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedUsedTokensShown(): Boolean = try {
        vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_USED_TOKENS_SHOWN,
            "false"
        ).toBooleanStrictOrNull() ?: false
    } catch (_: RuntimeException) { false }

    fun trySaveUsedTokensShown(shown: Boolean): Boolean = try {
        vault.preferences.putString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_USED_TOKENS_SHOWN,
            shown.toString()
        )
        true
    } catch (_: Exception) { false }

    fun savedTokenGroupSize(): Int? = try {
        val raw = vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SPLIT_GROUP_SIZE,
            ""
        )
        raw.toIntOrNull()?.takeIf { it == 0 || it == 2 || it == 3 }
    } catch (_: RuntimeException) { null }

    fun trySaveTokenGroupSize(groupSize: Int?): Boolean {
        if (groupSize != null && groupSize != 0 && groupSize != 2 && groupSize != 3) return false
        return try {
            if (groupSize == null) {
                vault.preferences.remove(com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SPLIT_GROUP_SIZE)
            } else {
                vault.preferences.putInt(com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SPLIT_GROUP_SIZE, groupSize)
            }
            true
        } catch (_: Exception) { false }
    }

    fun savedSearchIncludes(): List<Constants.SearchIncludes> = try {
        val raw = vault.preferences.getString(
            com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SEARCH_INCLUDES,
            ""
        )
        if (raw.isBlank()) {
            Constants.SearchIncludes.values().toList()
        } else {
            val resolved = raw.split('\n', ',')
                .mapNotNull { token ->
                    val normalized = token.trim().uppercase(java.util.Locale.ROOT)
                    Constants.SearchIncludes.values().firstOrNull { it.name == normalized }
                }
                .distinct()
            resolved.ifEmpty { Constants.SearchIncludes.values().toList() }
        }
    } catch (_: RuntimeException) {
        Constants.SearchIncludes.values().toList()
    }

    fun trySaveSearchIncludes(includes: List<Constants.SearchIncludes>): Boolean {
        val distinct = includes.distinct()
        if (distinct.isEmpty()) return false
        return try {
            vault.preferences.putString(
                com.fairuniversal.offlineotp.Utilities.PreferencesExport.UI_SEARCH_INCLUDES,
                distinct.joinToString("\n") { it.name }
            )
            true
        } catch (_: Exception) { false }
    }

    /** Portable allowlisted preferences only; never export vault auth state or backup passwords. */
    fun exportPreferences(): com.fairuniversal.offlineotp.Utilities.PreferencesExport.Export =
        com.fairuniversal.offlineotp.Utilities.PreferencesExport.export(
            com.fairuniversal.offlineotp.desktop.storage.DesktopPreferencesPort(vault.preferences),
            includeSecrets = false, platform = com.fairuniversal.offlineotp.Utilities.PreferencesExport.PLATFORM_DESKTOP)

    fun importPreferences(plan: com.fairuniversal.offlineotp.desktop.storage.DesktopPreferencesImportPlan): Boolean = try {
        plan.applyTo(vault.preferences)
        true
    } catch (_: Exception) { false }

    fun backup(): Boolean = vault.backup()

    private companion object {
        /** Key used in the client settings file; never part of the encrypted vault document. */
        const val SORT_MODE_KEY = "ui_sort_mode"
    }

    private fun mapRead(error: VaultReadError): Failure = when (error) {
        VaultReadError.MISSING, VaultReadError.MISSING_KEY -> Failure.MISSING
        VaultReadError.AUTHENTICATION_FAILED -> Failure.AUTHENTICATION_FAILED
        VaultReadError.CORRUPT, VaultReadError.RECOVERY_REQUIRED -> Failure.CORRUPT
        VaultReadError.IO_FAILURE -> Failure.IO_FAILURE
    }

    private fun mapWrite(error: VaultWriteError): Failure = when (error) {
        VaultWriteError.MISSING, VaultWriteError.MISSING_KEY -> Failure.MISSING
        VaultWriteError.AUTHENTICATION_FAILED -> Failure.AUTHENTICATION_FAILED
        VaultWriteError.CORRUPT -> Failure.CORRUPT
        VaultWriteError.IO_FAILURE, VaultWriteError.BACKUP_FAILED -> Failure.IO_FAILURE
        VaultWriteError.ALREADY_EXISTS -> Failure.ALREADY_EXISTS
        VaultWriteError.INVALID_INPUT -> Failure.INVALID_INPUT
        VaultWriteError.CANCELED -> Failure.INVALID_INPUT
    }
}
