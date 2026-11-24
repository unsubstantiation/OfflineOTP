package com.fairuniversal.offlineotp.Activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.ViewGroup
import android.widget.Toast

import androidx.appcompat.widget.Toolbar

import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

import org.openintents.openpgp.util.OpenPgpAppPreference
import org.openintents.openpgp.util.OpenPgpKeyPreference
import com.fairuniversal.offlineotp.Preferences.CredentialsPreference
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

    private var fragment: SettingsFragment? = null
    private var settingsOverview: SettingsOverviewComposeView? = null
    private var legacySettingsContent: View? = null
    private var showingAdvancedSettings = false
    private var overviewPickerPending = false
    private var overviewGeneration = -1L

    /** The view the snackbars attach to: the settings fragment's view, or the window while it is absent. */
    private fun snackbarAnchor(): View = fragment?.getView() ?: getWindow().decorView
    private var prefs: SharedPreferences? = null

    private var encryptionKey: SecretKey? = null
    private var authenticationGeneration: Long = -1
    private var encryptionChanged = false
    private var clearingKeyStore = false

    internal var inProgress: ComposeDialogHost? = null
    internal var biometricPrompt: ComposeDialogHost? = null
    internal var settingsConfirmationPrompt: ComposeDialogHost? = null
    private var canGoBack = true

    internal fun dismissBiometricPrompt() {
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

    private companion object {
        const val STATE_AUTH_GENERATION = "settings_auth_generation"
        const val STATE_ADVANCED_SETTINGS = "settings_advanced_visible"
        const val STATE_SETTINGS_PICKER = "settings_picker_pending"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.settings_activity_title)
        setContentView(R.layout.activity_container)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        setSupportActionBar(toolbar)

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.content_settings)
        val content = stub.inflate() as ViewGroup
        legacySettingsContent = content.findViewById(R.id.settings_legacy_content)
        showingAdvancedSettings = savedInstanceState?.getBoolean(STATE_ADVANCED_SETTINGS, false) == true
        overviewPickerPending = savedInstanceState?.getBoolean(STATE_SETTINGS_PICKER, false) == true

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

        val settingsFragment = SettingsFragment()
        fragment = settingsFragment
        settingsFragment.setEncryptionKey(encryptionKey)
        settingsFragment.setEncryptionChangeCallback(this)

        getFragmentManager().beginTransaction()
            .replace(R.id.settings_legacy_content, settingsFragment)
            .commit()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs = sharedPreferences
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        overviewGeneration = VaultSession.generation()
        val overview = SettingsOverviewComposeView(this, ::dispatchSettingsOverviewAction)
        settingsOverview = overview
        content.addView(overview, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateSettingsPageVisibility()
        publishSettingsOverview()
        // Consume only a fresh same-session handoff. Recreation must not launch another picker.
        if (savedInstanceState == null) launchRequestedVaultTool()
    }

    private fun overviewSessionReady(): Boolean = VaultSession.isCurrent(overviewGeneration) &&
        encryptionKey != null && VaultSession.getKey() === encryptionKey && !isFinishing()

    private fun updateSettingsPageVisibility() {
        legacySettingsContent?.visibility = if (showingAdvancedSettings) View.VISIBLE else View.GONE
        settingsOverview?.visibility = if (showingAdvancedSettings) View.GONE else View.VISIBLE
    }

    private fun returnToSettingsOverview(): Boolean {
        if (!canGoBack || !showingAdvancedSettings) return false
        showingAdvancedSettings = false
        updateSettingsPageVisibility()
        publishSettingsOverview()
        return true
    }

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
                    PortableToolItem("pgpProvider", getString(R.string.settings_title_openpgp_provider),
                        detail = getString(R.string.settings_pgp_provider_setup_detail),
                        enabled = s.getOpenPGPMode() == OpenPgpMode.PROVIDER)
                )),
                PortableToolGroup("", listOf(PortableToolItem("advanced",
                    getString(R.string.settings_advanced_title), detail = getString(R.string.settings_advanced_description))))
            ), busy = !canGoBack || overviewPickerPending
        )
    }

    private fun dispatchSettingsOverviewAction(key: String) {
        if (!overviewSessionReady() || !canGoBack || overviewPickerPending || showingAdvancedSettings || settingsConfirmationPrompt != null) return
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
            "autoBackupMode" -> showSharedAutoBackupMode()
            "backupFiles" -> showSharedBackupFiles()
            "backupRetention" -> showSharedBackupRetention()
            "backupFolder" -> requestBackupAccess()
            "storage" -> showStorageLocation()
            "exportPreferences" -> confirmPreferencesExport()
            "importPreferences" -> requestPreferencesImport()
            "advanced", "pgpProvider" -> {
                if (key == "pgpProvider" && settings!!.getOpenPGPMode() != OpenPgpMode.PROVIDER) return
                // Recreate so XML preferences read the values just committed by shared dialogs.
                showingAdvancedSettings = true
                updateSettingsPageVisibility()
                recreate()
            }
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
                            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
                        } else if (!panic && chosen.isNotEmpty()) {
                            // Same runtime-permission handoff as the native preference; no work is
                            // scheduled and permission denial does not change the configured set.
                            fragment?.let { NotificationHelper.requestNotificationPermission(it) }
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
                        Toast.makeText(this, R.string.settings_desc_auto_backup_requirements, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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
                    } else Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
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

        outState.putBoolean(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, encryptionChanged)
        outState.putLong(STATE_AUTH_GENERATION, authenticationGeneration)
        outState.putBoolean(STATE_ADVANCED_SETTINGS, showingAdvancedSettings)
        outState.putBoolean(STATE_SETTINGS_PICKER, overviewPickerPending)
    }

    /** Finishes and reports to MainActivity whether the encryption changed. */
    fun finishWithResult() {
        val data = Intent()

        data.putExtra(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, encryptionChanged)

        setResult(RESULT_OK, data)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (returnToSettingsOverview()) return true
        if (canGoBack)
            finishWithResult()

        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (returnToSettingsOverview()) return
        if (canGoBack) {
            finishWithResult()
            super.onBackPressed()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        val settingsFragment = fragment ?: return
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
                settingsFragment.useAndroidSync?.let {
                    it.isEnabled = false
                    it.isChecked = false
                }
            } else {
                settingsFragment.useAndroidSync?.isEnabled = false
            }
        } else if (key == getString(R.string.settings_key_enable_android_backup_service)) {
            Log.d(SettingsActivity::class.java.simpleName,
                "onSharedPreferenceChanged called modifying settings_key_enable_android_backup_service service is now: " +
                        (if (settings!!.getAndroidBackupServiceEnabled()) "enabled" else "disabled"))

            val message = if (settings!!.getAndroidBackupServiceEnabled())
                R.string.settings_toast_android_sync_enabled else R.string.settings_toast_android_sync_disabled
            Snackbar.make(snackbarAnchor(), message, BaseTransientBottomBar.LENGTH_SHORT).show()
        }

        settingsFragment.updateAutoBackup()
        publishSettingsOverview()
    }

    override fun requiresVaultSession(): Boolean = true

    override fun onVaultLocked() {
        if (clearingKeyStore) return
        settingsOverview?.close()
        dismissSettingsPrompts()
        encryptionKey = null
        fragment?.setEncryptionKey(null)
        cancelBackgroundTask()
        finish()
    }

    internal fun clearKeyStore() {
        clearingKeyStore = true
        try {
            KeyStoreHelper.wipeKeys(this)
            try { BiometricKeyStore.clear(this) } catch (_: Exception) { }
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .remove(getString(R.string.settings_key_backup_password_enc))
                .apply()
            if (settings!!.getEncryption() == EncryptionType.KEYSTORE) {
                DatabaseHelper.wipeDatabase(this)
                generateNewEncryptionKey()
            }
            fragment?.updateAutoBackup()
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
                fragment?.setEncryptionKey(null)
                finish()
                return
            }
            encryptionKey = key
            fragment?.setEncryptionKey(key)
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
        startActivityForResult(authIntent, Constants.INTENT_SETTINGS_AUTHENTICATE)
    }

    override fun onSuccessfulEncryptionChange(newEncryptionType: EncryptionType, newEncryptionKey: SecretKey) {
        if (isFinishing() || VaultSession.getKey() == null) return
        if (VaultSession.activate(newEncryptionKey, VaultSession.generation()) !is SessionCommandResult.Applied) return
        encryptionKey = newEncryptionKey
        encryptionChanged = true
        overviewGeneration = VaultSession.generation()

        fragment?.encryption?.value = newEncryptionType.name.lowercase(Locale.ROOT)
        fragment?.setEncryptionKey(newEncryptionKey)
        fragment?.updateAutoBackup()
        publishSettingsOverview()
    }

    override fun onTaskResult(result: ChangeEncryptionTask.Result) {
        when (result.result) {
            EncryptionChangeResult.SUCCESS -> {
                val newType = result.encryptionType
                val newKey = result.newEncryptionKey
                if (newType != null && newKey != null)
                    onSuccessfulEncryptionChange(newType, newKey)
                Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_change_success,
                    BaseTransientBottomBar.LENGTH_SHORT).show()
            }
            EncryptionChangeResult.MISSING_NEW_KEY,
            EncryptionChangeResult.DECRYPTION_FAILED,
            EncryptionChangeResult.TASK_CREATION_FAILED -> {
                Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_change_failed,
                    BaseTransientBottomBar.LENGTH_SHORT).show()
            }
            EncryptionChangeResult.BACKUP_FAILURE -> {
                Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_backup_failed,
                    BaseTransientBottomBar.LENGTH_SHORT).show()
            }
            EncryptionChangeResult.CHANGE_FAILURE -> {
                Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_change_failed,
                    BaseTransientBottomBar.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.backup_toast_location_access_failed, Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (multiple) settings!!.setOpenPGPRecipientFiles(picked)
        else settings!!.setOpenPGPSecretFile(picked.first())
        fragment?.refreshOpenPgpKeyFileSummaries()
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
        val export = try {
            PreferencesExport.export(AndroidPreferencesPort(this, Settings(this)), includeSecrets,
                platform = PreferencesExport.PLATFORM_ANDROID)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.preferences_export_failed, Toast.LENGTH_LONG).show()
            return
        }
        preferencesExportPending?.zero()
        preferencesExportPending = export
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "offlineotp-preferences.json")
        try {
            launchSettingsDocumentPicker(intent, Constants.INTENT_SETTINGS_PREFERENCES_EXPORT)
        } catch (_: RuntimeException) {
            export.zero()
            preferencesExportPending = null
            Toast.makeText(this, R.string.preferences_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun savePreferencesExport(uri: Uri) {
        val pending = preferencesExportPending ?: return
        preferencesExportPending = null
        val saved = try {
            StorageAccessHelper.saveFile(this, uri, pending.bytes, BooleanSupplier { !isFinishing() })
        } catch (_: RuntimeException) {
            false
        } finally {
            pending.zero()
        }
        when {
            !saved -> Toast.makeText(this, R.string.preferences_export_failed, Toast.LENGTH_LONG).show()
            pending.includesSecrets ->
                Toast.makeText(this, R.string.preferences_export_success_with_secrets, Toast.LENGTH_LONG).show()
            else -> Toast.makeText(this,
                getString(R.string.preferences_export_success, pending.keysWritten), Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the picker; the confirmation happens once the file is known to be readable. */
    fun requestPreferencesImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            launchSettingsDocumentPicker(intent, Constants.INTENT_SETTINGS_PREFERENCES_IMPORT)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.preferences_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmPreferencesImport(uri: Uri) {
        val bytes = try {
            getContentResolver().openInputStream(uri).use { BoundedInputReader.read(it, PreferencesExport.MAX_BYTES) }
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.preferences_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        // Validate before asking, so a wrong file is rejected without a dialog the user cannot act on.
        if (!isReadablePreferences(bytes)) {
            bytes.fill(0)
            Toast.makeText(this, R.string.preferences_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        val preview = previewPreferencesImport(bytes, notifyWrongPlatform = false) ?: run {
            bytes.fill(0)
            Toast.makeText(this, R.string.preferences_import_failed, Toast.LENGTH_LONG).show()
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
                val payload = bytes.copyOf()
                closeDialog()
                applyPreferencesImport(payload)
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
    private fun previewPreferencesImport(bytes: ByteArray, notifyWrongPlatform: Boolean = true): PreferencesExport.Import? {
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
            if (notifyWrongPlatform && error is PreferencesExport.WrongPlatformException) {
                Toast.makeText(this, R.string.preferences_import_wrong_platform, Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    private fun isReadablePreferences(bytes: ByteArray): Boolean = previewPreferencesImport(bytes) != null

    private fun applyPreferencesImport(bytes: ByteArray) {
        val result = try {
            PreferencesExport.import(bytes, AndroidPreferencesPort(this, Settings(this)),
                PreferencesExport.PLATFORM_ANDROID)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.preferences_import_failed, Toast.LENGTH_LONG).show()
            return
        } finally {
            bytes.fill(0)
        }
        if (result.includesSecrets) {
            Toast.makeText(this, R.string.preferences_import_success_with_secrets, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.preferences_import_success, result.applied),
                Toast.LENGTH_LONG).show()
        }
        if (result.unknownKeys.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.preferences_import_skipped, result.unknownKeys.size),
                Toast.LENGTH_LONG).show()
        }
        // The screen the user is looking at may now describe settings that changed underneath it.
        fragment?.refreshOpenPgpKeyFileSummaries()
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

        completeSettingsDocumentPicker(requestCode)

        if (requestCode == Constants.INTENT_SETTINGS_AUTHENTICATE) {
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
                    Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_no_key,
                        BaseTransientBottomBar.LENGTH_SHORT).show()
                }
            } else if (resultCode != RESULT_CANCELED && !isFinishing()) {
                Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_auth_failed,
                    BaseTransientBottomBar.LENGTH_SHORT).show()
            }
        } else if (requestCode == Constants.INTENT_SETTINGS_PREFERENCES_EXPORT) {
            if (resultCode == RESULT_OK && data?.data != null) savePreferencesExport(data.data!!)
            else { preferencesExportPending?.zero(); preferencesExportPending = null }
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
                    Toast.makeText(this, R.string.backup_toast_location_access_failed, Toast.LENGTH_LONG).show()
                }
            }
        } else if (requestCode == Constants.INTENT_SETTINGS_OPENPGP_RECIPIENT_FILES ||
                requestCode == Constants.INTENT_SETTINGS_OPENPGP_SECRET_FILE) {
            if (resultCode == RESULT_OK && !isFinishing()) handleOpenPgpKeyFileResult(requestCode, data)
        } else {
            // Handled in OpenPgpKeyPreference
            fragment?.pgpSigningKey?.handleOnActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        settingsOverview?.close()
        settingsOverview = null
        inProgress?.dismiss()
        inProgress = null
        dismissSettingsPrompts()
        prefs?.unregisterOnSharedPreferenceChangeListener(this)
        encryptionKey = null
        fragment?.setEncryptionKey(null)
        prefs = null

        super.onDestroy()
    }

    /** The preference screen itself; encryption changes are forwarded to the activity. */
    open class SettingsFragment : PreferenceFragment() {
        var catUI: PreferenceCategory? = null

        private var settings: Settings? = null

        private var credentialsPreference: CredentialsPreference? = null
        private var encryptionKey: SecretKey? = null
        private var encryptionChangeCallback: EncryptionHelper.EncryptionChangeCallback? = null

        var encryption: ListPreference? = null
        private var useAutoBackup: ListPreference? = null

        var useAndroidSync: CheckBoxPreference? = null

        private var pgpEncryptionKey: EditTextPreference? = null
        private var pgpMode: ListPreference? = null
        private var pgpProviderPreference: OpenPgpAppPreference? = null
        private var pgpSigningKeyPreference: OpenPgpKeyPreference? = null
        private var pgpVerifyPreference: CheckBoxPreference? = null
        private var pgpRecipientFiles: android.preference.Preference? = null
        private var pgpSecretFile: android.preference.Preference? = null

        var pgpSigningKey: OpenPgpKeyPreference? = null
        var themeMode: ListPreference? = null
        var themeBlack: CheckBoxPreference? = null
        var theme: ListPreference? = null

        /** Shows the data-loss warning before the encryption type is switched. */
        fun encryptionChangeWithDialog(encryptionType: EncryptionType) {
            val activity = getActivity() as? SettingsActivity ?: return
            var host: DeleteConfirmationComposeView? = null
            val closeHost = {
                val h = host
                if (activity.settingsConfirmationPrompt === h) activity.settingsConfirmationPrompt = null
                h?.dismiss()
                Unit
            }
            host = DeleteConfirmationComposeView(
                activity = activity,
                title = getString(R.string.settings_dialog_title_warning),
                message = getString(R.string.settings_dialog_msg_encryption_change),
                confirmLabel = getString(android.R.string.ok),
                dismissLabel = getString(android.R.string.cancel),
                onConfirm = {
                    closeHost()
                    if (encryptionType == EncryptionType.PASSWORD)
                        activity.tryEncryptionChangeWithAuth(encryptionType)
                    else if (encryptionType == EncryptionType.KEYSTORE)
                        activity.startEncryptionChangeTask(encryptionType, null)
                },
                onDismiss = closeHost
            )
            activity.showSettingsPrompt(host)
        }

        /** Enables the selector only while the selected backup credentials and location are available. */
        fun updateAutoBackup() {
            val autoBackup = useAutoBackup ?: return
            autoBackup.isEnabled = BackupHelper.autoBackupType(getActivity()!!) == Constants.BackupType.ENCRYPTED

            if (autoBackup.isEnabled) {
                autoBackup.setSummary(R.string.settings_desc_auto_backup_password_enc)
            } else {
                autoBackup.setSummary(R.string.settings_desc_auto_backup_requirements)
            }
        }

        /** Passes the new key on to the credentials preference, if it is already created. */
        fun setEncryptionKey(encryptionKey: SecretKey?) {
            this.encryptionKey = encryptionKey

            credentialsPreference?.setOldEncryptionKey(encryptionKey)
        }

        /** Shows only the preferences belonging to the selected OpenPGP engine. */
        private fun applyOpenPgpMode(mode: OpenPgpMode) {
            val provider = mode == OpenPgpMode.PROVIDER
            pgpProviderPreference?.isEnabled = provider
            pgpSigningKeyPreference?.isEnabled = provider
            pgpVerifyPreference?.isEnabled = provider
            pgpEncryptionKey?.isEnabled = provider && !pgpProviderPreference?.value.isNullOrEmpty()
            pgpRecipientFiles?.isEnabled = !provider
            pgpSecretFile?.isEnabled = !provider
        }

        /** Reports how many recipient files and whether a secret key are selected. */
        fun refreshOpenPgpKeyFileSummaries() {
            val currentSettings = settings ?: return
            val recipients = currentSettings.getOpenPGPRecipientFiles().size
            pgpRecipientFiles?.summary = if (recipients == 0)
                getString(R.string.settings_desc_openpgp_files_unset)
            else getString(R.string.settings_desc_openpgp_recipient_count, recipients)
            pgpSecretFile?.summary = if (currentSettings.getOpenPGPSecretFile().isEmpty())
                getString(R.string.settings_desc_openpgp_files_unset)
            else getString(R.string.settings_desc_openpgp_secret_file)
        }

        /** Passes the callback on to the credentials preference, if it is already created. */
        fun setEncryptionChangeCallback(changeCallback: EncryptionHelper.EncryptionChangeCallback?) {
            encryptionChangeCallback = changeCallback

            credentialsPreference?.setEncryptionChangeCallback(changeCallback)
        }

        public override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            val currentSettings = Settings(getActivity()!!)
            settings = currentSettings

            val sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity()!!.getBaseContext())

            addPreferencesFromResource(R.xml.preferences)

            val credentials = findPreference(getString(R.string.settings_key_auth)) as? CredentialsPreference
            credentialsPreference = credentials
            credentials?.setOldEncryptionKey(encryptionKey)
            credentials?.setEncryptionChangeCallback(encryptionChangeCallback)

            val blockAutofill = findPreference(getString(R.string.settings_key_block_autofill)) as? CheckBoxPreference
            val autoUnlockAfterAutofill =
                findPreference(getString(R.string.settings_key_auto_unlock_after_autofill)) as? CheckBoxPreference
            blockAutofill?.isEnabled = true
            blockAutofill?.setSummary(R.string.settings_desc_block_autofill)
            autoUnlockAfterAutofill?.isEnabled = true
            autoUnlockAfterAutofill?.setSummary(R.string.settings_desc_auto_unlock_after_autofill)

            // Encryption and theme
            catUI = findPreference(getString(R.string.settings_key_cat_ui)) as? PreferenceCategory
            encryption = findPreference(getString(R.string.settings_key_encryption)) as? ListPreference
            themeMode = findPreference(getString(R.string.settings_key_theme_mode)) as? ListPreference
            themeBlack = findPreference(getString(R.string.settings_key_theme_black_auto)) as? CheckBoxPreference
            theme = findPreference(getString(R.string.settings_key_theme)) as? ListPreference

            encryption?.setOnPreferenceChangeListener { _, o ->
                val newEncryption = o as? String ?: return@setOnPreferenceChangeListener false
                val encryptionType = EncryptionType.valueOf(newEncryption.uppercase(Locale.ROOT))
                val oldEncryptionType = currentSettings.getEncryption()
                val authMethod = currentSettings.getAuthMethod()

                if (encryptionType != oldEncryptionType) {
                    if (encryptionType == EncryptionType.PASSWORD) {
                        val settingsActivity = getActivity() as? SettingsActivity ?: return@setOnPreferenceChangeListener false
                        if (authMethod != AuthMethod.PASSWORD) {
                            settingsActivity.showSettingsNotice(
                                R.string.settings_dialog_title_error,
                                R.string.settings_dialog_msg_encryption_invalid_with_auth
                            )
                            return@setOnPreferenceChangeListener false
                        } else {
                            if (currentSettings.getAuthCredentials().isEmpty()) {
                                settingsActivity.showSettingsNotice(
                                    R.string.settings_dialog_title_error,
                                    R.string.settings_dialog_msg_encryption_invalid_without_credentials
                                )
                                return@setOnPreferenceChangeListener false
                            }
                        }

                        encryptionChangeWithDialog(EncryptionType.PASSWORD)
                    } else if (encryptionType == EncryptionType.KEYSTORE) {
                        encryptionChangeWithDialog(EncryptionType.KEYSTORE)
                    }
                }

                false
            }

            // Backup location
            val backupLocation = findPreference(getString(R.string.settings_key_backup_location))

            if (currentSettings.isBackupLocationSet()) {
                backupLocation?.setSummary(R.string.settings_desc_backup_location_set)
            } else {
                backupLocation?.setSummary(R.string.settings_desc_backup_location)
            }

            backupLocation?.setOnPreferenceClickListener {
                (getActivity()!! as SettingsActivity).requestBackupAccess()
                true
            }

            // Portable preferences: the file is plaintext, so the export asks about the backup
            // password before writing and the import says what it will overwrite.
            findPreference(getString(R.string.settings_key_preferences_export))?.setOnPreferenceClickListener {
                (getActivity()!! as SettingsActivity).confirmPreferencesExport()
                true
            }

            findPreference(getString(R.string.settings_key_preferences_import))?.setOnPreferenceClickListener {
                (getActivity()!! as SettingsActivity).requestPreferencesImport()
                true
            }

            findPreference(getString(R.string.settings_key_storage_location))?.setOnPreferenceClickListener {
                (getActivity()!! as SettingsActivity).showStorageLocation()
                true
            }

            // OpenPGP
            val pgpProvider = findPreference(getString(R.string.settings_key_openpgp_provider)) as? OpenPgpAppPreference
            pgpProviderPreference = pgpProvider
            pgpEncryptionKey = findPreference(getString(R.string.settings_key_openpgp_key_encrypt)) as? EditTextPreference
            val signingKey = findPreference(getString(R.string.settings_key_openpgp_key_sign)) as? OpenPgpKeyPreference
            pgpSigningKey = signingKey
            pgpSigningKeyPreference = signingKey
            pgpVerifyPreference = findPreference(getString(R.string.settings_key_openpgp_verify)) as? CheckBoxPreference
            pgpMode = findPreference(getString(R.string.settings_key_openpgp_mode)) as? ListPreference
            pgpRecipientFiles = findPreference(getString(R.string.settings_key_openpgp_recipient_files))
            pgpSecretFile = findPreference(getString(R.string.settings_key_openpgp_secret_file))

            signingKey?.setOpenPgpProvider(pgpProvider?.value)

            pgpEncryptionKey?.isEnabled = !pgpProvider?.value.isNullOrEmpty()

            pgpProvider?.setOnPreferenceChangeListener { _, newValue ->
                pgpEncryptionKey?.isEnabled = !(newValue as? String).isNullOrEmpty()
                signingKey?.setOpenPgpProvider(newValue as? String)

                true
            }

            // The two engines have disjoint settings; showing the inactive ones would suggest they
            // still apply. The stored values are kept so switching back restores the configuration.
            pgpMode?.value = OpenPgpMode.preferenceValue(currentSettings.getOpenPGPMode())
            applyOpenPgpMode(currentSettings.getOpenPGPMode())
            pgpMode?.setOnPreferenceChangeListener { _, newValue ->
                val mode = OpenPgpMode.parse(newValue as? String)
                currentSettings.setOpenPGPMode(mode)
                applyOpenPgpMode(mode)
                true
            }

            pgpRecipientFiles?.setOnPreferenceClickListener {
                (getActivity()!! as SettingsActivity).requestOpenPgpKeyFiles(true)
                true
            }
            pgpSecretFile?.setOnPreferenceClickListener {
                (getActivity()!! as SettingsActivity).requestOpenPgpKeyFiles(false)
                true
            }
            refreshOpenPgpKeyFileSummaries()

            val loginBackup = findPreference(getString(R.string.settings_key_auto_backup_use_login)) as? CheckBoxPreference
            loginBackup?.isChecked = LoginBackupPolicy.usesLoginPassword(getActivity()!!)
            loginBackup?.setOnPreferenceChangeListener { _, value ->
                val oldKey = getString(R.string.settings_key_backup_password)
                val oldPasswordPresent = !sharedPref.getString(oldKey, "").isNullOrEmpty()
                if (value == true && oldPasswordPresent) {
                    val activity = getActivity() as? SettingsActivity ?: return@setOnPreferenceChangeListener false
                    var host: DeleteConfirmationComposeView? = null
                    val closeHost = {
                        val h = host
                        if (activity.settingsConfirmationPrompt === h) activity.settingsConfirmationPrompt = null
                        h?.dismiss()
                        Unit
                    }
                    host = DeleteConfirmationComposeView(
                        activity = activity,
                        title = getString(R.string.settings_dialog_title_remove_old_backup_password),
                        message = getString(R.string.settings_dialog_msg_remove_old_backup_password),
                        confirmLabel = getString(android.R.string.ok),
                        dismissLabel = getString(android.R.string.cancel),
                        onConfirm = {
                            closeHost()
                            if (sharedPref.edit().remove(oldKey).commit()) {
                                loginBackup.isChecked = true
                                updateAutoBackup()
                            } else {
                                Toast.makeText(activity, R.string.settings_toast_remove_old_backup_password_failed, Toast.LENGTH_LONG).show()
                            }
                        },
                        onDismiss = closeHost
                    )
                    activity.showSettingsPrompt(host)
                    false
                } else true
            }
            useAutoBackup = findPreference(getString(R.string.settings_key_auto_backup_password_enc)) as? ListPreference
            updateAutoBackup()

            val backupBroadcasts = findPreference(getString(R.string.settings_key_backup_broadcasts))
            backupBroadcasts?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Set<*> && newValue.isNotEmpty()) {
                    NotificationHelper.requestNotificationPermission(this)
                }
                true
            }

            val androidSync = findPreference(getString(R.string.settings_key_enable_android_backup_service))
                as? CheckBoxPreference
            useAndroidSync = androidSync
            androidSync?.isEnabled = false
            androidSync?.setSummary(R.string.security_platform_backup_disabled)
            if (androidSync?.isEnabled != true)
                androidSync?.isChecked = false

            // Fingerprint quick unlock — opt-in, password-confirmed, device-only vault key.
            val biometricPref = findPreference(getString(R.string.settings_key_biometric_enabled)) as? CheckBoxPreference
            if (biometricPref != null) {
                val isPasswordVault = currentSettings.getEncryption() == EncryptionType.PASSWORD &&
                    currentSettings.getAuthMethod() == com.fairuniversal.offlineotp.Utilities.Constants.AuthMethod.PASSWORD
                biometricPref.isEnabled = isPasswordVault
                biometricPref.isChecked = isPasswordVault && BiometricKeyStore.isEnabled(getActivity()!!) &&
                    BiometricKeyStore.hasStoredKey(getActivity()!!)
                if (!isPasswordVault) {
                    biometricPref.summary = getString(R.string.settings_desc_biometric) + " " +
                        getString(R.string.settings_dialog_msg_encryption_invalid_with_auth)
                } else {
                    biometricPref.summary = getString(R.string.settings_desc_biometric)
                }
                biometricPref.setOnPreferenceChangeListener { _, newValue ->
                    val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                    val activity = getActivity() ?: return@setOnPreferenceChangeListener false
                    if (enable) {
                        if (!isPasswordVault) {
                            Toast.makeText(activity, R.string.settings_dialog_msg_encryption_invalid_with_auth, Toast.LENGTH_LONG).show()
                            return@setOnPreferenceChangeListener false
                        }
                        val avail = BiometricKeyStore.availability(activity)
                        if (!avail.isAvailable) {
                            val msg = if (avail == BiometricKeyStore.Availability.NOT_ENROLLED)
                                R.string.settings_toast_biometric_not_enrolled else R.string.settings_toast_biometric_unavailable
                            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                            return@setOnPreferenceChangeListener false
                        }
                        // Ask for vault password first — an unlocked phone must not be enough to add a shortcut.
                        val settingsActivity = activity as? SettingsActivity ?: return@setOnPreferenceChangeListener false
                        promptBiometricEnrollment(biometricPref, settingsActivity)
                        false
                    } else {
                        try {
                            BiometricKeyStore.clear(activity)
                        } catch (_: Exception) { }
                        Toast.makeText(activity, R.string.settings_toast_biometric_turned_off, Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            }

            if (sharedPref.contains(getString(R.string.settings_key_special_features)) &&
                    sharedPref.getBoolean(getString(R.string.settings_key_special_features), false)) {
                addPreferencesFromResource(R.xml.preferences_special)

                val clearKeyStore = findPreference(getString(R.string.settings_key_clear_keystore))
                clearKeyStore?.setOnPreferenceClickListener {
                    val activity = getActivity() as? SettingsActivity ?: return@setOnPreferenceClickListener false
                    val messageId = when (currentSettings.getEncryption()) {
                        EncryptionType.PASSWORD -> R.string.settings_dialog_msg_clear_keystore_password
                        EncryptionType.KEYSTORE -> R.string.settings_dialog_msg_clear_keystore_keystore
                    }
                    var host: DeleteConfirmationComposeView? = null
                    val closeHost = {
                        val h = host
                        if (activity.settingsConfirmationPrompt === h) activity.settingsConfirmationPrompt = null
                        h?.dismiss()
                        Unit
                    }
                    host = DeleteConfirmationComposeView(
                        activity = activity,
                        title = getString(R.string.settings_dialog_title_clear_keystore),
                        message = getString(messageId),
                        confirmLabel = getString(android.R.string.ok),
                        dismissLabel = getString(android.R.string.cancel),
                        onConfirm = {
                            closeHost()
                            activity.clearKeyStore()
                        },
                        onDismiss = closeHost
                    )
                    activity.showSettingsPrompt(host)
                    false
                }
            }
            // Theme mode needs Android 10+; on older devices hide both options. With mode "auto" the manual
            // theme choice is unused, otherwise the black-theme toggle is.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                catUI?.removePreference(themeMode!!)
                catUI?.removePreference(themeBlack!!)
            } else {
                if (sharedPref.getString(getString(R.string.settings_key_theme_mode),
                        getString(R.string.settings_default_theme_mode)) == "auto")
                    catUI?.removePreference(theme!!)
                else
                    catUI?.removePreference(themeBlack!!)
            }
        }

        private fun promptBiometricEnrollment(pref: CheckBoxPreference, host: SettingsActivity) {
            val ctx = getActivity() ?: return
            val wrongPassword = getString(R.string.settings_toast_biometric_wrong_password)
            val saveFailed = getString(R.string.settings_toast_biometric_save_failed)

            // One shared Compose form for this password prompt on every client. Android keeps the
            // Dialog window, the PBKDF2 verifier and the biometric/keychain write; the form keeps
            // no password after confirmation and renders the same error handling as iOS/desktop.
            val view = BiometricEnableComposeView(
                activity = ctx,
                title = getString(R.string.settings_title_biometric),
                detail = getString(R.string.settings_dialog_biometric_enable),
                passwordLabel = getString(R.string.settings_hint_password),
                confirmLabel = getString(android.R.string.ok),
                cancelLabel = getString(android.R.string.cancel),
                onConfirm = { typed ->
                    if (typed.isEmpty()) {
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
                                        BiometricKeyStore.save(fragmentActivity, vaultKey) { success, error ->
                                            // Wipe the copy after the store has copied it.
                                            try { vaultKey.fill(0) } catch (_: Exception) { }
                                            if (success) {
                                                pref.isChecked = true
                                                Toast.makeText(ctx, R.string.settings_toast_biometric_turned_on,
                                                    Toast.LENGTH_SHORT).show()
                                                host.dismissBiometricPrompt()
                                            } else {
                                                pref.isChecked = false
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
                                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        null
                                    } catch (_: Exception) {
                                        try { vaultKey.fill(0) } catch (_: Exception) { }
                                        saveFailed
                                    }
                                }
                            }
                        }
                    }
                },
                onDismiss = { host.dismissBiometricPrompt() }
            )
            host.dismissBiometricPrompt()
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
    }
}
