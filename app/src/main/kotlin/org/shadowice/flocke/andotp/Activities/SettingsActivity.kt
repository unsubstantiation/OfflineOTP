package org.shadowice.flocke.andotp.Activities

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
import android.preference.Preference
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
import org.shadowice.flocke.andotp.Preferences.CredentialsPreference
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver
import org.shadowice.flocke.andotp.Tasks.ChangeEncryptionTask
import org.shadowice.flocke.andotp.Utilities.BackupHelper
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod
import org.shadowice.flocke.andotp.Utilities.Constants.EncryptionType
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.EncryptionChangeResult
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.NotificationHelper
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.UIHelper
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult

import java.util.Arrays
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

    private var inProgress: AlertDialog? = null
    private var canGoBack = true

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
        if (savedInstanceState != null)
            encryptionChanged = savedInstanceState.getBoolean(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, false)

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
                    UIHelper.showGenericDialog(this,
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
        encryptionKey = null
        fragment?.setEncryptionKey(null)
        cancelBackgroundTask()
        finish()
    }

    /** Generates a new KeyStore key after all keys were wiped. */
    private fun generateNewEncryptionKey() {
        if (settings!!.getEncryption() == EncryptionType.KEYSTORE) {
            val key = KeyStoreHelper.createEncryptionKey(this)
            encryptionKey = key
            if (key == null || DatabaseHelper.changeVaultEncryption(this, null, key, true,
                    { id ->
                        VaultCommitDecision.fromCommit(
                            settings!!.commitVaultSettings(id, EncryptionType.KEYSTORE, null, null, true))
                    }) !is VaultWriteResult.Saved) {
                finish()
                return
            }
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

        fragment?.encryption?.value = newEncryptionType.name.lowercase(Locale.getDefault())
        fragment?.setEncryptionKey(newEncryptionKey)
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
            } else {
                Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_auth_failed,
                    BaseTransientBottomBar.LENGTH_SHORT).show()
            }
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
        } else {
            // Handled in OpenPgpKeyPreference
            fragment?.pgpSigningKey?.handleOnActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        inProgress?.dismiss()
        inProgress = null
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

        var pgpSigningKey: OpenPgpKeyPreference? = null
        var themeMode: ListPreference? = null
        var themeBlack: CheckBoxPreference? = null
        var theme: ListPreference? = null

        /** Shows the data-loss warning before the encryption type is switched. */
        fun encryptionChangeWithDialog(encryptionType: EncryptionType) {
            val builder = AlertDialog.Builder(getActivity()!!)
            builder.setTitle(R.string.settings_dialog_title_warning)
                .setMessage(R.string.settings_dialog_msg_encryption_change)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val activity = getActivity()!! as SettingsActivity
                    if (encryptionType == EncryptionType.PASSWORD)
                        activity.tryEncryptionChangeWithAuth(encryptionType)
                    else if (encryptionType == EncryptionType.KEYSTORE)
                        activity.startEncryptionChangeTask(encryptionType, null)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .create()
                .show()
        }

        /** Enables auto-backup only when a location and a backup password are set. */
        fun updateAutoBackup() {
            val autoBackup = useAutoBackup ?: return
            autoBackup.isEnabled = BackupHelper.autoBackupType(getActivity()!!) == Constants.BackupType.ENCRYPTED
            if (!autoBackup.isEnabled)
                autoBackup.value = Constants.AutoBackup.OFF.toString().lowercase(Locale.ENGLISH)

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
                val encryptionType = EncryptionType.valueOf(newEncryption.uppercase(Locale.getDefault()))
                val oldEncryptionType = currentSettings.getEncryption()
                val authMethod = currentSettings.getAuthMethod()

                if (encryptionType != oldEncryptionType) {
                    if (encryptionType == EncryptionType.PASSWORD) {
                        if (authMethod != AuthMethod.PASSWORD && authMethod != AuthMethod.PIN) {
                            UIHelper.showGenericDialog(getActivity()!!, R.string.settings_dialog_title_error,
                                R.string.settings_dialog_msg_encryption_invalid_with_auth)
                            return@setOnPreferenceChangeListener false
                        } else {
                            if (currentSettings.getAuthCredentials().isEmpty()) {
                                UIHelper.showGenericDialog(getActivity()!!, R.string.settings_dialog_title_error,
                                    R.string.settings_dialog_msg_encryption_invalid_without_credentials)
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

            // OpenPGP
            val pgpProvider = findPreference(getString(R.string.settings_key_openpgp_provider)) as? OpenPgpAppPreference
            pgpEncryptionKey = findPreference(getString(R.string.settings_key_openpgp_key_encrypt)) as? EditTextPreference
            val signingKey = findPreference(getString(R.string.settings_key_openpgp_key_sign)) as? OpenPgpKeyPreference
            pgpSigningKey = signingKey

            signingKey?.setOpenPgpProvider(pgpProvider?.value)

            pgpEncryptionKey?.isEnabled = !pgpProvider?.value.isNullOrEmpty()

            pgpProvider?.setOnPreferenceChangeListener { _, newValue ->
                pgpEncryptionKey?.isEnabled = !(newValue as? String).isNullOrEmpty()
                signingKey?.setOpenPgpProvider(newValue as? String)

                true
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

            if (sharedPref.contains(getString(R.string.settings_key_special_features)) &&
                    sharedPref.getBoolean(getString(R.string.settings_key_special_features), false)) {
                addPreferencesFromResource(R.xml.preferences_special)

                val clearKeyStore = findPreference(getString(R.string.settings_key_clear_keystore))
                clearKeyStore?.setOnPreferenceClickListener {
                    val builder = AlertDialog.Builder(getActivity()!!)

                    builder.setTitle(R.string.settings_dialog_title_clear_keystore)
                    if (currentSettings.getEncryption() == EncryptionType.PASSWORD)
                        builder.setMessage(R.string.settings_dialog_msg_clear_keystore_password)
                    else if (currentSettings.getEncryption() == EncryptionType.KEYSTORE)
                        builder.setMessage(R.string.settings_dialog_msg_clear_keystore_keystore)

                    builder.setPositiveButton(android.R.string.ok) { _, _ ->
                        KeyStoreHelper.wipeKeys(getActivity()!!)
                        if (currentSettings.getEncryption() == EncryptionType.KEYSTORE) {
                            DatabaseHelper.wipeDatabase(getActivity()!!)
                            (getActivity()!! as SettingsActivity).generateNewEncryptionKey()
                        }
                    }
                    builder.setNegativeButton(android.R.string.cancel) { _, _ -> }

                    builder.create().show()
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
    }
}
