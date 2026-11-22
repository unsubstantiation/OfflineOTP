package com.fairuniversal.offlineotp.Activities

import android.app.AlertDialog
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
import com.fairuniversal.offlineotp.ui.host.BiometricEnableComposeView
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import com.fairuniversal.offlineotp.ui.host.EntryCopyComposeView
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

    /** The view the snackbars attach to: the settings fragment's view, or the window while it is absent. */
    private fun snackbarAnchor(): View = fragment?.getView() ?: getWindow().decorView
    private var prefs: SharedPreferences? = null

    private var encryptionKey: SecretKey? = null
    private var authenticationGeneration: Long = -1
    private var encryptionChanged = false
    private var clearingKeyStore = false

    private var inProgress: AlertDialog? = null
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.settings_activity_title)
        setContentView(R.layout.activity_container)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        setSupportActionBar(toolbar)

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.inflate()

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
            .replace(R.id.container_content, settingsFragment)
            .commit()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs = sharedPreferences
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, encryptionChanged)
        outState.putLong(STATE_AUTH_GENERATION, authenticationGeneration)
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
    }

    override fun requiresVaultSession(): Boolean = true

    override fun onVaultLocked() {
        if (clearingKeyStore) return
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

        fragment?.encryption?.value = newEncryptionType.name.lowercase(Locale.ROOT)
        fragment?.setEncryptionKey(newEncryptionKey)
        fragment?.updateAutoBackup()
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
                val progressView = getLayoutInflater().inflate(R.layout.dialog_encryption_progress, null, false)
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.settings_activity_title)
                    .setView(progressView)
                    .setCancelable(false)
                    .create()
                inProgress = dialog
                dialog.show()
            }
        } else {
            inProgress?.dismiss()
            inProgress = null
            canGoBack = true
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
            startActivityForResult(intent, Constants.INTENT_SETTINGS_BACKUP_LOCATION)
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
            startActivityForResult(intent, requestCode)
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
        var host: EntryCopyComposeView? = null
        val closeHost = {
            val h = host
            if (settingsConfirmationPrompt === h) settingsConfirmationPrompt = null
            h?.dismiss()
            Unit
        }
        host = EntryCopyComposeView(
            activity = this,
            title = getString(R.string.settings_title_preferences_export),
            cancelLabel = getString(android.R.string.cancel),
            fields = listOf(
                getString(R.string.preferences_export_with_secrets),
                getString(R.string.preferences_export_without_secrets)
            ),
            message = getString(R.string.preferences_export_question) + "\n\n" +
                getString(R.string.preferences_export_question_detail),
            onSelect = { index ->
                closeHost()
                if (index == 0) {
                    startPreferencesExport(includeSecrets = true)
                } else {
                    startPreferencesExport(includeSecrets = false)
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
            startActivityForResult(intent, Constants.INTENT_SETTINGS_PREFERENCES_EXPORT)
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
            startActivityForResult(intent, Constants.INTENT_SETTINGS_PREFERENCES_IMPORT)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

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
