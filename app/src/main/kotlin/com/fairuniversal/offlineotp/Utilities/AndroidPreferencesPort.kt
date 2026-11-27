package com.fairuniversal.offlineotp.Utilities

import android.content.Context
import android.preference.PreferenceManager
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.ui.portable.PortableCardDisplaySelection
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout
import com.fairuniversal.offlineotp.ui.portable.PortableLabelDisplay
import com.fairuniversal.offlineotp.ui.portable.PortableTapMode

import java.util.Collections
import java.util.Locale

/**
 * Adapts the app's `SharedPreferences` to [PreferencesExport.SettingsPort].
 *
 * The port is deliberately narrow, and this class is where the platform's own knowledge stays:
 * a value is read in the type the catalog declares, never "whatever was stored". A key whose stored
 * value is of another type reads as absent instead of being coerced, because the export must not
 * invent a setting that the app itself would not read back the same way.
 *
 * The backup password is the one value that is not plain preference text — it is wrapped by the
 * device's Keystore — so it goes through [Settings.getBackupPasswordEnc]/`setBackupPassword`
 * rather than the generic path. That is also why it is the only secret the export can carry.
 */
class AndroidPreferencesPort(
    context: Context,
    private val settings: Settings = Settings(context)
) : PreferencesExport.SettingsPort {

    /** The same store [android.preference.PreferenceManager] hands to [Settings]. */
    private val store = PreferenceManager.getDefaultSharedPreferences(context)

    override fun storedKeys(): Set<String> = all().keys.mapNotNull { it }.toSet()

    /**
     * The whole store. The platform's `getAll()` is non-null; the sandbox stub types it nullable, so
     * the null case is absorbed once here rather than at each call site.
     */
    private fun all(): Map<String, *> = store.getAll() ?: emptyMap<String, Any>()

    override fun read(key: String): PreferencesExport.Value? {
        if (!store.contains(key)) return null
        val raw = all()[key] ?: return null
        return when (PreferencesExport.setting(key)?.kind) {
            PreferencesExport.Kind.BOOLEAN -> (raw as? Boolean)?.let { PreferencesExport.Value.Bool(it) }
            PreferencesExport.Kind.INTEGER -> (raw as? Int)?.let { PreferencesExport.Value.IntValue(it) }
            PreferencesExport.Kind.STRING -> (raw as? String)?.let { PreferencesExport.Value.Text(it) }
            PreferencesExport.Kind.STRING_SET -> (raw as? Set<*>)
                ?.mapNotNull { it as? String }
                ?.takeIf { it.size == (raw as Set<*>).size }
                ?.let { PreferencesExport.Value.TextSet(it.sorted()) }
            else -> null
        }
    }

    override fun readBackupPassword(): String? = settings.getBackupPasswordEnc().ifEmpty { null }

    override fun write(key: String, value: PreferencesExport.Value) {
        val editor = store.edit()
        when (val kind = PreferencesExport.setting(key)?.kind) {
            PreferencesExport.Kind.BOOLEAN -> {
                val typed = value as? PreferencesExport.Value.Bool
                    ?: throw IllegalArgumentException("$key is not a boolean")
                editor.putBoolean(key, typed.value)
            }
            PreferencesExport.Kind.INTEGER -> {
                val typed = value as? PreferencesExport.Value.IntValue
                    ?: throw IllegalArgumentException("$key is not an integer")
                editor.putInt(key, typed.value)
            }
            PreferencesExport.Kind.STRING, PreferencesExport.Kind.SECRET -> {
                val typed = value as? PreferencesExport.Value.Text
                    ?: throw IllegalArgumentException("$key is not text")
                // The backup password keeps its Keystore wrapping; it never lands in the prefs as
                // plaintext, which is why it is written through Settings rather than the editor.
                if (kind == PreferencesExport.Kind.SECRET) {
                    if (!settings.setBackupPassword(typed.value)) {
                        throw IllegalStateException("The backup password could not be re-encrypted")
                    }
                    return
                }
                editor.putString(key, typed.value)
            }
            PreferencesExport.Kind.STRING_SET -> {
                val typed = value as? PreferencesExport.Value.TextSet
                    ?: throw IllegalArgumentException("$key is not a set")
                editor.putStringSet(key, Collections.unmodifiableSet(LinkedHashSet(typed.values)))
            }
            PreferencesExport.Kind.DEVICE_BOUND -> throw IllegalArgumentException("$key never leaves this device")
            null -> throw IllegalArgumentException("$key has no portable meaning")
        }
        editor.apply()
    }
}

/** Persists the active search-field set (`label`, `issuer`, `tags`); requires at least one field. */
fun Settings.setSearchValues(context: Context, values: Collection<Constants.SearchIncludes>): Boolean {
    val normalized = values
        .map { it.name.lowercase(Locale.ROOT) }
        .toCollection(LinkedHashSet())
    if (normalized.isEmpty()) return false
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putStringSet(context.getString(R.string.settings_key_search_includes), normalized)
        .commit()
}

/** Current inactivity auto-lock timeout in seconds, or `0` when inactivity relocking is disabled. */
fun Settings.currentAutoLockSeconds(): Int {
    return if (!getAuthInactivity()) 0 else getAuthInactivityDelay().coerceAtLeast(0)
}

/** Persists the shared [com.fairuniversal.offlineotp.ui.vault.AutoLockDialog] selection. */
fun Settings.applyAutoLockSettings(
    context: Context,
    seconds: Int,
    relockOnBackground: Boolean = getRelockOnBackground()
): Boolean {
    val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
    editor.putBoolean(context.getString(R.string.settings_key_relock_background), relockOnBackground)
    if (seconds <= 0) {
        editor.putBoolean(context.getString(R.string.settings_key_auth_inactivity), false)
    } else {
        val rawMin = context.resources.getInteger(R.integer.settings_auth_min_inactivity_delay)
        val rawMax = context.resources.getInteger(R.integer.settings_auth_max_inactivity_delay)
        val minDelay = if (rawMin in 1..rawMax) rawMin else 10
        val maxDelay = if (rawMin in 1..rawMax) rawMax else 3600
        val clamped = seconds.coerceIn(minDelay, maxDelay)
        editor.putBoolean(context.getString(R.string.settings_key_auth_inactivity), true)
        editor.putInt(context.getString(R.string.settings_key_auth_inactivity_delay), clamped)
    }
    return editor.commit()
}

/** Projects Android's stored card display & interaction preferences into the shared selection DTO. */
fun Settings.currentCardDisplaySelection(context: Context): PortableCardDisplaySelection {
    val layout = when (getCardLayout()) {
        Constants.CardLayouts.COMPACT -> PortableCardLayout.COMPACT
        Constants.CardLayouts.FULL -> PortableCardLayout.FULL
        Constants.CardLayouts.DEFAULT -> PortableCardLayout.DEFAULT
    }
    val labelDisplay = when (getLabelDisplay()) {
        Constants.LabelDisplay.SCROLL -> PortableLabelDisplay.SCROLL
        Constants.LabelDisplay.MULTILINE -> PortableLabelDisplay.MULTILINE
        Constants.LabelDisplay.TRUNCATE -> PortableLabelDisplay.TRUNCATE
    }
    val store = PreferenceManager.getDefaultSharedPreferences(context)
    val defaultThumb = context.getString(R.string.settings_default_thumbnail_size).trim()
    val rawThumb = (store.getString(
        context.getString(R.string.settings_key_thumbnail_size),
        defaultThumb
    ) ?: defaultThumb).trim()
    val parsedThumbDp = rawThumb.removeSuffix("dp").toIntOrNull()
    val showThumbnails = parsedThumbDp == null || parsedThumbDp > 0
    val customThumbDp = when {
        !showThumbnails -> 0
        rawThumb == defaultThumb || parsedThumbDp == null -> 0
        else -> PortableCardLayout.normalizeThumbnailSizeDp(parsedThumbDp)
    }
    val groupSize = getTokenSplitGroupSize().let { if (it == 0 || it == 2 || it == 3) it else 3 }
    return PortableCardDisplaySelection(
        layout = layout,
        hideIssuer = isHideIssuerEnabled(),
        showPreviousToken = getShowPrevToken(),
        tokenGroupSize = groupSize,
        showIndividualCountdowns = isShowIndividualTimeoutsEnabled(),
        labelDisplay = labelDisplay,
        showThumbnails = showThumbnails,
        labelSizeSp = PortableTapMode.normalizeLabelSizeSp(getLabelSize().coerceAtLeast(12)),
        highlightExpiringTokens = isHighlightTokenOptionEnabled(),
        tapSingle = toPortableTapMode(getTapSingle()),
        tapDouble = toPortableTapMode(getTapDouble()),
        hideGlobalTimeout = isHideGlobalTimeoutEnabled(),
        tapToRevealTimeoutSeconds = PortableTapMode.normalizeRevealTimeoutSeconds(getTapToRevealTimeout()),
        thumbnailSizeDp = customThumbDp,
        focusSearchOnStart = isFocusSearchOnStartEnabled(),
        minimizeOnCopy = isMinimizeAppOnCopyEnabled()
    )
}

/** Atomically commits a [PortableCardDisplaySelection] from the shared card display dialog. */
fun Settings.applyCardDisplaySelection(
    context: Context,
    selection: PortableCardDisplaySelection
): Boolean {
    val splitSize = (selection.tokenGroupSize ?: 3).let {
        if (it == 0 || it == 2 || it == 3) it else 3
    }
    val resolvedLabelSp = if (selection.labelSizeSp <= 0) {
        context.resources.getInteger(R.integer.settings_default_label_size)
    } else {
        PortableTapMode.normalizeLabelSizeSp(selection.labelSizeSp)
    }
    val resolvedThumb = when {
        !selection.showThumbnails -> "0dp"
        selection.thumbnailSizeDp <= 0 -> context.getString(R.string.settings_default_thumbnail_size)
        else -> "${PortableCardLayout.normalizeThumbnailSizeDp(selection.thumbnailSizeDp)}dp"
    }
    val resolvedTapSingle = resolveStoredTapToken(selection.tapSingle, getTapSingle())
    val resolvedTapDouble = resolveStoredTapToken(selection.tapDouble, getTapDouble())
    val resolvedRevealTimeout = PortableTapMode.normalizeRevealTimeoutSeconds(selection.tapToRevealTimeoutSeconds)
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(R.string.settings_key_card_layout), selection.layout.name.lowercase(Locale.ROOT))
        .putBoolean(context.getString(R.string.settings_key_hide_issuer), selection.hideIssuer)
        .putBoolean(context.getString(R.string.settings_key_show_prev_token), selection.showPreviousToken)
        .putString(context.getString(R.string.settings_key_split_group_size), splitSize.toString())
        .putBoolean(context.getString(R.string.settings_key_show_individual_timeouts), selection.showIndividualCountdowns)
        .putString(context.getString(R.string.settings_key_label_display), selection.labelDisplay.name.lowercase(Locale.ROOT))
        .putString(context.getString(R.string.settings_key_thumbnail_size), resolvedThumb)
        .putInt(context.getString(R.string.settings_key_label_size), resolvedLabelSp)
        .putBoolean(context.getString(R.string.settings_key_label_highlight_token), selection.highlightExpiringTokens)
        .putString(context.getString(R.string.settings_key_tap_single), resolvedTapSingle)
        .putString(context.getString(R.string.settings_key_tap_double), resolvedTapDouble)
        .putBoolean(context.getString(R.string.settings_key_hide_global_timeout), selection.hideGlobalTimeout)
        .putInt(context.getString(R.string.settings_key_tap_to_reveal_timeout), resolvedRevealTimeout)
        .putBoolean(context.getString(R.string.settings_key_focus_search_on_start), selection.focusSearchOnStart)
        .putBoolean(context.getString(R.string.settings_key_minimize_on_copy), selection.minimizeOnCopy)
        .commit()
}

private fun toPortableTapMode(mode: Constants.TapMode): PortableTapMode = when (mode) {
    Constants.TapMode.NOTHING -> PortableTapMode.NOTHING
    Constants.TapMode.REVEAL -> PortableTapMode.REVEAL
    Constants.TapMode.COPY,
    Constants.TapMode.COPY_BACKGROUND,
    Constants.TapMode.SEND_KEYSTROKES -> PortableTapMode.COPY
}

private fun resolveStoredTapToken(chosen: PortableTapMode, existing: Constants.TapMode): String {
    val preserveSpecializedCopy = chosen == PortableTapMode.COPY &&
        (existing == Constants.TapMode.COPY_BACKGROUND || existing == Constants.TapMode.SEND_KEYSTROKES)
    return if (preserveSpecializedCopy) {
        existing.name.lowercase(Locale.ROOT)
    } else {
        chosen.name.lowercase(Locale.ROOT)
    }
}

/** Keep the native ListPreference wire values while sharing the selection UI. */
fun Settings.applyTapAction(context: Context, doubleTap: Boolean, mode: Constants.TapMode): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(if (doubleTap) R.string.settings_key_tap_double else R.string.settings_key_tap_single),
            mode.name.lowercase(Locale.ROOT))
        .commit()

/** Android policy values; enforcement stays in BaseActivity/Tools rather than Compose. */
data class ScreenPrivacySelection(
    val allowScreenshots: Boolean,
    val hideInAppSwitcher: Boolean,
    val relockOnScreenOff: Boolean
)

fun Settings.currentScreenPrivacy() = ScreenPrivacySelection(
    getScreenshotsEnabled(), getHideInAppSwitcher(), getRelockOnScreenOff())

/** One editor commit keeps the three related policy choices coherent for listeners. */
fun Settings.applyScreenPrivacy(context: Context, selection: ScreenPrivacySelection): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(context.getString(R.string.settings_key_enable_screenshot), selection.allowScreenshots)
        .putBoolean(context.getString(R.string.settings_key_hide_in_app_switcher), selection.hideInAppSwitcher)
        .putBoolean(context.getString(R.string.settings_key_relock_screen_off), selection.relockOnScreenOff)
        .commit()

/** Fixed allowlist for app-owned appearance choices; never accepts an arbitrary preference key. */
enum class AppearancePreference(val keyId: Int, val entriesId: Int, val valuesId: Int, val defaultId: Int) {
    LANGUAGE(R.string.settings_key_lang, R.array.settings_entries_lang, R.array.settings_values_lang, R.string.settings_default_lang),
    THEME_MODE(R.string.settings_key_theme_mode, R.array.settings_entries_theme_mode, R.array.settings_values_theme_mode, R.string.settings_default_theme_mode),
    THEME(R.string.settings_key_theme, R.array.settings_entries_theme, R.array.settings_values_theme, R.string.settings_default_theme),
    BLACK_AUTO(R.string.settings_key_theme_black_auto, R.array.settings_entries_black_auto, R.array.settings_values_black_auto, R.string.settings_default_theme)
}

fun Settings.currentAppearanceValue(context: Context, preference: AppearancePreference): String {
    if (preference == AppearancePreference.BLACK_AUTO) return PreferenceManager.getDefaultSharedPreferences(context)
        .getBoolean(context.getString(preference.keyId), false).toString()
    return PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(preference.keyId),
        context.getString(preference.defaultId)) ?: context.getString(preference.defaultId)
}

fun Settings.applyAppearanceValue(context: Context, preference: AppearancePreference, value: String): Boolean {
    if (value !in context.resources.getStringArray(preference.valuesId)) return false
    val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
    if (preference == AppearancePreference.BLACK_AUTO) editor.putBoolean(context.getString(preference.keyId), value == "true")
    else editor.putString(context.getString(preference.keyId), value)
    return editor.commit()
}

/** Values are persisted unchanged; the Android authentication adapter enforces these policies. */
data class InputProtectionSelection(val blockAccessibility: Boolean, val blockAutofill: Boolean, val autoUnlockAfterAutofill: Boolean)

fun Settings.currentInputProtection() = InputProtectionSelection(
    getBlockAccessibility(), getBlockAutofill(), getAutoUnlockAfterAutofill())

fun Settings.applyInputProtection(context: Context, selection: InputProtectionSelection): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(context.getString(R.string.settings_key_block_accessibility), selection.blockAccessibility)
        .putBoolean(context.getString(R.string.settings_key_block_autofill), selection.blockAutofill)
        .putBoolean(context.getString(R.string.settings_key_auto_unlock_after_autofill), selection.autoUnlockAfterAutofill)
        .commit()

/** No rounding/clamping: reject out-of-range input instead of committing a different timeout. */
fun Settings.applyRevealTimeout(context: Context, seconds: Int): Boolean {
    val min = context.resources.getInteger(R.integer.settings_min_tap_to_reveal_timeout)
    val max = context.resources.getInteger(R.integer.settings_max_tap_to_reveal_timeout)
    if (seconds !in min..max) return false
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putInt(context.getString(R.string.settings_key_tap_to_reveal_timeout), seconds).commit()
}

/** Manual export naming/destination preferences; no files are touched by these adapters. */
data class BackupFileSelection(val appendDateTime: Boolean, val askForDestination: Boolean)

fun Settings.currentBackupFileSelection() = BackupFileSelection(getIsAppendingDateTimeToBackups(), getBackupAsk())

fun Settings.applyBackupFileSelection(context: Context, selection: BackupFileSelection): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(context.getString(R.string.settings_key_backup_append_date_time), selection.appendDateTime)
        .putBoolean(context.getString(R.string.settings_key_backup_ask), selection.askForDestination)
        .commit()

/** Saving retention only configures subsequent backup work; never prunes at dialog confirmation. */
fun Settings.applyBackupRetention(context: Context, keep: Int): Boolean {
    if (keep !in BackupRetention.CHOICES) return false
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(R.string.settings_key_auto_backup_keep), keep.toString()).commit()
}

/** UI behavior only; copying, focus and countdown rendering remain platform/vault responsibilities. */
data class VaultBehaviorSelection(val minimizeOnCopy: Boolean, val focusSearchOnStart: Boolean, val hideGlobalTimeout: Boolean)

fun Settings.currentVaultBehavior() = VaultBehaviorSelection(
    isMinimizeAppOnCopyEnabled(), isFocusSearchOnStartEnabled(), isHideGlobalTimeoutEnabled())

fun Settings.applyVaultBehavior(context: Context, selection: VaultBehaviorSelection): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(context.getString(R.string.settings_key_minimize_on_copy), selection.minimizeOnCopy)
        .putBoolean(context.getString(R.string.settings_key_focus_search_on_start), selection.focusSearchOnStart)
        .putBoolean(context.getString(R.string.settings_key_hide_global_timeout), selection.hideGlobalTimeout)
        .commit()

fun Settings.applyTagBehavior(context: Context, mode: Constants.TagFunctionality): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(R.string.settings_key_tag_functionality), mode.name.lowercase(Locale.ROOT)).commit()

/** Engine selection does not clear the inactive engine's provider/key-file configuration. */
fun Settings.applyOpenPgpMode(context: Context, mode: OpenPgpMode): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(R.string.settings_key_openpgp_mode), OpenPgpMode.preferenceValue(mode)).commit()

/** Preserve recipient text verbatim; parsing/deduplication still belongs to OpenPgpRecipientIds. */
fun Settings.applyOpenPgpProviderOptions(context: Context, recipients: String, verify: Boolean): Boolean {
    if (recipients.length > InputPolicy.MAX_TEXT_CHARS) return false
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(R.string.settings_key_openpgp_key_encrypt), recipients)
        .putBoolean(context.getString(R.string.settings_key_openpgp_verify), verify).commit()
}

fun Settings.currentAutoBackupMode(): Constants.AutoBackup = when {
    getAutoBackupEncryptedFullEnabled() -> Constants.AutoBackup.ALL_EDITS
    getAutoBackupEncryptedPasswordsEnabled() -> Constants.AutoBackup.NEW_ENTRIES
    else -> Constants.AutoBackup.OFF
}

/** Always allow OFF; enabling requires the same readiness check used by native backup settings. */
fun Settings.applyAutoBackupMode(context: Context, mode: Constants.AutoBackup): Boolean {
    if (mode != Constants.AutoBackup.OFF && BackupHelper.autoBackupType(context) != Constants.BackupType.ENCRYPTED) return false
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(context.getString(R.string.settings_key_auto_backup_password_enc), mode.name.lowercase(Locale.ROOT)).commit()
}

/** Fixed platform policy allowlist: UI configuration never executes the selected actions. */
enum class PolicyChecklist(val keyId: Int, val allowed: Set<String>) {
    PANIC(R.string.settings_key_panic, setOf("accounts", "settings")),
    BACKUP_BROADCASTS(R.string.settings_key_backup_broadcasts, setOf("encrypted"))
}

fun Settings.applyPolicyChecklist(context: Context, policy: PolicyChecklist, values: Set<String>): Boolean {
    if (values.any { it !in policy.allowed }) return false
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putStringSet(context.getString(policy.keyId), LinkedHashSet(values)).commit()
}

/** Only the obsolete plaintext value requires deletion consent; wrapped credentials remain intact. */
fun Settings.hasLegacyBackupPassword(context: Context): Boolean =
    !PreferenceManager.getDefaultSharedPreferences(context)
        .getString(context.getString(R.string.settings_key_backup_password), "").isNullOrEmpty()

/** Atomically select a source and, only with explicit consent, remove obsolete plaintext storage. */
fun Settings.applyBackupPasswordSource(context: Context, useLogin: Boolean, removeLegacy: Boolean = false): Boolean {
    if (useLogin && hasLegacyBackupPassword(context) && !removeLegacy) return false
    val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(context.getString(R.string.settings_key_auto_backup_use_login), useLogin)
        .remove(LoginBackupPolicy.VAULT_COPY)
    if (useLogin && removeLegacy) editor.remove(context.getString(R.string.settings_key_backup_password))
    return editor.commit()
}

/** Select full-file copies explicitly without deleting any previously configured export password. */
fun Settings.applyVaultCopyBackupSource(context: Context): Boolean {
    try { DatabaseHelper.vaultSnapshot(context, VaultSession.getKey()).fill(0) }
    catch (_: Exception) { return false }
    return PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(LoginBackupPolicy.VAULT_COPY, true).commit()
}

/** Wrap a newly entered separate password; empty input clears it without needing a usable KeyStore. */
fun Settings.applySeparateBackupPassword(context: Context, value: String): Boolean {
    if (value.length > InputPolicy.MAX_TEXT_CHARS) return false
    return try {
        val encoded = if (value.isEmpty()) "" else {
            val pair = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD) ?: return false
            val plain = value.toByteArray(Charsets.UTF_8)
            try {
                android.util.Base64.encodeToString(EncryptionHelper.encrypt(pair.public, plain), android.util.Base64.URL_SAFE)
            } finally { plain.fill(0) }
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(context.getString(R.string.settings_key_backup_password_enc), encoded).commit()
    } catch (_: Exception) { false }
}

fun Settings.applySpecialFeatures(context: Context, enabled: Boolean): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putBoolean(context.getString(R.string.settings_key_special_features), enabled).commit()
