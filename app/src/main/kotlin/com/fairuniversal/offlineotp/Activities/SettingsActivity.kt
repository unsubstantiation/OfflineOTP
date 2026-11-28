package com.fairuniversal.offlineotp.Activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.ViewGroup

import androidx.appcompat.widget.Toolbar


import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.AndroidPreferencesPort
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.BiometricKeyStore
import com.fairuniversal.offlineotp.Receivers.EncryptedBackupBroadcastReceiver
import com.fairuniversal.offlineotp.Tasks.ChangeEncryptionTask
import com.fairuniversal.offlineotp.Utilities.BackupHelper
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.Constants.AuthMethod
import com.fairuniversal.offlineotp.Utilities.Constants.EncryptionType
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper
import com.fairuniversal.offlineotp.Utilities.EncryptionHelper.EncryptionChangeResult
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.KeyStoreHelper
import com.fairuniversal.offlineotp.Utilities.LoginBackupPolicy
import com.fairuniversal.offlineotp.Utilities.NotificationHelper
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper
import com.fairuniversal.offlineotp.Utilities.OpenPgpKeyFilePolicy
import com.fairuniversal.offlineotp.Utilities.OpenPgpMode
import com.fairuniversal.offlineotp.Utilities.SessionCommandResult
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.Tools
import com.fairuniversal.offlineotp.Utilities.VaultCommitDecision
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.Utilities.VaultWriteResult
import com.fairuniversal.offlineotp.ui.host.SettingsOverviewComposeView
import com.fairuniversal.offlineotp.ui.host.CardDisplayComposeView
import com.fairuniversal.offlineotp.ui.host.AutoLockComposeView
import com.fairuniversal.offlineotp.ui.host.MultiChoiceSelectionComposeView
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.portable.PortableToolItem
import com.fairuniversal.offlineotp.Utilities.currentCardDisplaySelection
import com.fairuniversal.offlineotp.Utilities.applyCardDisplaySelection
import com.fairuniversal.offlineotp.Utilities.currentAutoLockSeconds
import com.fairuniversal.offlineotp.Utilities.applyAutoLockSettings
import com.fairuniversal.offlineotp.ui.host.OpenPgpProviderOptionsComposeView
import com.fairuniversal.offlineotp.Utilities.applyOpenPgpProviderOptions
import com.fairuniversal.offlineotp.Utilities.applySpecialFeatures
import com.fairuniversal.offlineotp.ui.host.ConfirmedSecretPreferenceComposeView
import com.fairuniversal.offlineotp.Utilities.applySeparateBackupPassword
import com.fairuniversal.offlineotp.Utilities.ConfirmedPasswordTransformationHelper
import com.fairuniversal.offlineotp.Utilities.hasLegacyBackupPassword
import com.fairuniversal.offlineotp.Utilities.applyVaultCopyBackupSource
import com.fairuniversal.offlineotp.Utilities.applyBackupPasswordSource
import com.fairuniversal.offlineotp.Utilities.PolicyChecklist
import com.fairuniversal.offlineotp.Utilities.applyPolicyChecklist
import com.fairuniversal.offlineotp.Utilities.currentAutoBackupMode
import com.fairuniversal.offlineotp.Utilities.applyAutoBackupMode
import com.fairuniversal.offlineotp.Utilities.applyOpenPgpMode
import com.fairuniversal.offlineotp.Utilities.VaultBehaviorSelection
import com.fairuniversal.offlineotp.Utilities.currentVaultBehavior
import com.fairuniversal.offlineotp.Utilities.applyVaultBehavior
import com.fairuniversal.offlineotp.Utilities.applyTagBehavior
import com.fairuniversal.offlineotp.Utilities.BackupFileSelection
import com.fairuniversal.offlineotp.Utilities.currentBackupFileSelection
import com.fairuniversal.offlineotp.Utilities.applyBackupFileSelection
import com.fairuniversal.offlineotp.Utilities.applyBackupRetention
import com.fairuniversal.offlineotp.Utilities.InputProtectionSelection
import com.fairuniversal.offlineotp.Utilities.currentInputProtection
import com.fairuniversal.offlineotp.Utilities.applyInputProtection
import com.fairuniversal.offlineotp.Utilities.applyRevealTimeout
import com.fairuniversal.offlineotp.ui.host.NumericPreferenceComposeView
import com.fairuniversal.offlineotp.Utilities.AppearancePreference
import com.fairuniversal.offlineotp.Utilities.currentAppearanceValue
import com.fairuniversal.offlineotp.Utilities.applyAppearanceValue
import com.fairuniversal.offlineotp.Utilities.ScreenPrivacySelection
import com.fairuniversal.offlineotp.Utilities.currentScreenPrivacy
import com.fairuniversal.offlineotp.Utilities.applyScreenPrivacy
import com.fairuniversal.offlineotp.Utilities.applyTapAction
import com.fairuniversal.offlineotp.ui.host.SingleChoiceSelectionComposeView
import com.fairuniversal.offlineotp.Utilities.setSearchValues
import com.fairuniversal.offlineotp.ui.host.BiometricEnableComposeView
import com.fairuniversal.offlineotp.ui.host.BusyProgressComposeView
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import com.fairuniversal.offlineotp.ui.host.PreferencesExportComposeView
import com.fairuniversal.offlineotp.ui.host.EntryDetailsComposeView
import com.fairuniversal.offlineotp.ui.host.PreferencesImportComposeView
import com.fairuniversal.offlineotp.ui.host.StorageLocationComposeView

import java.util.Arrays
import java.util.function.BooleanSupplier
import java.util.Locale

import javax.crypto.SecretKey

/** Settings screen that manages preferences, backup folder access, and background encryption changes. */
open class SettingsActivity : BackgroundTaskActivity<ChangeEncryptionTask.Result>(),
    SharedPreferences.OnSharedPreferenceChangeListener, EncryptionHelper.EncryptionChangeCallback {

    private var settingsOverview: SettingsOverviewComposeView? = null
    private var overviewPickerPending = false
    private var overviewGeneration = -1L

    private var prefs: SharedPreferences? = null

    private var encryptionKey: SecretKey? = null
    private var authenticationGeneration: Long = -1
    private var encryptionChanged = false
    private var clearingKeyStore = false

    internal var inProgress: ComposeDialogHost? = null
    internal var biometricPrompt: ComposeDialogHost? = null
    internal var settingsConfirmationPrompt: ComposeDialogHost? = null
    private var canGoBack = true

    private var biometricEnrollmentRevision = 0

    internal fun dismissBiometricPrompt() {
        biometricEnrollmentRevision++
        biometricPrompt?.dismiss()
        biometricPrompt = null
    }

    internal fun dismissSettingsPrompts() {
        dismissBiometricPrompt()
        settingsConfirmationPrompt?.dismiss()
        settingsConfirmationPrompt = null
        inProgress?.dismiss()
        inProgress = null
    }

    internal fun showSettingsPrompt(host: ComposeDialogHost) {
        settingsConfirmationPrompt?.dismiss()
        settingsConfirmationPrompt = host
        host.show()
    }

    internal fun showSettingsNotice(titleId: Int, messageId: Int) {
        var host: EntryDetailsComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = EntryDetailsComposeView(
            activity = this,
            title = getString(titleId),
            body = getString(messageId),
            confirmLabel = getString(android.R.string.ok),
            onDismiss = closeHost
        )
        showSettingsPrompt(host)
    }

    private val feedbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var feedbackExpiry: Runnable? = null

    private fun showSettingsFeedback(message: Int) = showSettingsFeedback(getString(message))

    private fun showSettingsFeedback(message: String) {
        if (!overviewSessionReady()) return
        val page = settingsOverview ?: return
        feedbackExpiry?.let { feedbackHandler.removeCallbacks(it) }
        val revision = page.showNotice(message)
        val expiry = Runnable { page.dismissNotice(revision) }
        feedbackExpiry = expiry
        feedbackHandler.postDelayed(expiry, 5000L)
    }

    private fun clearSettingsFeedback() {
        feedbackExpiry?.let { feedbackHandler.removeCallbacks(it) }
        feedbackExpiry = null
    }

    // Only non-secret result metadata crosses the import-triggered recreation.
    private var importFeedback: PreferencesImportFeedback? = null
    private data class PreferencesImportFeedback(val generation: Long, val applied: Int,
        val skipped: Int, val includesSecrets: Boolean)

    private fun saveImportFeedback(state: Bundle) {
        val feedback = importFeedback ?: return
        if (!overviewSessionReady() || !VaultSession.isCurrent(feedback.generation)) return
        state.putLong("settings_import_feedback_generation", feedback.generation)
        state.putInt("settings_import_feedback_applied", feedback.applied)
        state.putInt("settings_import_feedback_skipped", feedback.skipped)
        state.putBoolean("settings_import_feedback_secrets", feedback.includesSecrets)
    }

    private fun restoreImportFeedback(state: Bundle?) {
        if (state == null || !overviewSessionReady()) return
        val generation = state.getLong("settings_import_feedback_generation", -1L)
        val applied = state.getInt("settings_import_feedback_applied", -1)
        val skipped = state.getInt("settings_import_feedback_skipped", -1)
        if (!VaultSession.isCurrent(generation) || applied < 0 || skipped < 0) return
        val success = if (state.getBoolean("settings_import_feedback_secrets", false))
            getString(R.string.preferences_import_success_with_secrets)
        else getString(R.string.preferences_import_success, applied)
        val detail = if (skipped > 0) "\n" + getString(R.string.preferences_import_skipped, skipped) else ""
        showSettingsFeedback(success + detail)
        // Do not republish this handoff into subsequent saved state.
        importFeedback = null
    }

    private companion object {
        const val STATE_AUTH_GENERATION = "settings_auth_generation"
        const val STATE_SETTINGS_PICKER = "settings_picker_pending"
        const val STATE_SIGNING_PENDING = "settings_signing_pending"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.settings_activity_title)
        setContentView(R.layout.activity_container)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        setSupportActionBar(toolbar)
        toolbar.visibility = View.GONE // Shared Compose owns title/navigation.

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.content_settings)
        val content = stub.inflate() as ViewGroup
        overviewPickerPending = savedInstanceState?.getBoolean(STATE_SETTINGS_PICKER, false) == true &&
            savedInstanceState.getBoolean(STATE_SIGNING_PENDING, false) != true

        encryptionKey = VaultSession.getKey()
        if (encryptionKey == null) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            encryptionChanged = savedInstanceState.getBoolean(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, false)
            val savedAuthGeneration = savedInstanceState.getLong(STATE_AUTH_GENERATION, -1L)
            if (savedAuthGeneration >= 0L && VaultSession.isCurrent(savedAuthGeneration)) {
                authenticationGeneration = savedAuthGeneration
            }
        }

        // Compose is the only settings UI. Platform backup stays disabled without inflating
        // an invisible native preference tree merely to initialize a disabled checkbox.
        if (settings!!.getAndroidBackupServiceEnabled()) settings!!.setAndroidBackupServiceEnabled(false)
        val sharedPreferences = settings!!.preferenceStore()
        prefs = sharedPreferences
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        overviewGeneration = VaultSession.generation()
        val overview = SettingsOverviewComposeView(this, ::dispatchSettingsOverviewAction)
        overview.onBack = { onSupportNavigateUp() }
        settingsOverview = overview
        content.addView(overview, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        publishSettingsOverview()
        restoreImportFeedback(savedInstanceState)
        // Consume only a fresh same-session handoff. Recreation must not launch another picker.
        if (savedInstanceState == null) launchRequestedVaultTool()
    }

    private fun overviewSessionReady(): Boolean = VaultSession.isCurrent(overviewGeneration) &&
        encryptionKey != null && VaultSession.getKey() === encryptionKey && !isFinishing()

    private fun publishSettingsOverview() {
        val overview = settingsOverview ?: return
        if (!overviewSessionReady()) return
        val s = settings ?: return
        val display = s.currentCardDisplaySelection(this)
        val autoLock = if (s.getAuthMethod() != AuthMethod.NONE) listOf(PortableToolItem(
            key = "autoLock", label = getString(R.string.menu_main_auto_lock),
            value = com.fairuniversal.offlineotp.ui.vault.AutoLockOptions.label(s.currentAutoLockSeconds())
        )) else emptyList()
        overview.render(
            title = getString(R.string.settings_activity_title),
            description = getString(R.string.settings_shared_overview_description),
            groups = listOf(
                PortableToolGroup(getString(R.string.settings_category_title_security), autoLock + listOf(
                    PortableToolItem("authentication", getString(R.string.settings_title_auth)),
                    PortableToolItem("encryption", getString(R.string.settings_title_encryption)),
                    PortableToolItem("biometricUnlock", getString(R.string.settings_title_biometric),
                        enabled = s.getEncryption() == EncryptionType.PASSWORD && s.getAuthMethod() == AuthMethod.PASSWORD),
                    PortableToolItem("panicActions", getString(R.string.settings_title_panic)),
                    PortableToolItem("screenPrivacy", getString(R.string.settings_title_screen_privacy)),
                    PortableToolItem("inputProtection", getString(R.string.settings_title_input_protection)),
                    PortableToolItem("revealTimeout", getString(R.string.settings_title_tap_to_reveal_timeout),
                        value = "${s.getTapToRevealTimeout()} s"))),
                PortableToolGroup(getString(R.string.settings_category_title_ui), listOf(
                    PortableToolItem("language", getString(R.string.settings_title_lang)),
                    PortableToolItem("themeMode", getString(R.string.settings_title_theme_mode),
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
                    PortableToolItem("blackAuto", getString(R.string.settings_title_theme_black_auto),
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            s.currentAppearanceValue(this, AppearancePreference.THEME_MODE) == "auto"),
                    PortableToolItem("theme", getString(R.string.settings_title_theme),
                        enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                            s.currentAppearanceValue(this, AppearancePreference.THEME_MODE) != "auto"),
                    PortableToolItem("display", getString(R.string.settings_title_card_layout), value = display.layout.name),
                    PortableToolItem("vaultBehavior", getString(R.string.settings_title_vault_behavior)),
                    PortableToolItem("tagBehavior", getString(R.string.settings_title_tag_functionality)),
                    PortableToolItem("singleTap", getString(R.string.settings_title_tap_single)),
                    PortableToolItem("doubleTap", getString(R.string.settings_title_tap_double)),
                    PortableToolItem("search", getString(R.string.settings_title_search_includes),
                        value = s.getSearchValues().joinToString { it.name.lowercase(Locale.ROOT) })
                )),
                PortableToolGroup(getString(R.string.settings_category_title_backup), listOf(
                    PortableToolItem("backupBroadcasts", getString(R.string.settings_title_backup_broadcasts)),
                    PortableToolItem("separateBackupPassword", getString(R.string.settings_title_backup_password)),
                    PortableToolItem("backupPasswordSource", getString(R.string.settings_title_backup_password_source)),
                    PortableToolItem("autoBackupMode", getString(R.string.settings_title_auto_backup_password_enc)),
                    PortableToolItem("backupFiles", getString(R.string.settings_title_backup_files)),
                    PortableToolItem("backupRetention", getString(R.string.settings_title_auto_backup_keep)),
                    PortableToolItem("backupFolder", getString(R.string.settings_title_backup_location)),
                    PortableToolItem("storage", getString(R.string.settings_title_storage_location)),
                    PortableToolItem("exportPreferences", getString(R.string.settings_title_preferences_export)),
                    PortableToolItem("importPreferences", getString(R.string.settings_title_preferences_import))
                )),
                PortableToolGroup(getString(R.string.settings_title_openpgp_mode), listOf(
                    PortableToolItem("pgpMode", getString(R.string.settings_title_openpgp_mode)),
                    PortableToolItem("pgpRecipients", getString(R.string.settings_title_openpgp_recipient_files),
                        detail = getString(R.string.settings_desc_openpgp_recipient_files),
                        enabled = s.getOpenPGPMode() == OpenPgpMode.KEY_FILE),
                    PortableToolItem("pgpSecret", getString(R.string.settings_title_openpgp_secret_file),
                        detail = getString(R.string.settings_desc_openpgp_secret_file),
                        enabled = s.getOpenPGPMode() == OpenPgpMode.KEY_FILE),
                    PortableToolItem("pgpProviderOptions", getString(R.string.settings_title_pgp_provider_options),
                        enabled = s.getOpenPGPMode() == OpenPgpMode.PROVIDER && s.getOpenPGPProvider().isNotBlank()),
                    PortableToolItem("pgpSigningKey", getString(R.string.settings_title_openpgp_key_sign),
                        enabled = s.getOpenPGPMode() == OpenPgpMode.PROVIDER && s.getOpenPGPProvider().isNotBlank()),
                    PortableToolItem("pgpProvider", getString(R.string.settings_title_openpgp_provider),
                        detail = getString(R.string.settings_pgp_provider_setup_detail),
                        enabled = s.getOpenPGPMode() == OpenPgpMode.PROVIDER)
                )),
                PortableToolGroup(getString(R.string.about_title_special_features), listOf(
                    PortableToolItem("specialFeatures", getString(R.string.settings_title_special_features)),
                    PortableToolItem("resetKeyStore", getString(R.string.settings_title_clear_keystore),
                        enabled = s.getSpecialFeatures())
                ))
            ), busy = !canGoBack || overviewPickerPending
        )
    }

    private fun dispatchSettingsOverviewAction(key: String) {
        if (!overviewSessionReady() || !canGoBack || overviewPickerPending || settingsConfirmationPrompt != null || biometricPrompt != null) return
        when (key) {
            "language" -> showSharedAppearance(AppearancePreference.LANGUAGE)
            "themeMode" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) showSharedAppearance(AppearancePreference.THEME_MODE)
            "blackAuto" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                settings!!.currentAppearanceValue(this, AppearancePreference.THEME_MODE) == "auto")
                    showSharedAppearance(AppearancePreference.BLACK_AUTO)
            "theme" -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                settings!!.currentAppearanceValue(this, AppearancePreference.THEME_MODE) != "auto")
                    showSharedAppearance(AppearancePreference.THEME)
            "screenPrivacy" -> showSharedScreenPrivacy()
            "inputProtection" -> showSharedInputProtection()
            "revealTimeout" -> showSharedRevealTimeout()
            "display" -> showSharedCardDisplay()
            "search" -> showSharedSearchFields()
            "singleTap" -> showSharedTapAction(false)
            "doubleTap" -> showSharedTapAction(true)
            "autoLock" -> showSharedAutoLock()
            "vaultBehavior" -> showSharedVaultBehavior()
            "tagBehavior" -> showSharedTagBehavior()
            "pgpMode" -> showSharedOpenPgpMode()
            "pgpProviderOptions" -> showSharedOpenPgpProviderOptions()
            "pgpRecipients" -> if (settings!!.getOpenPGPMode() == OpenPgpMode.KEY_FILE) requestOpenPgpKeyFiles(true)
            "pgpSecret" -> if (settings!!.getOpenPGPMode() == OpenPgpMode.KEY_FILE) requestOpenPgpKeyFiles(false)
            "panicActions" -> showSharedPolicyChecklist(PolicyChecklist.PANIC)
            "backupBroadcasts" -> showSharedPolicyChecklist(PolicyChecklist.BACKUP_BROADCASTS)
            "authentication" -> showSharedAuthentication()
            "encryption" -> showSharedEncryption()
            "biometricUnlock" -> showSharedBiometricSelection()
            "specialFeatures" -> showSharedSpecialFeatures()
            "resetKeyStore" -> showSharedKeyStoreReset()
            "separateBackupPassword" -> showSharedSeparateBackupPassword()
            "backupPasswordSource" -> showSharedBackupPasswordSource()
            "autoBackupMode" -> showSharedAutoBackupMode()
            "backupFiles" -> showSharedBackupFiles()
            "backupRetention" -> showSharedBackupRetention()
            "backupFolder" -> requestBackupAccess()
            "storage" -> showStorageLocation()
            "exportPreferences" -> confirmPreferencesExport()
            "importPreferences" -> requestPreferencesImport()
            "pgpSigningKey" -> selectSharedSigningKey()
            "pgpProvider" -> showSharedPgpProvider()

        }
    }

    private fun showSharedCardDisplay(failed: Boolean = false) {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        var host: CardDisplayComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = CardDisplayComposeView(this, settings!!.currentCardDisplaySelection(this), failed,
            onSave = { selection ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyCardDisplaySelection(this, selection)) showSharedCardDisplay(failed = true)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
    }

    private fun showSharedAutoLock(failed: Boolean = false) {
        if (!overviewSessionReady() || !canGoBack || settings!!.getAuthMethod() == AuthMethod.NONE) return
        val generation = overviewGeneration
        var host: AutoLockComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = AutoLockComposeView(this, settings!!.currentAutoLockSeconds(), settings!!.getRelockOnBackground(), failed,
            onSave = { seconds, background ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyAutoLockSettings(this, seconds, background)) showSharedAutoLock(failed = true)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
    }

    private var signingConnection: org.openintents.openpgp.util.OpenPgpServiceConnection? = null
    private var signingProvider: String? = null
    private var signingGeneration = -1L
    private val signingRequestCode = 6420

    private fun closeSigningSelection() {
        signingConnection?.unbindFromService()
        signingConnection = null
        signingProvider = null
        signingGeneration = -1L
        overviewPickerPending = false
    }

    private fun signingSelectionCurrent(): Boolean = signingConnection != null && overviewSessionReady() && canGoBack &&
        VaultSession.isCurrent(signingGeneration) && settings!!.getOpenPGPMode() == OpenPgpMode.PROVIDER &&
        settings!!.getOpenPGPProvider() == signingProvider

    private fun selectSharedSigningKey() {
        if (!overviewSessionReady() || !canGoBack || signingConnection != null ||
            settings!!.getOpenPGPMode() != OpenPgpMode.PROVIDER || settings!!.getOpenPGPProvider().isBlank()) return
        signingProvider = settings!!.getOpenPGPProvider()
        signingGeneration = overviewGeneration
        overviewPickerPending = true
        lateinit var connection: org.openintents.openpgp.util.OpenPgpServiceConnection
        connection = org.openintents.openpgp.util.OpenPgpServiceConnection(this, signingProvider,
            object : org.openintents.openpgp.util.OpenPgpServiceConnection.OnBound {
                override fun onBound(service: org.openintents.openpgp.IOpenPgpService2) {
                    if (signingConnection === connection) requestSigningKey(Intent())
                }
                override fun onError(error: Exception) {
                    if (signingConnection !== connection) return
                    closeSigningSelection()
                    publishSettingsOverview()
                    showSettingsFeedback(R.string.settings_save_failed)
                }
            })
        signingConnection = connection
        publishSettingsOverview()
        try { connection.bindToService() } catch (_: Exception) { closeSigningSelection(); publishSettingsOverview() }
    }

    private fun requestSigningKey(data: Intent) {
        val connection = signingConnection ?: return
        if (!signingSelectionCurrent()) { closeSigningSelection(); publishSettingsOverview(); return }
        data.setAction("org.openintents.openpgp.action.GET_SIGN_KEY_ID")
        val api = org.openintents.openpgp.util.OpenPgpApi(this, connection.getService())
        try { api.executeApiAsync(data, null, null, object : org.openintents.openpgp.util.OpenPgpApi.IOpenPgpCallback {
            override fun onReturn(result: Intent) {
                if (signingConnection !== connection) return
                if (!signingSelectionCurrent()) { closeSigningSelection(); publishSettingsOverview(); return }
                when (result.getIntExtra("result_code", 0)) {
                    1 -> {
                        val key = result.getLongExtra("sign_key_id", 0L)
                        val saved = settings!!.preferenceStore().edit()
                            .putLong(getString(R.string.settings_key_openpgp_key_sign), key).commit()
                        closeSigningSelection()
                        if (!saved) showSettingsFeedback(R.string.settings_save_failed)
                        publishSettingsOverview()
                    }
                    2 -> {
                        val pending = result.getParcelableExtra<android.app.PendingIntent>("intent")
                        try {
                            if (pending == null) throw IllegalStateException("Missing provider intent")
                            startIntentSenderForResult(pending.intentSender, signingRequestCode, null, 0, 0, 0)
                        } catch (_: Exception) { closeSigningSelection(); publishSettingsOverview() }
                    }
                    else -> {
                        closeSigningSelection(); publishSettingsOverview()
                        showSettingsFeedback(R.string.settings_save_failed)
                    }
                }
            }
        }) } catch (_: Exception) {
            if (signingConnection === connection) { closeSigningSelection(); publishSettingsOverview() }
        }
    }

    private fun installedPgpProviders(): List<Pair<String, String>> {
        val pm = getPackageManager()
        return pm.queryIntentServices(Intent("org.openintents.openpgp.IOpenPgpService2"), 0)
            .mapNotNull { result -> result.serviceInfo?.let {
                if (it.packageName == "org.thialfihar.android.apg") null
                else it.packageName to it.loadLabel(pm).toString()
            } }.distinctBy { it.first }.sortedBy { it.second }
    }

    private fun showSharedPgpProvider() {
        if (!overviewSessionReady() || !canGoBack || settings!!.getOpenPGPMode() != OpenPgpMode.PROVIDER) return
        val generation = overviewGeneration
        val choices = listOf("" to getString(R.string.settings_choice_disabled)) + installedPgpProviders()
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this, title = getString(R.string.settings_title_openpgp_provider),
            message = getString(R.string.settings_pgp_provider_setup_detail), items = choices.map { it.second },
            initialSelected = choices.indexOfFirst { it.first == settings!!.getOpenPGPProvider() },
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                val provider = choices[index].first
                if (owned && overviewSessionReady() && canGoBack && VaultSession.isCurrent(generation) &&
                    settings!!.getOpenPGPMode() == OpenPgpMode.PROVIDER &&
                    (provider.isEmpty() || installedPgpProviders().any { it.first == provider })) {
                    val saved = settings!!.preferenceStore().edit()
                        .putString(getString(R.string.settings_key_openpgp_provider), provider).commit()
                    if (!saved) showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private var credentialsTask: com.fairuniversal.offlineotp.Tasks.ChangeCredentialsTask? = null

    private fun showSharedAuthentication() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val encryption = settings!!.getEncryption()
        val modes = listOf(AuthMethod.NONE, AuthMethod.PASSWORD, AuthMethod.DEVICE)
        val labels = resources.getStringArray(R.array.settings_entries_auth).toList()
        if (labels.size != modes.size) return
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this, title = getString(R.string.settings_title_auth),
            items = labels, initialSelected = modes.indexOf(settings!!.getAuthMethod()),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && overviewSessionReady() && canGoBack && VaultSession.isCurrent(generation) &&
                    settings!!.getEncryption() == encryption) {
                    val method = modes[index]
                    if (encryption == EncryptionType.PASSWORD && method != AuthMethod.PASSWORD)
                        showSettingsNotice(R.string.settings_dialog_title_error, R.string.settings_dialog_msg_auth_invalid_with_encryption)
                    else if (method == AuthMethod.PASSWORD) showSharedAuthenticationPassword()
                    else if (method == AuthMethod.DEVICE &&
                        (getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardSecure() != true)
                        showSettingsFeedback(R.string.settings_toast_auth_device_not_secure)
                    else startSharedCredentialsChange(method, "")
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun startSharedCredentialsChange(method: AuthMethod, password: String) {
        if (!overviewSessionReady() || !canGoBack || credentialsTask != null) return
        val encryption = settings!!.getEncryption()
        if (encryption == EncryptionType.PASSWORD && method != AuthMethod.PASSWORD) return
        if (method == AuthMethod.PASSWORD && (password.length < Constants.AUTH_MIN_PASSWORD_LENGTH ||
            password.length > com.fairuniversal.offlineotp.Utilities.InputPolicy.MAX_TEXT_CHARS)) return
        val generation = overviewGeneration
        val task = com.fairuniversal.offlineotp.Tasks.ChangeCredentialsTask(this, encryption, encryptionKey, method, password)
        credentialsTask = task
        setupUiForTaskState(true)
        task.setOutcomeCallback { outcome ->
            if (credentialsTask !== task) { task.disposeOutcome(outcome); return@setOutcomeCallback }
            credentialsTask = null
            setupUiForTaskState(false)
            if (overviewSessionReady() && VaultSession.isCurrent(generation)) {
                val result = (outcome as? com.fairuniversal.offlineotp.Tasks.TaskOutcome.Value)?.value
                if (result?.success == true) {
                    val key = result.newEncryptionKey
                    val type = result.encryptionType
                    if (key != null && type != null) onSuccessfulEncryptionChange(type, key)
                    publishSettingsOverview()
                } else showSettingsFeedback(R.string.settings_toast_encryption_change_failed)
            }
            task.disposeOutcome(outcome)
        }
        task.execute()
    }

    private fun showSharedEncryption() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val original = settings!!.getEncryption()
        val values = resources.getStringArray(R.array.settings_values_encryption).map {
            EncryptionType.valueOf(it.uppercase(Locale.ROOT)) }
        val labels = resources.getStringArray(R.array.settings_entries_encryption).toList()
        if (labels.size != values.size) return
        val portable = DatabaseHelper.isPortableVault(this)
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this, title = getString(R.string.settings_title_encryption),
            items = labels, initialSelected = values.indexOf(original),
            enabledIndices = values.indices.filter { !portable || values[it] == EncryptionType.PASSWORD }.toSet(),
            message = if (portable) "Portable vaults require password recovery. Use biometrics or a security key for local quick unlock; the vault cannot be changed to device-only encryption." else "",
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && overviewSessionReady() && canGoBack && VaultSession.isCurrent(generation) &&
                    settings!!.getEncryption() == original && values[index] != original) {
                    val selected = values[index]
                    if (selected == EncryptionType.PASSWORD && settings!!.getAuthMethod() != AuthMethod.PASSWORD)
                        showSettingsNotice(R.string.settings_dialog_title_error, R.string.settings_dialog_msg_encryption_invalid_with_auth)
                    else if (selected == EncryptionType.PASSWORD && settings!!.getAuthCredentials().isEmpty())
                        showSettingsNotice(R.string.settings_dialog_title_error, R.string.settings_dialog_msg_encryption_invalid_without_credentials)
                    else confirmSharedEncryptionChange(selected)
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun confirmSharedEncryptionChange(selected: EncryptionType) {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val original = settings!!.getEncryption()
        var host: DeleteConfirmationComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = DeleteConfirmationComposeView(this, getString(R.string.settings_dialog_title_warning),
            getString(R.string.settings_dialog_msg_encryption_change), getString(android.R.string.ok),
            getString(android.R.string.cancel), onConfirm = {
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && overviewSessionReady() && canGoBack && VaultSession.isCurrent(generation) &&
                    settings!!.getEncryption() == original && selected != original) {
                    if (selected == EncryptionType.PASSWORD) {
                        if (settings!!.getAuthMethod() == AuthMethod.PASSWORD && settings!!.getAuthCredentials().isNotEmpty())
                            tryEncryptionChangeWithAuth(selected)
                    } else if (selected == EncryptionType.KEYSTORE) startEncryptionChangeTask(selected, null)
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedBiometricSelection() {
        if (!overviewSessionReady() || !canGoBack || settings!!.getEncryption() != EncryptionType.PASSWORD ||
            settings!!.getAuthMethod() != AuthMethod.PASSWORD || biometricPrompt != null) return
        val generation = overviewGeneration
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_biometric), message = getString(R.string.settings_desc_biometric),
            items = listOf(getString(R.string.settings_choice_disabled), getString(R.string.settings_choice_enabled)),
            initialSelected = if (BiometricKeyStore.isEnabled(this) && BiometricKeyStore.hasStoredKey(this)) 1 else 0,
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation) &&
                    settings!!.getEncryption() == EncryptionType.PASSWORD && settings!!.getAuthMethod() == AuthMethod.PASSWORD) {
                    if (index == 0) {
                        dismissBiometricPrompt()
                        try { BiometricKeyStore.clear(this) } catch (_: Exception) { }
                        publishSettingsOverview()
                    } else {
                        val availability = BiometricKeyStore.availability(this)
                        if (availability.isAvailable) promptBiometricEnrollment(this)
                        else showSettingsFeedback(if (availability == BiometricKeyStore.Availability.NOT_ENROLLED)
                            R.string.settings_toast_biometric_not_enrolled else R.string.settings_toast_biometric_unavailable)
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun promptBiometricEnrollment(host: SettingsActivity) {
        val ctx = host
        if (!host.overviewSessionReady() || !host.canGoBack) return
        host.dismissBiometricPrompt()
        val generation = host.overviewGeneration
        val revision = host.biometricEnrollmentRevision
        var pending = false
        lateinit var view: BiometricEnableComposeView
        fun owned(): Boolean = host.biometricPrompt === view && host.biometricEnrollmentRevision == revision &&
            host.overviewSessionReady() && VaultSession.isCurrent(generation) && host.canGoBack &&
            host.settings?.getEncryption() == EncryptionType.PASSWORD && host.settings?.getAuthMethod() == AuthMethod.PASSWORD
        val wrongPassword = getString(R.string.settings_toast_biometric_wrong_password)
        val saveFailed = getString(R.string.settings_toast_biometric_save_failed)

        // One shared Compose form for this password prompt on every client. Android keeps the
        // Dialog window, the PBKDF2 verifier and the biometric/keychain write; the form keeps
        // no password after confirmation and renders the same error handling as iOS/desktop.
        view = BiometricEnableComposeView(
            activity = ctx,
            title = getString(R.string.settings_title_biometric),
            detail = getString(R.string.settings_dialog_biometric_enable),
            passwordLabel = getString(R.string.settings_hint_password),
            confirmLabel = getString(android.R.string.ok),
            cancelLabel = getString(android.R.string.cancel),
            onConfirm = { typed ->
                if (!owned() || pending) {
                    saveFailed
                } else if (typed.isEmpty()) {
                    wrongPassword
                } else {
                    val verified = try {
                        verifyVaultPassword(typed)
                    } catch (_: Exception) {
                        false
                    }
                    if (!verified) {
                        wrongPassword
                    } else {
                        // Use the live vault key — the password matched, and the GCM tag is
                        // the real gate. Keep the Android keychain adapter in the host.
                        val vaultKey = VaultSession.getKey()?.encoded?.clone()
                        if (vaultKey == null || vaultKey.isEmpty()) {
                            saveFailed
                        } else {
                            val fragmentActivity = host as? androidx.fragment.app.FragmentActivity
                            if (fragmentActivity == null) {
                                try { vaultKey.fill(0) } catch (_: Exception) { }
                                saveFailed
                            } else {
                                try {
                                    pending = true
                                    view.updateOperation(true)
                                    BiometricKeyStore.save(fragmentActivity, vaultKey, permitted = { owned() }) { success, error ->
                                        // Wipe the copy after the store has copied it.
                                        try { vaultKey.fill(0) } catch (_: Exception) { }
                                        if (!owned()) return@save
                                        pending = false
                                        if (success) {
                                            host.dismissBiometricPrompt()
                                            host.publishSettingsOverview()
                                            host.showSettingsFeedback(R.string.settings_toast_biometric_turned_on)
                                        } else {
                                            val msg = when (error) {
                                                BiometricKeyStore.BiometricError.NOT_ENROLLED ->
                                                    getString(R.string.settings_toast_biometric_not_enrolled)
                                                BiometricKeyStore.BiometricError.UNAVAILABLE ->
                                                    getString(R.string.settings_toast_biometric_unavailable)
                                                BiometricKeyStore.BiometricError.LOCKED_OUT ->
                                                    getString(R.string.settings_toast_biometric_locked_out)
                                                BiometricKeyStore.BiometricError.CANCELLED ->
                                                    getString(R.string.settings_toast_biometric_cancelled)
                                                else -> saveFailed
                                            }
                                            view.updateOperation(false, msg)
                                        }
                                    }
                                    null
                                } catch (_: Exception) {
                                    pending = false
                                    view.updateOperation(false)
                                    try { vaultKey.fill(0) } catch (_: Exception) { }
                                    saveFailed
                                }
                            }
                        }
                    }
                }
            },
            onDismiss = { if (host.biometricPrompt === view && host.biometricEnrollmentRevision == revision) host.dismissBiometricPrompt() }
        )
        host.biometricPrompt = view
        view.show()
    }

    private fun verifyVaultPassword(plainPassword: String): Boolean {
        val s = settings ?: return false
        return try {
            val storedVerifier = s.getAuthCredentials()
            if (storedVerifier.isEmpty()) return false
            val derived = s.deriveVaultCredentials(plainPassword)
            try {
                val storedBytes = android.util.Base64.decode(storedVerifier, android.util.Base64.URL_SAFE)
                java.security.MessageDigest.isEqual(storedBytes, derived.password)
            } finally {
                try { derived.password.fill(0) } catch (_: Exception) { }
                try { derived.key.fill(0) } catch (_: Exception) { }
            }
        } catch (_: Exception) { false }
    }

    private fun showSharedSpecialFeatures() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.about_title_special_features), message = getString(R.string.about_dialog_special_features),
            items = listOf(getString(R.string.settings_choice_disabled), getString(R.string.settings_choice_enabled)),
            initialSelected = if (settings!!.getSpecialFeatures()) 1 else 0,
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applySpecialFeatures(this, index == 1))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedKeyStoreReset() {
        if (!overviewSessionReady() || !canGoBack || settings!!.getSpecialFeatures() != true || settingsConfirmationPrompt != null) return
        val generation = overviewGeneration
        val encryption = settings!!.getEncryption()
        val messageId = when (encryption) {
            EncryptionType.PASSWORD -> R.string.settings_dialog_msg_clear_keystore_password
            EncryptionType.KEYSTORE -> R.string.settings_dialog_msg_clear_keystore_keystore
        }
        var host: DeleteConfirmationComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = DeleteConfirmationComposeView(this,
            title = getString(R.string.settings_dialog_title_clear_keystore), message = getString(messageId),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = {
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation) &&
                    settings!!.getSpecialFeatures() && settings!!.getEncryption() == encryption) {
                    clearKeyStore()
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedAuthenticationPassword() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val input = android.view.LayoutInflater.from(this).inflate(R.layout.component_password, null)
        val layout = requireNotNull(input.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.passwordLayout))
        val password = requireNotNull(input.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordEdit))
        val confirmation = requireNotNull(input.findViewById<android.widget.EditText>(R.id.passwordConfirm))
        password.isSaveEnabled = false; confirmation.isSaveEnabled = false
        password.setText(""); confirmation.setText("")
        password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        confirmation.inputType = password.inputType
        if (settings!!.getBlockAccessibility()) {
            layout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            confirmation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }
        if (settings!!.getBlockAutofill()) {
            layout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            confirmation.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
        }
        ConfirmedPasswordTransformationHelper.setup(layout, password, confirmation)
        input.findViewById<View>(R.id.btnSave)?.visibility = View.GONE
        input.findViewById<View>(R.id.btnCancel)?.visibility = View.GONE
        var host: ConfirmedSecretPreferenceComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            password.setText(""); confirmation.setText("")
            h?.releaseInput(); h?.dismiss()
            Unit
        }
        host = ConfirmedSecretPreferenceComposeView(this, input,
            title = getString(R.string.settings_title_auth),
            description = getString(R.string.settings_hint_password),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onSave = {
                val owned = settingsConfirmationPrompt === host
                val value = password.text?.toString().orEmpty()
                val matches = value == confirmation.text?.toString().orEmpty()
                closeHost()
                if (owned && matches && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    startSharedCredentialsChange(AuthMethod.PASSWORD, value)
                }
            }, onDismiss = closeHost)
        val watcher = object : android.text.TextWatcher {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = password.text?.toString().orEmpty()
                host?.updateValidity(text.length >= Constants.AUTH_MIN_PASSWORD_LENGTH && text.length <= com.fairuniversal.offlineotp.Utilities.InputPolicy.MAX_TEXT_CHARS && text == confirmation.text?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) { }
        }
        password.addTextChangedListener(watcher); confirmation.addTextChangedListener(watcher)
        host.updateValidity(false)
        showSettingsPrompt(host)
        host.setOnDismissListener {
            if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null
            host?.releaseInput()
            password.setText(""); confirmation.setText("")
        }
    }

    private fun showSharedSeparateBackupPassword() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val input = android.view.LayoutInflater.from(this).inflate(R.layout.component_password, null)
        val layout = requireNotNull(input.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.passwordLayout))
        val password = requireNotNull(input.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordEdit))
        val confirmation = requireNotNull(input.findViewById<android.widget.EditText>(R.id.passwordConfirm))
        password.isSaveEnabled = false; confirmation.isSaveEnabled = false
        password.setText(""); confirmation.setText("")
        password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        confirmation.inputType = password.inputType
        if (settings!!.getBlockAccessibility()) {
            layout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            confirmation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }
        if (settings!!.getBlockAutofill()) {
            layout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            confirmation.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
        }
        ConfirmedPasswordTransformationHelper.setup(layout, password, confirmation)
        input.findViewById<View>(R.id.btnSave)?.visibility = View.GONE
        input.findViewById<View>(R.id.btnCancel)?.visibility = View.GONE
        var host: ConfirmedSecretPreferenceComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            password.setText(""); confirmation.setText("")
            h?.releaseInput(); h?.dismiss()
            Unit
        }
        host = ConfirmedSecretPreferenceComposeView(this, input,
            title = getString(R.string.settings_title_backup_password),
            description = getString(R.string.settings_desc_shared_backup_password),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onSave = {
                val owned = settingsConfirmationPrompt === host
                val value = password.text?.toString().orEmpty()
                val matches = value == confirmation.text?.toString().orEmpty()
                closeHost()
                if (owned && matches && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applySeparateBackupPassword(this, value))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        val watcher = object : android.text.TextWatcher {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = password.text?.toString().orEmpty()
                host?.updateValidity(text.length <= com.fairuniversal.offlineotp.Utilities.InputPolicy.MAX_TEXT_CHARS && text == confirmation.text?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) { }
        }
        password.addTextChangedListener(watcher); confirmation.addTextChangedListener(watcher)
        host.updateValidity(true) // Both blank intentionally clears the separate credential.
        showSettingsPrompt(host)
        host.setOnDismissListener {
            if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null
            host?.releaseInput()
            password.setText(""); confirmation.setText("")
        }
    }

    private fun showSharedBackupPasswordSource() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_backup_password_source),
            message = "Full vault copies use the vault recovery password and run after successful saves while unlocked, using the configured destination and retention. Migrate a legacy vault in Backups first. Separate password modes remain exports. No new locked-vault or periodic background job is enabled.",
            items = listOf(getString(R.string.settings_backup_password_source_app), getString(R.string.settings_backup_password_source_separate), "Full saved vault file copies"),
            initialSelected = if (LoginBackupPolicy.usesVaultCopy(this)) 2 else if (LoginBackupPolicy.usesLoginPassword(this)) 0 else 1,
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    val login = index == 0
                    if (index == 2) {
                        if (!settings!!.applyVaultCopyBackupSource(this))
                            showSettingsNotice(R.string.settings_dialog_title_error, R.string.settings_backup_vault_copy_requires_migration)
                        publishSettingsOverview()
                    } else if (login && settings!!.hasLegacyBackupPassword(this)) confirmLegacyBackupPasswordRemoval()
                    else {
                        if (!settings!!.applyBackupPasswordSource(this, login))
                            showSettingsFeedback(R.string.settings_save_failed)
                        publishSettingsOverview()
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun confirmLegacyBackupPasswordRemoval() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        var host: DeleteConfirmationComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = DeleteConfirmationComposeView(this,
            title = getString(R.string.settings_dialog_title_remove_old_backup_password),
            message = getString(R.string.settings_dialog_msg_remove_old_backup_password),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = {
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyBackupPasswordSource(this, useLogin = true, removeLegacy = true))
                        showSettingsFeedback(R.string.settings_toast_remove_old_backup_password_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedPolicyChecklist(policy: PolicyChecklist) {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val panic = policy == PolicyChecklist.PANIC
        val labels = resources.getStringArray(if (panic) R.array.settings_entries_panic else R.array.settings_entries_backup_broadcasts).toList()
        val values = resources.getStringArray(if (panic) R.array.settings_values_panic else R.array.settings_values_backup_broadcasts).toList()
        if (labels.isEmpty() || labels.size != values.size || values.toSet() != policy.allowed || values.distinct().size != values.size) return
        val current = if (panic) settings!!.getPanicResponse() else settings!!.getBackupBroadcasts()
        var host: MultiChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = MultiChoiceSelectionComposeView(this,
            title = getString(if (panic) R.string.settings_title_panic else R.string.settings_title_backup_broadcasts),
            message = getString(if (panic) R.string.settings_desc_shared_panic else R.string.settings_desc_backup_broadcasts),
            items = labels, initialSelected = values.indices.filter { values[it] in current }.toSet(), allowEmpty = true,
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { indices ->
                if (indices.all { it in values.indices }) {
                    val owned = settingsConfirmationPrompt === host
                    closeHost()
                    if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                        val chosen = indices.map { values[it] }.toSet()
                        if (!settings!!.applyPolicyChecklist(this, policy, chosen)) {
                            showSettingsFeedback(R.string.settings_save_failed)
                        } else if (!panic && chosen.isNotEmpty()) {
                            // Same runtime-permission handoff as the native preference; no work is
                            // scheduled and permission denial does not change the configured set.
                            NotificationHelper.requestNotificationPermission(this)
                        }
                        publishSettingsOverview()
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedAutoBackupMode() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val labels = resources.getStringArray(R.array.settings_entries_auto_backup_password_enc).toList()
        val values = resources.getStringArray(R.array.settings_values_auto_backup_password_enc).map { raw ->
            Constants.AutoBackup.values().firstOrNull { it.name.lowercase(Locale.ROOT) == raw }
        }
        if (labels.isEmpty() || labels.size != values.size || values.any { it == null } || values.distinct().size != values.size) return
        val ready = BackupHelper.autoBackupType(this) == Constants.BackupType.ENCRYPTED
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_auto_backup_password_enc), items = labels,
            message = getString(if (ready) R.string.settings_desc_auto_backup_password_enc else R.string.settings_desc_auto_backup_requirements),
            initialSelected = values.indexOf(settings!!.currentAutoBackupMode()),
            enabledIndices = values.indices.filter { ready || values[it] == Constants.AutoBackup.OFF }.toSet(),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyAutoBackupMode(this, requireNotNull(values[index])))
                        showSettingsFeedback(R.string.settings_desc_auto_backup_requirements)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedOpenPgpProviderOptions() {
        if (!overviewSessionReady() || !canGoBack || settings!!.getOpenPGPMode() != OpenPgpMode.PROVIDER ||
            settings!!.getOpenPGPProvider().isBlank()) return
        val generation = overviewGeneration
        val provider = settings!!.getOpenPGPProvider()
        var host: OpenPgpProviderOptionsComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = OpenPgpProviderOptionsComposeView(this, settings!!.getOpenPGPEncryptionUserIDs(), settings!!.getOpenPGPVerify(),
            title = getString(R.string.settings_title_pgp_provider_options),
            recipientsLabel = getString(R.string.settings_title_openpgp_key_encrypt),
            recipientsDescription = getString(R.string.settings_desc_openpgp_key_encrypt),
            verifyLabel = getString(R.string.settings_title_openpgp_verify),
            verifyDescription = getString(R.string.settings_desc_openpgp_verify),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onSave = { recipients, verify ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation) &&
                    settings!!.getOpenPGPMode() == OpenPgpMode.PROVIDER && settings!!.getOpenPGPProvider() == provider) {
                    if (!settings!!.applyOpenPgpProviderOptions(this, recipients, verify))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedOpenPgpMode() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val labels = resources.getStringArray(R.array.settings_entries_openpgp_mode).toList()
        val rawValues = resources.getStringArray(R.array.settings_values_openpgp_mode).toList()
        val modes = rawValues.map { raw -> OpenPgpMode.values().firstOrNull { OpenPgpMode.preferenceValue(it) == raw } }
        if (labels.isEmpty() || labels.size != modes.size || modes.any { it == null } || modes.distinct().size != modes.size) return
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_openpgp_mode),
            message = getString(R.string.settings_desc_openpgp_mode), items = labels,
            initialSelected = modes.indexOf(settings!!.getOpenPGPMode()),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyOpenPgpMode(this, requireNotNull(modes[index])))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedVaultBehavior() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val current = settings!!.currentVaultBehavior()
        val selected = buildSet {
            if (current.minimizeOnCopy) add(0)
            if (current.focusSearchOnStart) add(1)
            if (current.hideGlobalTimeout) add(2)
        }
        var host: MultiChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = MultiChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_vault_behavior),
            message = listOf(R.string.settings_desc_minimize_on_copy, R.string.settings_desc_focus_search_on_start,
                R.string.settings_desc_hide_global_timeout).joinToString("\n\n") { getString(it) },
            items = listOf(R.string.settings_title_minimize_on_copy, R.string.settings_title_focus_search_on_start,
                R.string.settings_title_hide_global_timeout).map { getString(it) },
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            initialSelected = selected, allowEmpty = true,
            onConfirm = { indices ->
                if (indices.all { it in 0..2 }) {
                    val owned = settingsConfirmationPrompt === host
                    closeHost()
                    if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                        if (!settings!!.applyVaultBehavior(this, VaultBehaviorSelection(0 in indices, 1 in indices, 2 in indices)))
                            showSettingsFeedback(R.string.settings_save_failed)
                        publishSettingsOverview()
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedTagBehavior() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val labels = resources.getStringArray(R.array.settings_entries_tag_functionality).toList()
        val values = resources.getStringArray(R.array.settings_values_tag_functionality).map { raw ->
            Constants.TagFunctionality.values().firstOrNull { it.name.lowercase(Locale.ROOT) == raw }
        }
        if (labels.isEmpty() || labels.size != values.size || values.any { it == null } || values.distinct().size != values.size) return
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_tag_functionality), items = labels,
            initialSelected = values.indexOf(settings!!.getTagFunctionality()),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyTagBehavior(this, requireNotNull(values[index])))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedBackupFiles() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val current = settings!!.currentBackupFileSelection()
        val selected = buildSet {
            if (current.appendDateTime) add(0)
            if (current.askForDestination) add(1)
        }
        var host: MultiChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = MultiChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_backup_files),
            message = listOf(R.string.settings_desc_backup_append_date, R.string.settings_desc_backup_ask)
                .joinToString("\n\n") { getString(it) },
            items = listOf(getString(R.string.settings_title_backup_append_date), getString(R.string.settings_title_backup_ask)),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            initialSelected = selected, allowEmpty = true,
            onConfirm = { indices ->
                if (indices.all { it in 0..1 }) {
                    val owned = settingsConfirmationPrompt === host
                    closeHost()
                    if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                        if (!settings!!.applyBackupFileSelection(this, BackupFileSelection(0 in indices, 1 in indices)))
                            showSettingsFeedback(R.string.settings_save_failed)
                        publishSettingsOverview()
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedBackupRetention() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val labels = resources.getStringArray(R.array.settings_entries_auto_backup_keep).toList()
        val values = resources.getStringArray(R.array.settings_values_auto_backup_keep).map { it.toIntOrNull() }
        if (labels.isEmpty() || labels.size != values.size || values.any { it == null } ||
            values.distinct().size != values.size) return
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_auto_backup_keep),
            message = getString(R.string.settings_desc_auto_backup_keep), items = labels,
            initialSelected = values.indexOf(settings!!.getAutoBackupKeepCount()),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyBackupRetention(this, requireNotNull(values[index])))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedRevealTimeout() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        var host: NumericPreferenceComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = NumericPreferenceComposeView(this, initialValue = settings!!.getTapToRevealTimeout(),
            minimum = resources.getInteger(R.integer.settings_min_tap_to_reveal_timeout),
            maximum = resources.getInteger(R.integer.settings_max_tap_to_reveal_timeout),
            title = getString(R.string.settings_title_tap_to_reveal_timeout),
            inputLabel = getString(R.string.settings_desc_tap_to_reveal_timeout),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onSave = { seconds ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyRevealTimeout(this, seconds))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedInputProtection() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val current = settings!!.currentInputProtection()
        val selected = buildSet {
            if (current.blockAccessibility) add(0)
            if (current.blockAutofill) add(1)
            if (current.autoUnlockAfterAutofill) add(2)
        }
        var host: MultiChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = MultiChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_input_protection),
            message = listOf(R.string.settings_desc_block_accessibility, R.string.settings_desc_block_autofill,
                R.string.settings_desc_auto_unlock_after_autofill).joinToString("\n\n") { getString(it) },
            items = listOf(R.string.settings_title_block_accessibility, R.string.settings_title_block_autofill,
                R.string.settings_title_auto_unlock_after_autofill).map { getString(it) },
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            initialSelected = selected, allowEmpty = true,
            onConfirm = { indices ->
                if (indices.all { it in 0..2 }) {
                    val owned = settingsConfirmationPrompt === host
                    closeHost()
                    if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                        if (!settings!!.applyInputProtection(this, InputProtectionSelection(0 in indices, 1 in indices, 2 in indices)))
                            showSettingsFeedback(R.string.settings_save_failed)
                        publishSettingsOverview()
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedAppearance(preference: AppearancePreference) {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val labels = resources.getStringArray(preference.entriesId).toList()
        val values = resources.getStringArray(preference.valuesId).toList()
        if (values.isEmpty() || labels.size != values.size || values.distinct().size != values.size) return
        val titleId = when (preference) {
            AppearancePreference.LANGUAGE -> R.string.settings_title_lang
            AppearancePreference.THEME_MODE -> R.string.settings_title_theme_mode
            AppearancePreference.THEME -> R.string.settings_title_theme
            AppearancePreference.BLACK_AUTO -> R.string.settings_title_theme_black_auto
        }
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this, title = getString(titleId), items = labels,
            initialSelected = values.indexOf(settings!!.currentAppearanceValue(this, preference)),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    // Existing preference listener owns locale/theme recreation. Close first so
                    // a synchronous commit notification cannot leave a dialog on the old Activity.
                    if (!settings!!.applyAppearanceValue(this, preference, values[index]))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedScreenPrivacy() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val current = settings!!.currentScreenPrivacy()
        val selected = buildSet {
            if (current.allowScreenshots) add(0)
            if (current.hideInAppSwitcher) add(1)
            if (current.relockOnScreenOff) add(2)
        }
        var host: MultiChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = MultiChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_screen_privacy),
            message = getString(R.string.settings_desc_screen_privacy),
            items = listOf(getString(R.string.settings_title_enable_screenshot),
                getString(R.string.settings_title_hide_in_app_switcher), getString(R.string.settings_title_relock_screen_off)),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            initialSelected = selected, allowEmpty = true,
            onConfirm = { indices ->
                if (indices.all { it in 0..2 }) {
                    val owned = settingsConfirmationPrompt === host
                    closeHost()
                    if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                        if (!settings!!.applyScreenPrivacy(this, ScreenPrivacySelection(0 in indices, 1 in indices, 2 in indices)))
                            showSettingsFeedback(R.string.settings_save_failed)
                        // Also reapply when no preference changed; policy is platform-owned.
                        applyScreenshotPolicyForForeground()
                        publishSettingsOverview()
                    }
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedTapAction(doubleTap: Boolean) {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val modes = Constants.TapMode.values().toList()
        val labels = resources.getStringArray(R.array.settings_entries_tap).toList()
        // Refuse a malformed resource rather than mapping a label to the wrong action.
        if (labels.size != modes.size) return
        val current = if (doubleTap) settings!!.getTapDouble() else settings!!.getTapSingle()
        var host: SingleChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = SingleChoiceSelectionComposeView(this,
            title = getString(if (doubleTap) R.string.settings_title_tap_double else R.string.settings_title_tap_single),
            items = labels, initialSelected = modes.indexOf(current),
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            onConfirm = { index ->
                val owned = settingsConfirmationPrompt === host
                closeHost()
                if (owned && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    if (!settings!!.applyTapAction(this, doubleTap, modes[index]))
                        showSettingsFeedback(R.string.settings_save_failed)
                    publishSettingsOverview()
                }
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
        host.setOnDismissListener { if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null }
    }

    private fun showSharedSearchFields() {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val options = Constants.SearchIncludes.values().toList()
        val labels = resources.getStringArray(R.array.settings_entries_search_includes).toList()
            .takeIf { it.size >= options.size }?.take(options.size)
            ?: listOf(getString(R.string.label_label), getString(R.string.label_issuer), getString(R.string.label_tags))
        val current = settings!!.getSearchValues().toSet()
        var host: MultiChoiceSelectionComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = MultiChoiceSelectionComposeView(this,
            title = getString(R.string.settings_title_search_includes),
            message = getString(R.string.settings_desc_search_includes), items = labels,
            confirmLabel = getString(android.R.string.ok), dismissLabel = getString(android.R.string.cancel),
            initialSelected = options.indices.filter { options[it] in current }.toSet().ifEmpty { setOf(0) },
            onConfirm = { indices ->
                if (settingsConfirmationPrompt === host && canGoBack && overviewSessionReady() && VaultSession.isCurrent(generation)) {
                    val chosen = indices.sorted().mapNotNull { options.getOrNull(it) }
                    if (settings!!.setSearchValues(this, chosen)) {
                        closeHost()
                        publishSettingsOverview()
                    } else showSettingsFeedback(R.string.settings_save_failed)
                } else closeHost()
            }, onDismiss = closeHost)
        showSettingsPrompt(host)
    }

    private fun launchRequestedVaultTool() {
        val request = getIntent() ?: return
        val generation = request.getStringExtra(Constants.EXTRA_VAULT_TOOL_GENERATION)?.toLongOrNull() ?: return
        if (!VaultSession.isCurrent(generation) || VaultSession.getKey() !== encryptionKey ||
            encryptionKey == null || isFinishing()) return
        when (request.getIntExtra(Constants.EXTRA_VAULT_TOOL_ACTION, -1)) {
            Constants.INTENT_SETTINGS_PREFERENCES_EXPORT -> confirmPreferencesExport()
            Constants.INTENT_SETTINGS_PREFERENCES_IMPORT -> requestPreferencesImport()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveImportFeedback(outState)

        outState.putBoolean(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, encryptionChanged)
        outState.putLong(STATE_AUTH_GENERATION, authenticationGeneration)
        outState.putBoolean(STATE_SETTINGS_PICKER, overviewPickerPending)
        outState.putBoolean(STATE_SIGNING_PENDING, signingConnection != null)
    }

    /** Finishes and reports to MainActivity whether the encryption changed. */
    fun finishWithResult() {
        val data = Intent()

        data.putExtra(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, encryptionChanged)

        setResult(RESULT_OK, data)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (canGoBack)
            finishWithResult()

        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (canGoBack) {
            finishWithResult()
            super.onBackPressed()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (isFinishing() || key == null) return
        if (key == getString(R.string.settings_key_backup_broadcasts) &&
                !settings!!.isEncryptedBackupBroadcastEnabled()) {
            androidx.work.WorkManager.getInstance(this)
                .cancelUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)
        }

        // Both switches decide one flag, so either change re-applies the policy for this window.
        if (key == getString(R.string.settings_key_enable_screenshot) ||
                key == getString(R.string.settings_key_hide_in_app_switcher)) {
            applyScreenshotPolicyForForeground()
        }

        if (key == getString(R.string.settings_key_theme) ||
                key == getString(R.string.settings_key_lang) ||
                key == getString(R.string.settings_key_special_features) ||
                key == getString(R.string.settings_key_backup_location) ||
                key == getString(R.string.settings_key_theme_mode) ||
                key == getString(R.string.settings_key_theme_black_auto)) {
            recreate()
        } else if (key == getString(R.string.settings_key_encryption)) {
            if (settings!!.getEncryption() != EncryptionType.PASSWORD) {
                if (settings!!.getAndroidBackupServiceEnabled()) {
                    showSettingsNotice(
                        R.string.settings_dialog_title_android_sync,
                        R.string.settings_dialog_msg_android_sync_disabled_encryption
                    )
                }

                settings!!.setAndroidBackupServiceEnabled(false)
            }
        } else if (key == getString(R.string.settings_key_enable_android_backup_service)) {
            Log.d(SettingsActivity::class.java.simpleName,
                "onSharedPreferenceChanged called modifying settings_key_enable_android_backup_service service is now: " +
                        (if (settings!!.getAndroidBackupServiceEnabled()) "enabled" else "disabled"))

            val message = if (settings!!.getAndroidBackupServiceEnabled())
                R.string.settings_toast_android_sync_enabled else R.string.settings_toast_android_sync_disabled
            showSettingsFeedback(message)
        }

        publishSettingsOverview()
    }

    override fun requiresVaultSession(): Boolean = true

    override fun onVaultLocked() {
        importFeedback = null
        clearPreferencesExport()
        clearSettingsFeedback()
        closeSigningSelection()
        credentialsTask?.let { it.setOutcomeCallback(null); it.cancel() }
        credentialsTask = null
        if (clearingKeyStore) return
        settingsOverview?.close()
        dismissSettingsPrompts()
        encryptionKey = null
        cancelBackgroundTask()
        finish()
    }

    internal fun clearKeyStore() {
        clearingKeyStore = true
        try {
            KeyStoreHelper.wipeKeys(this)
            try { BiometricKeyStore.clear(this) } catch (_: Exception) { }
            settings!!.preferenceStore()
                .edit()
                .remove(getString(R.string.settings_key_backup_password_enc))
                .apply()
            if (settings!!.getEncryption() == EncryptionType.KEYSTORE) {
                DatabaseHelper.wipeDatabase(this)
                generateNewEncryptionKey()
            }
        } finally {
            clearingKeyStore = false
        }
    }

    /** Generates a new KeyStore key after all keys were wiped. */
    private fun generateNewEncryptionKey() {
        if (settings!!.getEncryption() == EncryptionType.KEYSTORE) {
            val unlockAttempt = VaultSession.beginUnlock()
            val key = KeyStoreHelper.createEncryptionKey(this)
            if (key == null || DatabaseHelper.changeVaultEncryption(this, null, key, true,
                    { id ->
                        VaultCommitDecision.fromCommit(
                            settings!!.commitVaultSettings(id, EncryptionType.KEYSTORE, null, null, true))
                    }) !is VaultWriteResult.Saved ||
                    VaultSession.activate(key, unlockAttempt.generation) !is SessionCommandResult.Applied) {
                VaultSession.lock()
                encryptionKey = null
                finish()
                return
            }
            encryptionKey = key
            encryptionChanged = true
            overviewGeneration = VaultSession.generation()
            publishSettingsOverview()
        }
    }

    /** Asks for the password before switching to password encryption. */
    private fun tryEncryptionChangeWithAuth(newEnc: EncryptionType) {
        authenticationGeneration = VaultSession.generation()
        val authIntent = Intent(this, AuthenticateActivity::class.java)
        authIntent.putExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION, newEnc.name)
        authIntent.putExtra(Constants.EXTRA_AUTH_MESSAGE, R.string.auth_msg_confirm_encryption)
        overviewPickerPending = true
        publishSettingsOverview()
        try { startActivityForResult(authIntent, Constants.INTENT_SETTINGS_AUTHENTICATE) }
        catch (_: RuntimeException) {
            overviewPickerPending = false
            authenticationGeneration = -1L
            publishSettingsOverview()
        }
    }

    override fun onSuccessfulEncryptionChange(newEncryptionType: EncryptionType, newEncryptionKey: SecretKey) {
        if (isFinishing() || VaultSession.getKey() == null) return
        if (VaultSession.activate(newEncryptionKey, VaultSession.generation()) !is SessionCommandResult.Applied) return
        encryptionKey = newEncryptionKey
        encryptionChanged = true
        overviewGeneration = VaultSession.generation()

        publishSettingsOverview()
    }

    override fun onTaskResult(result: ChangeEncryptionTask.Result) {
        when (result.result) {
            EncryptionChangeResult.SUCCESS -> {
                val newType = result.encryptionType
                val newKey = result.newEncryptionKey
                if (newType != null && newKey != null)
                    onSuccessfulEncryptionChange(newType, newKey)
                showSettingsFeedback(R.string.settings_toast_encryption_change_success)
            }
            EncryptionChangeResult.MISSING_NEW_KEY,
            EncryptionChangeResult.DECRYPTION_FAILED,
            EncryptionChangeResult.TASK_CREATION_FAILED -> {
                showSettingsFeedback(R.string.settings_toast_encryption_change_failed)
            }
            EncryptionChangeResult.BACKUP_FAILURE -> {
                showSettingsFeedback(R.string.settings_toast_encryption_backup_failed)
            }
            EncryptionChangeResult.CHANGE_FAILURE -> {
                showSettingsFeedback(R.string.settings_toast_encryption_change_failed)
            }
        }
    }

    /** Re-encrypts the database with the new key in the background. */
    private fun startEncryptionChangeTask(newEnc: EncryptionType, newKey: ByteArray?) {
        val task = ChangeEncryptionTask(this, encryptionKey, newEnc, newKey)
        startBackgroundTask(task)
    }

    override fun setupUiForTaskState(taskRunning: Boolean) {
        if (taskRunning) {
            canGoBack = false
            if (inProgress == null) {
                val dialog = BusyProgressComposeView(
                    activity = this,
                    title = getString(R.string.settings_activity_title),
                    message = getString(R.string.settings_toast_encryption_changing)
                )
                inProgress = dialog
                dialog.show()
            }
        } else {
            inProgress?.dismiss()
            inProgress = null
            canGoBack = true
        }
        publishSettingsOverview()
    }

    private fun launchSettingsDocumentPicker(intent: Intent, requestCode: Int) {
        overviewPickerPending = true
        publishSettingsOverview()
        try {
            startActivityForResult(intent, requestCode)
        } catch (error: RuntimeException) {
            overviewPickerPending = false
            publishSettingsOverview()
            throw error
        }
    }

    /** Asks for a folder (Storage Access Framework) to store the backups in. */
    private fun requestBackupAccess() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

        if (settings!!.isBackupLocationSet())
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, settings!!.getBackupLocation())

        try {
            launchSettingsDocumentPicker(intent, Constants.INTENT_SETTINGS_BACKUP_LOCATION)
        } catch (error: ActivityNotFoundException) {
            // Work profiles and minimal ROMs may not have a document-tree provider. Do not let
            // this optional backup-location setting crash the entire Settings screen.
            showSettingsFeedback(R.string.backup_toast_file_selection_failed)
        } catch (error: SecurityException) {
            showSettingsFeedback(R.string.backup_toast_location_access_failed)
        }
    }

    /**
     * Asks for the OpenPGP key files used by the in-app engine. Recipients may be multiple public
     * certificates; the secret key is a single document. Only read access is requested and persisted,
     * so the app can use the keys later without writing to the user's files.
     */
    private fun requestOpenPgpKeyFiles(multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val requestCode = if (multiple) Constants.INTENT_SETTINGS_OPENPGP_RECIPIENT_FILES
            else Constants.INTENT_SETTINGS_OPENPGP_SECRET_FILE
        try {
            launchSettingsDocumentPicker(intent, requestCode)
        } catch (error: ActivityNotFoundException) {
            showSettingsFeedback(R.string.backup_toast_file_selection_failed)
        } catch (error: SecurityException) {
            showSettingsFeedback(R.string.backup_toast_file_selection_failed)
        }
    }

    /** Keeps a read grant for a picked key document, reporting whether it is usable and retained. */
    private fun retainKeyFileGrant(data: Intent?, uri: Uri?): Boolean {
        if (data == null || !OpenPgpKeyFilePolicy.isUsableKeyUri(uri?.toString())) return false
        return try {
            getContentResolver().takePersistableUriPermission(uri!!,
                data.getFlags() and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (error: RuntimeException) {
            // Without a persistable grant the key would be unreadable on the next backup.
            false
        }
    }

    /** Stores the picked key documents and refreshes the summaries that report them. */
    private fun handleOpenPgpKeyFileResult(requestCode: Int, data: Intent?) {
        val multiple = requestCode == Constants.INTENT_SETTINGS_OPENPGP_RECIPIENT_FILES
        val picked = LinkedHashSet<String>()
        val clip = data?.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                val uri = clip.getItemAt(index).uri
                if (retainKeyFileGrant(data, uri)) picked.add(uri.toString())
            }
        } else {
            val uri = data?.data
            if (retainKeyFileGrant(data, uri)) picked.add(uri!!.toString())
        }
        if (picked.isEmpty()) {
            showSettingsFeedback(R.string.backup_toast_file_selection_failed)
            return
        }
        if (multiple) settings!!.setOpenPGPRecipientFiles(picked)
        else settings!!.setOpenPGPSecretFile(picked.first())
        publishSettingsOverview()
    }

    /** Shows the read-only private vault directory and managed filenames through the shared Compose dialog. */
    fun showStorageLocation() {
        var host: StorageLocationComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = StorageLocationComposeView(
            activity = this,
            directory = getFilesDir().absolutePath,
            filenames = listOf(
                Constants.FILENAME_ENCRYPTED_KEY,
                Constants.FILENAME_DATABASE,
                Constants.FILENAME_DATABASE_BACKUP
            ),
            title = getString(R.string.settings_title_storage_location),
            guidance = listOf(
                getString(R.string.storage_location_guidance_private),
                getString(R.string.storage_location_guidance_backup)
            ),
            onDismiss = closeHost
        )
        showSettingsPrompt(host)
    }

    /** Pending preferences export; serialised before the picker so a failure is reported first. */
    private var preferencesExportPending: PreferencesExport.Export? = null
    private var preferencesExportGeneration = -1L

    private fun clearPreferencesExport() {
        preferencesExportPending?.zero()
        preferencesExportPending = null
        preferencesExportGeneration = -1L
    }

    /** Asks whether the file should carry the backup password, then opens the picker. */
    fun confirmPreferencesExport() {
        val generation = VaultSession.generation()
        if (VaultSession.getKey() == null || isFinishing()) return
        var host: PreferencesExportComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = PreferencesExportComposeView(
            activity = this,
            title = getString(R.string.settings_title_preferences_export),
            backupPasswordLabel = getString(R.string.preferences_export_with_secrets),
            message = getString(R.string.preferences_export_question) + "\n\n" +
                getString(R.string.preferences_export_question_detail),
            onConfirm = { includeSecrets ->
                val ownsPrompt = settingsConfirmationPrompt === host
                closeHost()
                if (ownsPrompt && VaultSession.isCurrent(generation) && VaultSession.getKey() != null && !isFinishing()) {
                    startPreferencesExport(includeSecrets = includeSecrets)
                }
            },
            onDismiss = closeHost
        )
        showSettingsPrompt(host)
    }

    private fun startPreferencesExport(includeSecrets: Boolean) {
        if (!overviewSessionReady() || !canGoBack) return
        val export = try {
            PreferencesExport.export(AndroidPreferencesPort(this, Settings(this)), includeSecrets,
                platform = PreferencesExport.PLATFORM_ANDROID)
        } catch (_: RuntimeException) {
            showSettingsFeedback(R.string.preferences_export_failed)
            return
        }
        preferencesExportPending?.zero()
        preferencesExportPending = export
        preferencesExportGeneration = overviewGeneration
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "offlineotp-preferences.json")
        try {
            launchSettingsDocumentPicker(intent, Constants.INTENT_SETTINGS_PREFERENCES_EXPORT)
        } catch (_: RuntimeException) {
            export.zero()
            preferencesExportPending = null
            showSettingsFeedback(R.string.preferences_export_failed)
        }
    }

    private fun savePreferencesExport(uri: Uri) {
        val pending = preferencesExportPending ?: return
        preferencesExportPending = null
        val generation = preferencesExportGeneration
        preferencesExportGeneration = -1L
        if (!overviewSessionReady() || !canGoBack || !VaultSession.isCurrent(generation)) {
            pending.zero()
            return
        }
        val saved = try {
            StorageAccessHelper.saveFile(this, uri, pending.bytes, BooleanSupplier { overviewSessionReady() && VaultSession.isCurrent(generation) })
        } catch (_: RuntimeException) {
            false
        } finally {
            pending.zero()
        }
        when {
            !saved -> showSettingsFeedback(R.string.preferences_export_failed)
            pending.includesSecrets ->
                showSettingsFeedback(R.string.preferences_export_success_with_secrets)
            else -> showSettingsFeedback(getString(R.string.preferences_export_success, pending.keysWritten))
        }
    }

    /** Opens the picker; the confirmation happens once the file is known to be readable. */
    fun requestPreferencesImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            launchSettingsDocumentPicker(intent, Constants.INTENT_SETTINGS_PREFERENCES_IMPORT)
        } catch (_: RuntimeException) {
            showSettingsFeedback(R.string.preferences_import_failed)
        }
    }

    private fun confirmPreferencesImport(uri: Uri) {
        if (!overviewSessionReady() || !canGoBack) return
        val generation = overviewGeneration
        val bytes = try {
            getContentResolver().openInputStream(uri).use { BoundedInputReader.read(it, PreferencesExport.MAX_BYTES) }
        } catch (_: RuntimeException) {
            showSettingsFeedback(R.string.preferences_import_failed)
            return
        }
        // Validate once; preserve the specific wrong-platform diagnostic instead of replacing it.
        val preview = previewPreferencesImport(bytes) ?: run {
            bytes.fill(0)
            return
        }
        var dialogRef: PreferencesImportComposeView? = null
        val closeDialog = {
            val host = dialogRef
            if (settingsConfirmationPrompt === host) settingsConfirmationPrompt = null
            host?.dismiss()
            Unit
        }
        val dialog = PreferencesImportComposeView(
            activity = this,
            count = preview.applied,
            skipped = preview.unknownKeys.size + preview.refusedKeys.size,
            applyDetail = getString(R.string.preferences_import_question_detail),
            onConfirm = {
                val owned = settingsConfirmationPrompt === dialogRef
                if (owned && overviewSessionReady() && canGoBack && VaultSession.isCurrent(generation)) {
                    val payload = bytes.copyOf()
                    closeDialog()
                    applyPreferencesImport(payload)
                } else closeDialog()
            },
            onDismiss = { closeDialog() }
        )
        dialogRef = dialog
        // Back or a tap outside is a cancel too, and the read bytes must not outlive the dialog.
        dialog.setOnDismissListener { bytes.fill(0) }
        showSettingsPrompt(dialog)
    }

    /**
     * A dry run against a port that writes nowhere: the codec validates the whole document before
     * applying anything, so this rejects a bad file without a second import attempt.
     */
    private fun previewPreferencesImport(bytes: ByteArray): PreferencesExport.Import? {
        val probe = object : PreferencesExport.SettingsPort {
            override fun storedKeys(): Set<String> = emptySet()
            override fun read(key: String): PreferencesExport.Value? = null
            override fun readBackupPassword(): String? = null
            override fun write(key: String, value: PreferencesExport.Value) { }
        }
        return try {
            PreferencesExport.import(bytes, probe, PreferencesExport.PLATFORM_ANDROID)
        } catch (error: IllegalArgumentException) {
            // Say which failure it was: a file for another client is not a corrupt file.
            showSettingsFeedback(if (error is PreferencesExport.WrongPlatformException)
                R.string.preferences_import_wrong_platform else R.string.preferences_import_failed)
            null
        }
    }

    private fun applyPreferencesImport(bytes: ByteArray) {
        val result = try {
            PreferencesExport.import(bytes, AndroidPreferencesPort(this, Settings(this)),
                PreferencesExport.PLATFORM_ANDROID)
        } catch (_: RuntimeException) {
            showSettingsFeedback(R.string.preferences_import_failed)
            return
        } finally {
            bytes.fill(0)
        }
        importFeedback = PreferencesImportFeedback(overviewGeneration, result.applied,
            result.unknownKeys.size, result.includesSecrets)
        // The screen the user is looking at may now describe settings that changed underneath it.
        if (!isFinishing()) recreate()
    }

    private fun completeSettingsDocumentPicker(requestCode: Int) {
        if (requestCode in listOf(Constants.INTENT_SETTINGS_BACKUP_LOCATION,
                Constants.INTENT_SETTINGS_OPENPGP_RECIPIENT_FILES, Constants.INTENT_SETTINGS_OPENPGP_SECRET_FILE,
                Constants.INTENT_SETTINGS_PREFERENCES_EXPORT, Constants.INTENT_SETTINGS_PREFERENCES_IMPORT)) {
            overviewPickerPending = false
            publishSettingsOverview()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == signingRequestCode) {
            if (resultCode == RESULT_OK && data != null && signingSelectionCurrent()) requestSigningKey(data)
            else { closeSigningSelection(); publishSettingsOverview() }
            return
        }

        completeSettingsDocumentPicker(requestCode)

        if (requestCode == Constants.INTENT_SETTINGS_AUTHENTICATE) {
            overviewPickerPending = false
            publishSettingsOverview()
            if (resultCode == RESULT_OK && data != null && !isFinishing() &&
                    VaultSession.isCurrent(authenticationGeneration)) {
                val authKey = VaultSession.takeDerivedKey(authenticationGeneration)
                val newEnc = data.getStringExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION)

                if (authKey != null && authKey.isNotEmpty() && newEnc != null && newEnc.isNotEmpty()) {
                    val newEncType = EncryptionType.valueOf(newEnc)
                    startEncryptionChangeTask(newEncType, authKey)
                    Arrays.fill(authKey, 0)
                } else {
                    if (authKey != null) Arrays.fill(authKey, 0)
                    showSettingsFeedback(R.string.settings_toast_encryption_no_key)
                }
            } else if (resultCode != RESULT_CANCELED && !isFinishing()) {
                showSettingsFeedback(R.string.settings_toast_encryption_auth_failed)
            }
        } else if (requestCode == Constants.INTENT_SETTINGS_PREFERENCES_EXPORT) {
            if (resultCode == RESULT_OK && data?.data != null) savePreferencesExport(data.data!!)
            else { clearPreferencesExport() }
        } else if (requestCode == Constants.INTENT_SETTINGS_PREFERENCES_IMPORT) {
            if (resultCode == RESULT_OK && data?.data != null) confirmPreferencesImport(data.data!!)
        } else if (requestCode == Constants.INTENT_SETTINGS_BACKUP_LOCATION && resultCode == RESULT_OK) {
            val treeUri: Uri? = data?.data
            if (treeUri != null && "content" == treeUri.getScheme() &&
                    treeUri.toString().length <= InputPolicy.MAX_URI_CHARS) {
                try {
                    val takeFlags = data.getFlags() and
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags)
                    settings!!.setBackupLocation(treeUri)
                } catch (error: RuntimeException) {
                    showSettingsFeedback(R.string.backup_toast_location_access_failed)
                }
            }
        } else if (requestCode == Constants.INTENT_SETTINGS_OPENPGP_RECIPIENT_FILES ||
                requestCode == Constants.INTENT_SETTINGS_OPENPGP_SECRET_FILE) {
            if (resultCode == RESULT_OK && !isFinishing()) handleOpenPgpKeyFileResult(requestCode, data)
        }
    }

    override fun onDestroy() {
        clearPreferencesExport()
        clearSettingsFeedback()
        closeSigningSelection()
        credentialsTask?.let { it.setOutcomeCallback(null); it.cancel() }
        credentialsTask = null
        settingsOverview?.close()
        settingsOverview = null
        inProgress?.dismiss()
        inProgress = null
        dismissSettingsPrompts()
        prefs?.unregisterOnSharedPreferenceChangeListener(this)
        encryptionKey = null
        prefs = null

        super.onDestroy()
    }

}
