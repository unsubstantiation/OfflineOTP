/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Base64

import org.shadowice.flocke.andotp.Preferences.CredentialsPreference
import org.shadowice.flocke.andotp.R

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import java.util.Collections
import java.util.HashSet
import java.util.Locale
import java.util.function.BooleanSupplier
import javax.crypto.SecretKey

/**
 * Typed accessors for the app's [SharedPreferences], addressed by string resource ID.
 *
 * Runs deprecated-preference migrations on construction when no staged vault transaction is pending.
 */
open class Settings(private val context: Context) {
    private val settings: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        if (DatabaseHelper.recoverPendingDatabase(context)) migrateDeprecatedSettings()
    }

    /** Migrates values that are stored under deprecated preference keys. */
    private fun migrateDeprecatedSettings() {
        if (settings.contains(getResString(R.string.settings_key_auth_password))) {
            if (setAuthCredentials(getString(R.string.settings_key_auth_password, "")) != null)
                remove(R.string.settings_key_auth_password)
        }

        if (settings.contains(getResString(R.string.settings_key_auth_pin))) {
            if (setAuthCredentials(getString(R.string.settings_key_auth_pin, "")) != null)
                remove(R.string.settings_key_auth_pin)
        }

        // The theme mode key was misspelled as "perf_theme_mode" before; move the stored value over.
        if (settings.contains("perf_theme_mode")) {
            settings.edit()
                .putString(getResString(R.string.settings_key_theme_mode), settings.getString("perf_theme_mode", ""))
                .remove("perf_theme_mode")
                .apply()
        }

        if (settings.contains(getResString(R.string.settings_key_locale))) {
            val lang = getString(R.string.settings_key_locale, R.string.settings_default_lang)

            if (oldLangs.contains(lang)) setLocale(newLangs[oldLangs.indexOf(lang)])

            remove(R.string.settings_key_locale)
        }

        if (settings.contains(getResString(R.string.settings_key_tap_to_reveal))) {
            if (getBoolean(R.string.settings_key_tap_to_reveal, false)) {
                setString(R.string.settings_key_tap_single, Constants.TapMode.REVEAL.toString().lowercase(Locale.ENGLISH))
            }
            remove(R.string.settings_key_tap_to_reveal)
        }

        if (settings.contains(getResString(R.string.settings_key_label_scroll))) {
            if (getBoolean(R.string.settings_key_label_scroll, false)) {
                setString(R.string.settings_key_label_display, Constants.LabelDisplay.SCROLL.toString().lowercase(Locale.ENGLISH))
            }
            remove(R.string.settings_key_label_scroll)
        }

        if (settings.contains(getResString(R.string.settings_key_backup_password))) {
            val plainPassword = getBackupPassword()

            try {
                val key: KeyPair? = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)

                if (key != null) {
                    val encPassword = EncryptionHelper.encrypt(key.public, plainPassword.toByteArray(StandardCharsets.UTF_8))

                    setString(R.string.settings_key_backup_password_enc, Base64.encodeToString(encPassword, Base64.URL_SAFE))

                    remove(R.string.settings_key_backup_password)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getResString(resId: Int): String {
        return context.getString(resId)
    }

    private fun getResInt(resId: Int): Int {
        return context.resources.getInteger(resId)
    }

    private fun getString(keyId: Int, defaultId: Int): String {
        return getString(keyId, getResString(defaultId))
    }

    /** Reads a string preference with a non-null fallback. */
    private fun getString(keyId: Int, defaultValue: String): String {
        return settings.getString(getResString(keyId), defaultValue)!!
    }

    private fun getStringSet(keyId: Int, defaultValue: Set<String>): Set<String> {
        return settings.getStringSet(getResString(keyId), defaultValue)!!
    }

    open fun getBoolean(keyId: Int, defaultValue: Boolean): Boolean {
        return settings.getBoolean(getResString(keyId), defaultValue)
    }

    private fun getInt(keyId: Int, defaultId: Int): Int {
        return settings.getInt(getResString(keyId), getResInt(defaultId))
    }

    private fun getIntValue(keyId: Int, defaultValue: Int): Int {
        return settings.getInt(getResString(keyId), defaultValue)
    }

    private fun getLong(keyId: Int, defaultValue: Long): Long {
        return settings.getLong(getResString(keyId), defaultValue)
    }

    open fun setBoolean(keyId: Int, value: Boolean) {
        settings.edit()
            .putBoolean(getResString(keyId), value)
            .apply()
    }

    private fun setInt(keyId: Int, value: Int) {
        settings.edit()
            .putInt(getResString(keyId), value)
            .apply()
    }

    private fun setString(keyId: Int, value: String) {
        settings.edit()
            .putString(getResString(keyId), value)
            .apply()
    }

    private fun setStringSet(keyId: Int, value: Set<String>) {
        settings.edit()
            .putStringSet(getResString(keyId), value)
            .apply()
    }

    /** Removes the entry with the given key. */
    private fun remove(keyId: Int) {
        settings.edit()
            .remove(getResString(keyId))
            .apply()
    }

    /** Resets all settings; with `keep_auth` the credentials and salt survive. */
    open fun clear(keep_auth: Boolean) {
        synchronized(DatabaseHelper.DatabaseFileLock) {
            if (!DatabaseHelper.recoverPendingDatabase(context)) return
            val authMethod = getAuthMethod()
            val authCredentials = getAuthCredentials()
            val authSalt = getString(R.string.settings_key_auth_salt, "")
            val legacyPassword = getOldCredentials(Constants.AuthMethod.PASSWORD)
            val legacyPin = getOldCredentials(Constants.AuthMethod.PIN)
            val encryptionType = getEncryption()
            val authIterations = getIterations()

            val warningShown = getFirstTimeWarningShown()

            val editor = settings.edit()
            editor.clear()

            editor.putBoolean(getResString(R.string.settings_key_security_backup_warning), warningShown)

            if (keep_auth) {
                editor.putString(getResString(R.string.settings_key_encryption), encryptionType.name.lowercase(Locale.ROOT))
                // Stored values are machine tokens: case-map them with Locale.ROOT, never with the
                // user's locale (Turkish "PIN" would otherwise be stored as "pın").
                editor.putString(getResString(R.string.settings_key_auth), authMethod.name.lowercase(Locale.ROOT))

                if (!authCredentials.isEmpty()) {
                    editor.putString(getResString(R.string.settings_key_auth_credentials), authCredentials)
                    editor.putInt(getResString(R.string.settings_key_auth_iterations), authIterations)

                    editor.putString(getResString(R.string.settings_key_auth_salt), authSalt)
                }
            }

            if (keep_auth) {
                if (!legacyPassword.isEmpty()) editor.putString(getResString(R.string.settings_key_auth_password_hash), legacyPassword)
                if (!legacyPin.isEmpty()) editor.putString(getResString(R.string.settings_key_auth_pin_hash), legacyPin)
            }
            if (!editor.commit()) return

            PreferenceManager.setDefaultValues(context, R.xml.preferences, true)
        }
    }

    /** Lets a listener react to changed preference values. */
    open fun registerPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        settings.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Stops delivering preference changes to the listener. */
    open fun unregisterPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        settings.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** True if either single or double tap is set to reveal. */
    open fun getTapToReveal(): Boolean {
        return getTapSingle() == Constants.TapMode.REVEAL || getTapDouble() == Constants.TapMode.REVEAL
    }

    open fun getTapToRevealTimeout(): Int {
        return getInt(R.string.settings_key_tap_to_reveal_timeout, R.integer.settings_default_tap_to_reveal_timeout)
    }

    open fun getAuthMethod(): Constants.AuthMethod {
        val authString = getString(R.string.settings_key_auth, CredentialsPreference.DEFAULT_VALUE.name.lowercase(Locale.ENGLISH))
        return Constants.AuthMethod.valueOf(authString.uppercase(Locale.ENGLISH))
    }

    open fun setAuthMethod(authMethod: Constants.AuthMethod) {
        setString(R.string.settings_key_auth, authMethod.name.lowercase(Locale.ENGLISH))
    }

    open fun removeAuthPasswordHash() {
        remove(R.string.settings_key_auth_password_hash)
    }

    open fun removeAuthPINHash() {
        remove(R.string.settings_key_auth_pin_hash)
    }

    open fun getOldCredentials(method: Constants.AuthMethod): String {
        return if (method == Constants.AuthMethod.PASSWORD)
            getString(R.string.settings_key_auth_password_hash, "")
        else if (method == Constants.AuthMethod.PIN)
            getString(R.string.settings_key_auth_pin_hash, "")
        else
            ""
    }

    open fun getAuthCredentials(): String {
        return getString(R.string.settings_key_auth_credentials, "")
    }

    /** Uncommitted PBKDF2 authentication material and derived encryption key. */
    class PreparedCredentials(
        @JvmField val key: ByteArray,
        @JvmField val verifier: String,
        @JvmField val salt: String,
        @JvmField val iterations: Int
    ) {
        fun destroy() {
            key.fill(0)
        }
    }

    /** Derive without changing the currently usable credentials. */
    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    open fun prepareAuthCredentials(password: String?): PreparedCredentials {
        if (password == null || password.isEmpty() || password.length > InputPolicy.MAX_TEXT_CHARS) throw IllegalArgumentException("Invalid password length")
        val salt = EncryptionHelper.generateRandom(Constants.PBKDF2_SALT_LENGTH)
        val iterations = EncryptionHelper.benchmarkIterations(password, salt)
        val derived = EncryptionHelper.generatePBKDF2Credentials(password, salt, iterations)
        try {
            return PreparedCredentials(
                derived.key,
                Base64.encodeToString(derived.password, Base64.URL_SAFE),
                Base64.encodeToString(salt, Base64.URL_SAFE),
                iterations
            )
        } finally {
            derived.password.fill(0)
        }
    }

    /** One synchronous preference commit, paired with the staged vault transaction. */
    open fun commitVaultSettings(
        transaction: String?, type: Constants.EncryptionType, auth: Constants.AuthMethod?,
        credentials: PreparedCredentials?, initialize: Boolean
    ): Boolean {
        val editor = settings.edit()
        if (transaction != null) {
            editor.putString(DatabaseHelper.TRANSACTION_ID, transaction)
            // Existing hardware wrappers contain the previous vault key; require re-enrollment.
            HardwareUnlockStore.clearOnVaultChange(editor)
        }
        editor.putString(getResString(R.string.settings_key_encryption), type.name.lowercase(Locale.ROOT))
        if (auth != null) editor.putString(getResString(R.string.settings_key_auth), auth.name.lowercase(Locale.ROOT))
        if (credentials != null) {
            editor.putString(getResString(R.string.settings_key_auth_credentials), credentials.verifier)
                .putString(getResString(R.string.settings_key_auth_salt), credentials.salt)
                .putInt(getResString(R.string.settings_key_auth_iterations), credentials.iterations)
                .remove(getResString(R.string.settings_key_auth_password_hash))
                .remove(getResString(R.string.settings_key_auth_pin_hash))
        }
        if (initialize) editor.putBoolean(getResString(R.string.settings_key_security_backup_warning), true)
        editor.putBoolean(getResString(R.string.settings_key_enable_android_backup_service), false)
        return editor.commit()
    }

    /** Legacy auth-only migration. Do not use this to change an encrypted vault's password. */
    open fun setAuthCredentials(plainPassword: String?): ByteArray? {
        return setAuthCredentials(plainPassword, BooleanSupplier { true })
    }

    open fun setAuthCredentials(plainPassword: String?, permitted: BooleanSupplier): ByteArray? {
        var credentials: PreparedCredentials? = null
        try {
            val prepared = prepareAuthCredentials(plainPassword)
            credentials = prepared
            synchronized(DatabaseHelper.DatabaseFileLock) {
                // Legacy verifier upgrades are auth-only. Never change a password vault's
                // credentials without re-encrypting its authenticated ciphertext.
                if (!permitted.asBoolean || getEncryption() != Constants.EncryptionType.KEYSTORE) return null
                val key: SecretKey? = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true)
                val loaded = DatabaseHelper.readVault(context, key)
                if (loaded !is VaultReadResult.Loaded) return null
                loaded.close()
                if (!permitted.asBoolean) return null
                if (commitVaultSettings(null, getEncryption(), null, prepared, false)) return prepared.key.clone()
            }
        } catch (error: Exception) {
            /* Keep the old credentials on failure. */
        } finally {
            credentials?.destroy()
        }
        return null
    }

    open fun setSalt(bytes: ByteArray) {
        val encodedSalt = Base64.encodeToString(bytes, Base64.URL_SAFE)
        setString(R.string.settings_key_auth_salt, encodedSalt)
    }

    open fun getSalt(): ByteArray {
        val storedSalt = getString(R.string.settings_key_auth_salt, "")

        if (storedSalt.isEmpty()) throw IllegalStateException("Missing authentication salt")
        return Base64.decode(storedSalt, Base64.URL_SAFE)
    }

    open fun getIterations(): Int {
        return getIntValue(R.string.settings_key_auth_iterations, Constants.PBKDF2_MIN_AUTH_ITERATIONS)
    }

    open fun setIterations(value: Int) {
        setInt(R.string.settings_key_auth_iterations, value)
    }

    open fun getEncryption(): Constants.EncryptionType {
        val encType = getString(R.string.settings_key_encryption, R.string.settings_default_encryption)
        return Constants.EncryptionType.valueOf(encType.uppercase(Locale.ROOT))
    }

    open fun setEncryption(encryptionType: Constants.EncryptionType) {
        setEncryption(encryptionType.name.lowercase(Locale.ROOT))
    }

    open fun setEncryption(encryption: String) {
        setString(R.string.settings_key_encryption, encryption)
    }

    open fun getPanicResponse(): Set<String> {
        return getStringSet(R.string.settings_key_panic, Collections.emptySet())
    }

    open fun getRelockOnScreenOff(): Boolean {
        return getBoolean(R.string.settings_key_relock_screen_off, true)
    }

    open fun getRelockOnBackground(): Boolean {
        return getBoolean(R.string.settings_key_relock_background, false)
    }

    open fun getBlockAccessibility(): Boolean {
        return getBoolean(R.string.settings_key_block_accessibility, false)
    }

    open fun setLocale(locale: String) {
        setString(R.string.settings_key_lang, locale)
    }

    open fun getLocale(): Locale {
        val lang = getString(R.string.settings_key_lang, R.string.settings_default_lang)

        return if (lang == "system") {
            Tools.getSystemLocale()
        } else {
            val splitLang = lang.split("_")

            if (splitLang.size > 1) {
                Locale(splitLang[0], splitLang[1])
            } else {
                Locale(lang)
            }
        }
    }

    open fun getTheme(): Int {
        var theme = R.style.AppTheme_NoActionBar
        val themeMode = getString(R.string.settings_key_theme_mode, R.string.settings_default_theme_mode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && themeMode == "auto") {
            val blackTheme = getBoolean(R.string.settings_key_theme_black_auto, false)

            when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES ->
                    theme = if (blackTheme) R.style.AppTheme_Black_NoActionBar else R.style.AppTheme_Dark_NoActionBar
                else -> theme = R.style.AppTheme_NoActionBar
            }
        } else {
            val themeName = getString(R.string.settings_key_theme, R.string.settings_default_theme)

            theme = when (themeName) {
                "light" -> R.style.AppTheme_NoActionBar
                "dark" -> R.style.AppTheme_Dark_NoActionBar
                "black" -> R.style.AppTheme_Black_NoActionBar
                else -> theme
            }
        }

        return theme
    }

    open fun getLabelSize(): Int {
        return getInt(R.string.settings_key_label_size, R.integer.settings_default_label_size)
    }

    open fun getFirstTimeWarningShown(): Boolean {
        return getBoolean(R.string.settings_key_security_backup_warning, false)
    }

    open fun setFirstTimeWarningShown(value: Boolean) {
        setBoolean(R.string.settings_key_security_backup_warning, value)
    }

    open fun getSpecialFeatures(): Boolean {
        return getBoolean(R.string.settings_key_special_features, false)
    }

    open fun setSpecialFeatures(value: Boolean) {
        setBoolean(R.string.settings_key_special_features, value)
    }

    open fun getSortMode(): Constants.SortMode {
        val modeStr = getString(R.string.settings_key_sort_mode, Constants.SortMode.UNSORTED.toString())
        return Constants.SortMode.valueOf(modeStr)
    }

    open fun setSortMode(value: Constants.SortMode) {
        setString(R.string.settings_key_sort_mode, value.toString())
    }

    open fun getSearchValues(): List<Constants.SearchIncludes> {
        val stringValues = getStringSet(
            R.string.settings_key_search_includes,
            HashSet(context.resources.getStringArray(R.array.settings_defaults_search_includes).asList())
        )

        val values = ArrayList<Constants.SearchIncludes>()

        for (value in stringValues) {
            values.add(Constants.SearchIncludes.valueOf(value.uppercase(Locale.ENGLISH)))
        }

        return values
    }

    open fun getCardLayout(): Constants.CardLayouts {
        val stringValue = getString(R.string.settings_key_card_layout, R.string.settings_default_card_layout)
        return Constants.CardLayouts.valueOf(stringValue.uppercase(Locale.ENGLISH))
    }

    open fun getBackupAsk(): Boolean {
        return getBoolean(R.string.settings_key_backup_ask, true)
    }

    open fun getBackupPassword(): String {
        return getString(R.string.settings_key_backup_password, "")
    }

    /** Decrypts the stored backup password with the KeyStore key. */
    open fun getBackupPasswordEnc(): String {
        val base64Password = getString(R.string.settings_key_backup_password_enc, "")
        if (base64Password.isEmpty()) return ""
        try {
            val encPassword = Base64.decode(base64Password, Base64.URL_SAFE)
            val key: KeyPair = KeyStoreHelper.loadAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD) ?: return ""
            val plain = EncryptionHelper.decrypt(key.private, encPassword)
            try {
                return String(plain, StandardCharsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        } catch (error: Exception) {
            return ""
        }
    }

    open fun tryAcquireBroadcastBackupSlot(): Boolean {
        synchronized(DatabaseHelper.DatabaseFileLock) {
            val now = SystemClock.elapsedRealtime()
            val last = settings.getLong("last_broadcast_backup_elapsed", -60000)
            if (now >= last && now - last < 60000) return false
            return settings.edit().putLong("last_broadcast_backup_elapsed", now).commit()
        }
    }

    open fun disableAutomaticBackups() {
        settings.edit().putStringSet(getResString(R.string.settings_key_backup_broadcasts), Collections.emptySet())
            .putString(getResString(R.string.settings_key_auto_backup_password_enc), "off").commit()
    }

    open fun getBackupBroadcasts(): Set<String> {
        return getStringSet(R.string.settings_key_backup_broadcasts, Collections.emptySet())
    }

    open fun isEncryptedBackupBroadcastEnabled(): Boolean {
        return getBackupBroadcasts().contains("encrypted")
    }

    open fun getOpenPGPProvider(): String {
        return getString(R.string.settings_key_openpgp_provider, "")
    }

    open fun getOpenPGPEncryptionUserIDs(): String {
        return getString(R.string.settings_key_openpgp_key_encrypt, "")
    }

    open fun getOpenPGPSigningKey(): Long {
        return getLong(R.string.settings_key_openpgp_key_sign, 0)
    }

    open fun getOpenPGPVerify(): Boolean {
        return getBoolean(R.string.settings_key_openpgp_verify, false)
    }

    open fun getAllTagsToggle(): Boolean {
        return getBoolean(R.string.settings_key_all_tags_toggle, true)
    }

    open fun setAllTagsToggle(value: Boolean?) {
        setBoolean(R.string.settings_key_all_tags_toggle, value!!)
    }

    open fun getNoTagsToggle(): Boolean {
        return getBoolean(R.string.settings_key_no_tags_toggle, true)
    }

    open fun setNoTagsToggle(value: Boolean?) {
        setBoolean(R.string.settings_key_no_tags_toggle, value!!)
    }

    open fun getTagToggle(tag: String): Boolean {
        // Only unchecked tags are stored, so unknown tags default to checked.
        val toggledTags = getStringSet(R.string.settings_key_tags_toggles, Collections.emptySet())

        return !toggledTags.contains(tag)
    }

    open fun setTagToggle(tag: String, value: Boolean?) {
        val toggledTagsPref = settings.getStringSet(getResString(R.string.settings_key_tags_toggles), Collections.emptySet<String>())

        var toggledTags: MutableSet<String> = Collections.emptySet()

        if (toggledTagsPref != null) toggledTags = HashSet(toggledTagsPref)

        if (value!!) toggledTags.remove(tag) else toggledTags.add(tag)

        setStringSet(R.string.settings_key_tags_toggles, toggledTags)
    }

    /** Thumbnails are hidden by setting their size to 0. */
    open fun getThumbnailVisible(): Boolean {
        return getThumbnailSize() > 0
    }

    open fun getThumbnailSize(): Int {
        return try {
            val dimen = getString(R.string.settings_key_thumbnail_size, context.resources.getString(R.string.settings_default_thumbnail_size))
            DimensionConverter.stringToDimensionPixelSize(dimen, context.resources.displayMetrics)
        } catch (e: Exception) {
            e.printStackTrace()
            context.resources.getDimensionPixelSize(R.dimen.card_thumbnail_size)
        }
    }

    open fun getTokenSplitGroupSize(): Int {
        // Stored as a String because ListPreference entryValues cannot be an integer array
        return getString(R.string.settings_key_split_group_size, R.string.settings_default_split_group_size).toInt()
    }

    open fun getTagFunctionality(): Constants.TagFunctionality {
        val tagFunctionality = getString(R.string.settings_key_tag_functionality, R.string.settings_default_tag_functionality)
        // Locale.ROOT: uppercase(Turkish) turns the stored token "single" into "SİNGLE", which
        // valueOf() rejects, so the settings/entry screens crashed for Turkish users.
        return Constants.TagFunctionality.valueOf(tagFunctionality.uppercase(Locale.ROOT))
    }

    open fun getScreenshotsEnabled(): Boolean {
        return getBoolean(R.string.settings_key_enable_screenshot, false)
    }

    open fun getUsedTokensDialogShown(): Boolean {
        return getBoolean(R.string.settings_key_last_used_dialog_shown, false)
    }

    open fun setUsedTokensDialogShown(value: Boolean) {
        setBoolean(R.string.settings_key_last_used_dialog_shown, value)
    }

    open fun getAndroidBackupServiceEnabled(): Boolean {
        return false
    }

    open fun setAndroidBackupServiceEnabled(value: Boolean) {
        setBoolean(R.string.settings_key_enable_android_backup_service, false)
    }

    open fun getIsAppendingDateTimeToBackups(): Boolean {
        return getBoolean(R.string.settings_key_backup_append_date_time, true)
    }

    open fun getAuthInactivityDelay(): Int {
        // Must match the preference's own default: a hardcoded 0 here would silently disable
        // the timer whenever the XML defaults had not been written yet.
        val defaultValue = context.resources.getInteger(R.integer.settings_default_auth_inactivity_delay)
        return getIntValue(R.string.settings_key_auth_inactivity_delay, defaultValue)
    }

    open fun getAuthInactivity(): Boolean {
        return getBoolean(R.string.settings_key_auth_inactivity, false)
    }

    open fun isMinimizeAppOnCopyEnabled(): Boolean {
        return getBoolean(R.string.settings_key_minimize_on_copy, false)
    }

    /** The auto-backup mode for the encrypted backups. */
    private fun getAutoBackupEncryptedSetting(): Constants.AutoBackup {
        val stringValue = getString(R.string.settings_key_auto_backup_password_enc, R.string.settings_default_auto_backup_password_enc)
        return Constants.AutoBackup.valueOf(stringValue.uppercase(Locale.ENGLISH))
    }

    open fun getAutoBackupEncryptedPasswordsEnabled(): Boolean {
        return getAutoBackupEncryptedSetting() != Constants.AutoBackup.OFF
    }

    open fun getAutoBackupEncryptedFullEnabled(): Boolean {
        return getAutoBackupEncryptedSetting() == Constants.AutoBackup.ALL_EDITS
    }

    open fun isHighlightTokenOptionEnabled(): Boolean {
        return getBoolean(R.string.settings_key_label_highlight_token, true)
    }

    open fun isHideGlobalTimeoutEnabled(): Boolean {
        return getBoolean(R.string.settings_key_hide_global_timeout, false)
    }

    open fun isShowIndividualTimeoutsEnabled(): Boolean {
        return getBoolean(R.string.settings_key_show_individual_timeouts, false)
    }

    open fun isFocusSearchOnStartEnabled(): Boolean {
        return getBoolean(R.string.settings_key_focus_search_on_start, false)
    }

    open fun isHideIssuerEnabled(): Boolean {
        return getBoolean(R.string.settings_key_hide_issuer, false)
    }

    open fun getTapSingle(): Constants.TapMode {
        val singleTap = getString(R.string.settings_key_tap_single, R.string.settings_default_tap_single)
        return Constants.TapMode.valueOf(singleTap.uppercase(Locale.ENGLISH))
    }

    open fun getTapDouble(): Constants.TapMode {
        val doubleTap = getString(R.string.settings_key_tap_double, R.string.settings_default_tap_double)
        return Constants.TapMode.valueOf(doubleTap.uppercase(Locale.ENGLISH))
    }

    open fun setBackupLocation(uri: Uri) {
        setString(R.string.settings_key_backup_location, uri.toString())
    }

    open fun getBackupLocation(): Uri {
        return Uri.parse(getString(R.string.settings_key_backup_location, ""))
    }

    open fun isBackupLocationSet(): Boolean {
        return getString(R.string.settings_key_backup_location, "").isNotEmpty()
    }

    open fun getBlockAutofill(): Boolean {
        return getBoolean(R.string.settings_key_block_autofill, false)
    }

    open fun getAutoUnlockAfterAutofill(): Boolean {
        return getBoolean(R.string.settings_key_auto_unlock_after_autofill, false)
    }

    open fun setDefaultBackupType(type: Constants.BackupType) {
        setString(R.string.settings_key_backup_default_type, type.name.lowercase(Locale.ENGLISH))
    }

    open fun getDefaultBackupType(): Constants.BackupType {
        val defaultType = getString(R.string.settings_key_backup_default_type, Constants.BackupType.ENCRYPTED.name)
        return try {
            Constants.BackupType.valueOf(defaultType.uppercase(Locale.ENGLISH))
        } catch (e: IllegalArgumentException) {
            // Stored value is no longer a valid type (e.g. a removed plain-text setting)
            Constants.BackupType.ENCRYPTED
        }
    }

    open fun getLabelDisplay(): Constants.LabelDisplay {
        val labelDisplay = getString(R.string.settings_key_label_display, R.string.settings_default_label_display)
        return Constants.LabelDisplay.valueOf(labelDisplay.uppercase(Locale.ENGLISH))
    }

    open fun getShowPrevToken(): Boolean {
        return getBoolean(R.string.settings_key_show_prev_token, false)
    }

    companion object {
        private val newLangs = listOf("ar", "bg", "ca", "cs", "de", "el", "en", "es", "fa", "fr", "gl", "hi", "hu", "it", "ja", "nl", "pl", "pt_BR", "ru", "sl", "sv", "tr", "uk", "zh_CN", "zh_TW")
        private val oldLangs = listOf("ar_SA", "bg_BG", "ca_ES", "cs_CZ", "de_DE", "el_GR", "en_US", "es_ES", "fa_IR", "fr_FR", "gl_ES", "hi_IN", "hu_HU", "it_IT", "ja_JP", "nl_NL", "pl_PL", "pt_BR", "ru_RU", "sl_SI", "sv_SE", "tr_TR", "uk_UA", "zh_CN", "zh_TW")
    }
}
