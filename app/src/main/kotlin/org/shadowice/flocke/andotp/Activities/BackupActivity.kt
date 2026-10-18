package org.shadowice.flocke.andotp.Activities

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.widget.Toolbar

import com.google.android.material.switchmaterial.SwitchMaterial

import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Dialogs.PasswordEntryDialog
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask
import org.shadowice.flocke.andotp.Tasks.EncryptedRestoreTask
import org.shadowice.flocke.andotp.Tasks.PGPBackupTask
import org.shadowice.flocke.andotp.Tasks.PGPRestoreTask
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Utilities.BackupHelper
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.BoundedOutput
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultRestoreOperation
import org.shadowice.flocke.andotp.Utilities.VaultSession

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.function.BooleanSupplier

import javax.crypto.SecretKey

/**
 * Backup/restore screen for password-encrypted and OpenPGP backups.
 *
 * Converted from the Java class of the same name. The file selection, the OpenPGP handshake, the
 * bounded document I/O and the restore merge are unchanged.
 *
 * Documented deltas: the layout children are resolved with `requireNotNull`, the Java
 * `outputStreamToString`/`handleOpenPGPResult` helpers stay public because they were, and the
 * platform's protected `onDestroy()` keeps the public visibility the Java class gave it.
 */
open class BackupActivity : BackgroundTaskActivity<BackupTaskResult>() {
    private var backupType: Constants.BackupType = Constants.BackupType.ENCRYPTED
    private var encryptionKey: SecretKey? = null
    private var restoreOperation: VaultRestoreOperation? = null

    private var pgpServiceConnection: OpenPgpServiceConnection? = null
    private var pgpEncryptionUserIDs: String? = null

    private var encryptTargetFile: Uri? = null
    private var decryptSourceFile: Uri? = null

    private var btnBackup: Button? = null
    private var btnRestore: Button? = null
    private var txtBackupLabel: TextView? = null
    private var txtBackupWarning: TextView? = null
    private var swReplace: SwitchMaterial? = null
    private var chkOldFormat: CheckBox? = null
    private var progressBackup: ProgressBar? = null
    private var progressRestore: ProgressBar? = null

    private var reload = false
    private var allowExit = true

    private var currentTask: BackupTaskResult.ResultType = BackupTaskResult.ResultType.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.backup_activity_title)
        setContentView(R.layout.activity_container)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        setSupportActionBar(toolbar)

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.content_backup)
        val v = stub.inflate()

        encryptionKey = VaultSession.getKey()
        if (encryptionKey == null) {
            finish()
            return
        }
        restoreOperation = VaultRestoreOperation(this)

        val spBackupType = requireNotNull(v.findViewById<Spinner>(R.id.backupType))
        btnBackup = requireNotNull(v.findViewById<Button>(R.id.buttonBackup))
        btnRestore = requireNotNull(v.findViewById<Button>(R.id.buttonRestore))
        txtBackupLabel = requireNotNull(v.findViewById<TextView>(R.id.backupLabel))
        txtBackupWarning = requireNotNull(v.findViewById<TextView>(R.id.backupErrorLabel))
        swReplace = requireNotNull(v.findViewById<SwitchMaterial>(R.id.backup_replace))
        chkOldFormat = requireNotNull(v.findViewById<CheckBox>(R.id.restoreOldCrypt))
        progressBackup = requireNotNull(v.findViewById<ProgressBar>(R.id.progressBarBackup))
        progressRestore = requireNotNull(v.findViewById<ProgressBar>(R.id.progressBarRestore))

        setupBackupType(settings!!.getDefaultBackupType())
        spBackupType.setSelection(backupType.ordinal)

        spBackupType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selectedView: View?, i: Int, selectedId: Long) {
                setupBackupType(Constants.BackupType.values()[i])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnBackup?.setOnClickListener {
            when (backupType) {
                Constants.BackupType.ENCRYPTED ->
                    showSaveFileSelector(Constants.BACKUP_MIMETYPE_CRYPT, Constants.BackupType.ENCRYPTED, Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT)
                Constants.BackupType.OPEN_PGP ->
                    showSaveFileSelector(Constants.BACKUP_MIMETYPE_PGP, Constants.BackupType.OPEN_PGP, Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP)
                Constants.BackupType.UNAVAILABLE -> Unit
            }
        }

        btnRestore?.setOnClickListener {
            when (backupType) {
                Constants.BackupType.ENCRYPTED ->
                    if (chkOldFormat?.isChecked == true)
                        showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD)
                    else
                        showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT)
                Constants.BackupType.OPEN_PGP ->
                    showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP)
                Constants.BackupType.UNAVAILABLE -> Unit
            }
        }
    }

    /** Shows the controls and warnings for the selected backup type and remembers the choice. */
    private fun setupBackupType(type: Constants.BackupType) {
        when (type) {
            Constants.BackupType.ENCRYPTED -> {
                txtBackupLabel?.setText(R.string.backup_label_crypt)

                chkOldFormat?.visibility = View.VISIBLE
                txtBackupWarning?.visibility = View.GONE

                btnBackup?.isEnabled = true
                btnRestore?.isEnabled = true
            }
            Constants.BackupType.OPEN_PGP -> {
                txtBackupLabel?.setText(R.string.backup_label_pgp)

                chkOldFormat?.visibility = View.GONE

                val pgpProvider = settings!!.getOpenPGPProvider()
                pgpEncryptionUserIDs = settings!!.getOpenPGPEncryptionUserIDs()

                if (TextUtils.isEmpty(pgpProvider)) {
                    txtBackupWarning?.setText(R.string.backup_desc_openpgp_provider)
                    txtBackupWarning?.visibility = View.VISIBLE

                    btnBackup?.isEnabled = false
                    btnRestore?.isEnabled = false
                } else if (TextUtils.isEmpty(pgpEncryptionUserIDs)) {
                    txtBackupWarning?.setText(R.string.backup_desc_openpgp_keyid)
                    txtBackupWarning?.visibility = View.VISIBLE

                    btnBackup?.isEnabled = false
                    btnRestore?.isEnabled = false
                } else {
                    txtBackupWarning?.visibility = View.GONE

                    btnBackup?.isEnabled = true
                    btnRestore?.isEnabled = true

                    val connection = OpenPgpServiceConnection(getApplicationContext(), pgpProvider)
                    pgpServiceConnection = connection
                    connection.bindToService()
                }
            }
            Constants.BackupType.UNAVAILABLE -> Unit
        }

        backupType = type
        settings!!.setDefaultBackupType(type)
    }

    /** Finishes the activity, reporting to MainActivity whether entries had to be reloaded. */
    fun finishWithResult() {
        val data = Intent()
        data.putExtra("reload", reload)
        setResult(RESULT_OK, data)
        finish()
    }

    // Go back to the main activity
    override fun onSupportNavigateUp(): Boolean {
        if (allowExit)
            finishWithResult()

        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (allowExit) {
            finishWithResult()
            super.onBackPressed()
        }
    }

    /**
     * The Java class widened the platform's protected `onDestroy()` to public, so the public
     * visibility is kept here; the body is unchanged.
     */
    public override fun onDestroy() {
        encryptionKey = null
        super.onDestroy()

        pgpServiceConnection?.unbindFromService()
    }

    override fun requiresVaultSession(): Boolean = true

    override fun onVaultLocked() {
        encryptionKey = null
        cancelBackgroundTask()
        finish()
    }

    private fun canOperate(): Boolean {
        return !isFinishing() && encryptionKey != null && VaultSession.getKey() === encryptionKey
    }

    private fun notifyBackupState(msgId: Int, detail: String? = null) {
        val message = getString(msgId)
        val text = if (detail.isNullOrBlank()) message else "$message\n$detail"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onTaskFailure(error: TaskFailure) {
        notifyBackupState(if (currentTask == BackupTaskResult.ResultType.RESTORE)
            R.string.backup_toast_import_failed else R.string.backup_toast_export_failed)
    }

    private fun readOwnedEntries(): ArrayList<Entry> {
        val result = DatabaseHelper.readVault(this, encryptionKey)
        if (result !is VaultReadResult.Loaded)
            throw IllegalStateException("Vault unavailable")
        return result.takeEntries()
    }

    override fun onTaskResult(result: BackupTaskResult) {
        if (!canOperate()) return
        if (result.resultType == BackupTaskResult.ResultType.BACKUP)
            handleBackupTaskResult(result)
        else if (result.resultType == BackupTaskResult.ResultType.RESTORE)
            handleRestoreTaskResult(result)
    }

    private fun handleBackupTaskResult(result: BackupTaskResult) {
        if (result.success) {
            val successMessage = result.messageId.takeIf { it != 0 } ?: R.string.backup_toast_export_success
            notifyBackupState(successMessage, result.payload)
            finishWithResult()
        } else {
            val failureMessage = result.messageId.takeIf { it != 0 } ?: R.string.backup_toast_export_failed
            notifyBackupState(failureMessage)
        }
    }

    private fun handleRestoreTaskResult(result: BackupTaskResult) {
        if (result.success) {
            if (result.isPGP) {
                val input = ByteArrayInputStream(result.payload!!.toByteArray(StandardCharsets.UTF_8))
                val os = BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES)

                val api = OpenPgpApi(this, pgpServiceConnection?.getService())
                val resultIntent: Intent
                try {
                    resultIntent = api.executeApi(result.decryptIntent!!, input, os)
                } catch (error: RuntimeException) {
                    notifyBackupState(R.string.backup_toast_import_failed)
                    return
                }
                handleOpenPGPResult(resultIntent, os, result.uri, Constants.INTENT_BACKUP_DECRYPT_PGP)
            } else {
                if (restoreEntries(result.payload!!, false)) finishWithResult()
            }
        } else {
            if (result.messageId != 0)
                notifyBackupState(result.messageId)
            else
                notifyBackupState(R.string.backup_toast_import_failed)
        }
    }

    override fun setupUiForTaskState(running: Boolean) {
        if (currentTask == BackupTaskResult.ResultType.BACKUP)
            showBackupProgress(running)
        else if (currentTask == BackupTaskResult.ResultType.RESTORE)
            showRestoreProgress(running)
    }

    /** Starts a task and remembers which progress indicator belongs to it. */
    protected fun startBackgroundTask(task: UiBasedBackgroundTask<BackupTaskResult>, type: BackupTaskResult.ResultType) {
        currentTask = type
        startBackgroundTask(task)
    }

    private fun toggleInProgressMode(running: Boolean) {
        allowExit = !running

        btnBackup?.isEnabled = !running
        btnRestore?.isEnabled = !running
        chkOldFormat?.isEnabled = !running
        swReplace?.isEnabled = !running
    }

    private fun showBackupProgress(running: Boolean) {
        toggleInProgressMode(running)
        progressBackup?.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun showRestoreProgress(running: Boolean) {
        toggleInProgressMode(running)
        progressRestore?.visibility = if (running) View.VISIBLE else View.GONE
    }

    // Results from external activities
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT && resultCode == RESULT_OK) {
            if (intent != null) {
                doRestoreCrypt(intent.data, false)
            }
        } else if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD && resultCode == RESULT_OK) {
            if (intent != null) {
                doRestoreCrypt(intent.data, true)
            }
        } else if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT && resultCode == RESULT_OK) {
            if (intent != null) {
                doBackupCrypt(intent.data)
            }
        } else if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP && resultCode == RESULT_OK) {
            if (intent != null)
                restoreEncryptedWithPGP(intent.data, null)
        } else if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP && resultCode == RESULT_OK) {
            if (intent != null)
                backupEncryptedWithPGP(intent.data, null)
        } else if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP && resultCode == RESULT_OK) {
            backupEncryptedWithPGP(encryptTargetFile, intent)
        } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP && resultCode == RESULT_OK) {
            restoreEncryptedWithPGP(decryptSourceFile, intent)
        }
    }

    /* Generic functions for all backup/restore options */

    private fun showOpenFileSelector(intentId: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")

        try {
            startActivityForResult(intent, intentId)
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Failed to use ACTION_OPEN_DOCUMENT, no matching activity found!")
        }

        intent.setAction(Intent.ACTION_GET_CONTENT)

        try {
            startActivityForResult(intent, intentId)
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Failed to use ACTION_GET_CONTENT, no matching activity found!")
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Uses the system file picker, or backs up straight to the configured location when "ask" is off. */
    private fun showSaveFileSelector(mimeType: String, backupType: Constants.BackupType, intentId: Int) {
        if (settings!!.getBackupAsk()) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType(mimeType)
            intent.putExtra(Intent.EXTRA_TITLE, BackupHelper.backupFilename(this, backupType))
            startActivityForResult(intent, intentId)
        } else {
            if (settings!!.isBackupLocationSet()) {
                if (intentId == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT) {
                    doBackupCrypt(null)
                } else if (intentId == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP) {
                    backupEncryptedWithPGP(null, null)
                }
            } else {
                Toast.makeText(this, R.string.backup_toast_no_location, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Adds the restored entries (replacing the current ones if selected) and saves the database. */
    private fun restoreEntries(text: String, finish: Boolean): Boolean {
        if (!canOperate()) return false
        val result = restoreOperation!!.restore(text, swReplace?.isChecked == true,
            encryptionKey, VaultSession.generation(), BooleanSupplier { canOperate() })
        if (result == VaultRestoreOperation.Result.SAVED) {
            reload = true
            notifyBackupState(R.string.backup_toast_import_success)
            if (finish) finishWithResult()
            return true
        }
        if (result == VaultRestoreOperation.Result.EMPTY_IMPORT)
            notifyBackupState(R.string.backup_toast_import_no_entries)
        else if (result == VaultRestoreOperation.Result.SAVE_FAILED
            || result == VaultRestoreOperation.Result.SESSION_REVOKED
            || result == VaultRestoreOperation.Result.RECOVERY_REQUIRED)
            notifyBackupState(R.string.backup_toast_import_save_failed)
        else
            notifyBackupState(R.string.backup_toast_import_failed)
        return false
    }

    /* Encrypted backup functions */

    private fun doRestoreCrypt(uri: Uri?, oldFormat: Boolean) {
        val password = settings!!.getBackupPasswordEnc()

        if (password.isEmpty()) {
            val pwDialog = PasswordEntryDialog(this, PasswordEntryDialog.Mode.ENTER,
                settings!!.getBlockAccessibility(), settings!!.getBlockAutofill(),
                PasswordEntryDialog.PasswordEnteredCallback { newPassword ->
                    doRestoreCryptWithPassword(uri, newPassword, oldFormat)
                })
            pwDialog.show()
        } else {
            doRestoreCryptWithPassword(uri, password, oldFormat)
        }
    }

    private fun doRestoreCryptWithPassword(uri: Uri?, password: String, oldFormat: Boolean) {
        if (Tools.isExternalStorageReadable()) {
            val task = EncryptedRestoreTask(this, uri, password, oldFormat)
            startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE)
        } else {
            Toast.makeText(this, R.string.backup_toast_storage_not_accessible, Toast.LENGTH_LONG).show()
        }
    }

    private fun doBackupCrypt(uri: Uri?) {
        val password = settings!!.getBackupPasswordEnc()

        if (password.isEmpty()) {
            val pwDialog = PasswordEntryDialog(this, PasswordEntryDialog.Mode.UPDATE,
                settings!!.getBlockAccessibility(), settings!!.getBlockAutofill(),
                PasswordEntryDialog.PasswordEnteredCallback { newPassword ->
                    doBackupCryptWithPassword(uri, newPassword)
                })
            pwDialog.show()
        } else {
            doBackupCryptWithPassword(uri, password)
        }
    }

    private fun doBackupCryptWithPassword(uri: Uri?, password: String) {
        if (!canOperate()) return
        try {
            val entries = readOwnedEntries()
            try {
                startBackgroundTask(EncryptedBackupTask(this, entries, password, uri), BackupTaskResult.ResultType.BACKUP)
            } finally {
                for (entry in entries) entry.destroy()
            }
        } catch (error: RuntimeException) {
            notifyBackupState(R.string.backup_toast_export_failed)
        }
    }

    private fun restoreEncryptedWithPGP(uri: Uri?, decryptIntent: Intent?) {
        val intent = decryptIntent ?: Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY)

        val task = PGPRestoreTask(this, uri, intent)
        startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE)
    }

    private fun doBackupEncrypted(uri: Uri?, data: String) {
        if (Tools.isExternalStorageWritable()) {
            val task = PGPBackupTask(this, data, uri)
            startBackgroundTask(task, BackupTaskResult.ResultType.BACKUP)
        } else {
            Toast.makeText(this, R.string.backup_toast_storage_not_accessible, Toast.LENGTH_LONG).show()
        }
    }

    private fun backupEncryptedWithPGP(uri: Uri?, encryptIntent: Intent?) {
        if (!canOperate()) return
        val entries: ArrayList<Entry>
        val plainJSON: String
        try {
            entries = readOwnedEntries()
            try {
                plainJSON = DatabaseHelper.entriesToString(entries)
            } finally {
                for (entry in entries) entry.destroy()
            }
        } catch (error: RuntimeException) {
            notifyBackupState(R.string.backup_toast_export_failed)
            return
        }

        val intent: Intent
        if (encryptIntent == null) {
            intent = Intent()

            if (settings!!.getOpenPGPSigningKey() != 0L) {
                intent.setAction(OpenPgpApi.ACTION_SIGN_AND_ENCRYPT)
                intent.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, settings!!.getOpenPGPSigningKey())
            } else {
                intent.setAction(OpenPgpApi.ACTION_ENCRYPT)
            }

            intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, pgpEncryptionUserIDs!!.split(",").toTypedArray())
            intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        } else {
            intent = encryptIntent
        }

        val input = ByteArrayInputStream(plainJSON.toByteArray(StandardCharsets.UTF_8))
        val os = BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES)
        val api = OpenPgpApi(this, pgpServiceConnection?.getService())
        val result: Intent
        try {
            result = api.executeApi(intent, input, os)
        } catch (error: RuntimeException) {
            notifyBackupState(R.string.backup_toast_export_failed)
            return
        }
        handleOpenPGPResult(result, os, uri, Constants.INTENT_BACKUP_ENCRYPT_PGP)
    }

    /** Reads the OpenPGP API's output stream as UTF-8 text. */
    fun outputStreamToString(os: ByteArrayOutputStream): String {
        return String(os.toByteArray(), StandardCharsets.UTF_8)
    }

    /** Handles the OpenPGP API result; a pending user interaction is passed to the OpenPGP app. */
    fun handleOpenPGPResult(result: Intent?, os: ByteArrayOutputStream?, file: Uri?, requestCode: Int) {
        if (!canOperate() || result == null || (os is BoundedOutput && os.overflowed())) {
            notifyBackupState(R.string.backup_toast_import_failed)
            return
        }
        if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_SUCCESS) {
            if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP) {
                if (os != null)
                    doBackupEncrypted(file, outputStreamToString(os))
            } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                if (os != null) {
                    if (settings!!.getOpenPGPVerify()) {
                        val sigResult = result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE)

                        if (sigResult != null && sigResult.getResult() == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED) {
                            restoreEntries(outputStreamToString(os), true)
                        } else {
                            Toast.makeText(this, R.string.backup_toast_openpgp_not_verified, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        restoreEntries(outputStreamToString(os), true)
                    }
                }
            }
        } else if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED) {
            val pi = result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)

            // Without the pending intent the user cannot be asked to act, so report the failure
            if (pi == null) {
                notifyOpenPgpFailure(requestCode)
                return
            }

            // Small hack to keep the target file even after user interaction
            if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP) {
                encryptTargetFile = file
            } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                decryptSourceFile = file
            }

            try {
                startIntentSenderForResult(pi.intentSender, requestCode, null, 0, 0, 0)
            } catch (e: IntentSender.SendIntentException) {
                e.printStackTrace()
            }
        } else if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_ERROR) {
            val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)

            if (error != null)
                Toast.makeText(this, String.format(getString(R.string.backup_toast_openpgp_error), error.getMessage()), Toast.LENGTH_LONG).show()
            else
                notifyOpenPgpFailure(requestCode)
        } else {
            notifyOpenPgpFailure(requestCode)
        }
    }

    /** Shows the generic export or import failure for an OpenPGP request that could not proceed. */
    private fun notifyOpenPgpFailure(requestCode: Int) {
        notifyBackupState(if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP)
            R.string.backup_toast_export_failed else R.string.backup_toast_import_failed)
    }

    override fun shouldDestroyOnScreenOff(): Boolean {
        return allowExit   // Don't destroy the backup activity as long as a backup task is running
    }

    companion object {
        private val TAG: String = BackupActivity::class.java.simpleName
    }
}
