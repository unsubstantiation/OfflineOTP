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

import org.openintents.openpgp.IOpenPgpService2
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
import org.shadowice.flocke.andotp.Tasks.PlainTextRestoreTask
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Utilities.BackupHelper
import org.shadowice.flocke.andotp.Utilities.BackupScreenOffPolicy
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.BoundedOutput
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.OpenPgpAvailability
import org.shadowice.flocke.andotp.Utilities.OpenPgpOutputPolicy
import org.shadowice.flocke.andotp.Utilities.OpenPgpRecipientIds
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper
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

/** Backup and restore screen for password-encrypted and OpenPGP backups. */
open class BackupActivity : BackgroundTaskActivity<BackupTaskResult>() {
    private var backupType: Constants.BackupType = Constants.BackupType.ENCRYPTED
    private var encryptionKey: SecretKey? = null
    private var restoreOperation: VaultRestoreOperation? = null

    private var pgpServiceConnection: OpenPgpServiceConnection? = null
    private var pgpServiceProvider: String? = null
    private var pgpServiceReady = false
    private var pgpServiceBinding = false
    private var pgpServiceFailed = false
    private var pgpConnectionRegistered = false
    private var pgpConnectionToken = 0
    private var pgpEncryptionUserIDs: List<String> = emptyList()

    private var encryptTargetFile: Uri? = null
    private var decryptSourceFile: Uri? = null

    private var btnBackup: Button? = null
    private var btnRestore: Button? = null
    private var btnRestorePlainText: Button? = null
    private var txtBackupLabel: TextView? = null
    private var txtBackupWarning: TextView? = null
    private var swReplace: SwitchMaterial? = null
    private var chkOldFormat: CheckBox? = null
    private var progressBackup: ProgressBar? = null
    private var progressRestore: ProgressBar? = null

    private var reload = false
    private var allowExit = true
    private var pendingPickerRequestCode: Int? = null
    private var lockedWhilePicker = false
    private var pendingResumeRequestCode: Int? = null
    private var pendingResumeUri: Uri? = null
    private var pendingResumeReplace = false
    private var activeActionRequestCode: Int? = null
    private var activeActionUri: Uri? = null
    private var activeActionReplace = false
    private var waitingForPassword = false

    private var currentTask: BackupTaskResult.ResultType = BackupTaskResult.ResultType.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingPickerRequestCode = savedInstanceState?.getInt(STATE_PENDING_PICKER_REQUEST, INVALID_REQUEST_CODE)
            ?.takeIf { it != INVALID_REQUEST_CODE }
        lockedWhilePicker = savedInstanceState?.getBoolean(STATE_LOCKED_WHILE_PICKER, false) == true
        if (pendingPickerRequestCode != null && VaultSession.getKey() == null) lockedWhilePicker = true
        if (savedInstanceState != null) {
            pendingResumeRequestCode = savedInstanceState.getInt(STATE_PENDING_RESUME_REQUEST, INVALID_REQUEST_CODE)
                .takeIf { it != INVALID_REQUEST_CODE }
            pendingResumeUri = savedInstanceState.getParcelable(STATE_PENDING_RESUME_URI)
            pendingResumeReplace = savedInstanceState.getBoolean(STATE_PENDING_RESUME_REPLACE, false)
            activeActionRequestCode = savedInstanceState.getInt(STATE_ACTIVE_ACTION, INVALID_REQUEST_CODE)
                .takeIf { it != INVALID_REQUEST_CODE }
            activeActionUri = savedInstanceState.getParcelable(STATE_ACTIVE_URI)
            activeActionReplace = savedInstanceState.getBoolean(STATE_ACTIVE_REPLACE, false)
            waitingForPassword = savedInstanceState.getBoolean(STATE_WAITING_FOR_PASSWORD, false)
            encryptTargetFile = savedInstanceState.getParcelable(STATE_PGP_ENCRYPT_TARGET)
            decryptSourceFile = savedInstanceState.getParcelable(STATE_PGP_DECRYPT_SOURCE)
            reload = savedInstanceState.getBoolean(STATE_RELOAD, false)
        } else {
            pendingResumeRequestCode = getIntent()?.getIntExtra(Constants.EXTRA_BACKUP_PENDING_ACTION, INVALID_REQUEST_CODE)
                ?.takeIf { it != INVALID_REQUEST_CODE }
            pendingResumeUri = getIntent()?.data
            pendingResumeReplace = getIntent()?.getBooleanExtra(Constants.EXTRA_BACKUP_PENDING_REPLACE, false) == true
        }
        if (waitingForPassword && pendingResumeRequestCode == null) {
            pendingResumeRequestCode = activeActionRequestCode
            pendingResumeUri = activeActionUri
            pendingResumeReplace = activeActionReplace
        }
        if (VaultSession.getKey() == null && pendingPickerRequestCode == null &&
            activeActionRequestCode != null && activeActionUri != null) {
            forwardDeferredAction(activeActionRequestCode!!, activeActionUri, activeActionReplace)
            return
        }

        setTitle(R.string.backup_activity_title)
        setContentView(R.layout.activity_container)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        setSupportActionBar(toolbar)

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.content_backup)
        val v = stub.inflate()

        encryptionKey = VaultSession.getKey()
        if (encryptionKey == null && !(lockedWhilePicker && pendingPickerRequestCode != null)) {
            finish()
            return
        }
        if (encryptionKey != null) restoreOperation = VaultRestoreOperation(this)

        val spBackupType = requireNotNull(v.findViewById<Spinner>(R.id.backupType))
        btnBackup = requireNotNull(v.findViewById<Button>(R.id.buttonBackup))
        btnRestore = requireNotNull(v.findViewById<Button>(R.id.buttonRestore))
        btnRestorePlainText = requireNotNull(v.findViewById<Button>(R.id.buttonRestorePlainText))
        txtBackupLabel = requireNotNull(v.findViewById<TextView>(R.id.backupLabel))
        txtBackupWarning = requireNotNull(v.findViewById<TextView>(R.id.backupErrorLabel))
        swReplace = requireNotNull(v.findViewById<SwitchMaterial>(R.id.backup_replace))
        chkOldFormat = requireNotNull(v.findViewById<CheckBox>(R.id.restoreOldCrypt))
        progressBackup = requireNotNull(v.findViewById<ProgressBar>(R.id.progressBarBackup))
        progressRestore = requireNotNull(v.findViewById<ProgressBar>(R.id.progressBarRestore))

        val resumeType = pendingResumeRequestCode?.let(::backupTypeForPickerRequest)
        if (encryptionKey != null) {
            setupBackupType(resumeType ?: settings!!.getDefaultBackupType(), persistSelection = resumeType == null)
            spBackupType.setSelection(backupType.ordinal)
        } else {
            txtBackupWarning?.setText(R.string.backup_desc_vault_locked_picker)
            txtBackupWarning?.visibility = View.VISIBLE
            spBackupType.isEnabled = false
            btnBackup?.isEnabled = false
            btnRestore?.isEnabled = false
            btnRestorePlainText?.isEnabled = false
        }

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

        btnRestorePlainText?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.backup_plaintext_warning_title)
                .setMessage(R.string.backup_plaintext_warning_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.backup_button_restore_plaintext) { _, _ ->
                    showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_PLAIN)
                }
                .show()
        }

        if (encryptionKey != null) tryResumePendingPickerAction()
    }

    /** Shows the controls and warnings for the selected backup type and remembers the choice. */
    private fun setupBackupType(type: Constants.BackupType, persistSelection: Boolean = true) {
        backupType = type
        if (persistSelection) settings!!.setDefaultBackupType(type)

        when (type) {
            Constants.BackupType.ENCRYPTED -> {
                releaseOpenPgpService()
                txtBackupLabel?.setText(R.string.backup_label_crypt)
                chkOldFormat?.visibility = View.VISIBLE
                txtBackupWarning?.visibility = View.GONE
                btnBackup?.isEnabled = true
                btnRestore?.isEnabled = true
            }
            Constants.BackupType.OPEN_PGP -> {
                txtBackupLabel?.setText(R.string.backup_label_pgp)
                chkOldFormat?.visibility = View.GONE
                pgpEncryptionUserIDs = OpenPgpRecipientIds.parse(settings!!.getOpenPGPEncryptionUserIDs())
                val provider = settings!!.getOpenPGPProvider().trim()
                if (provider.isEmpty()) {
                    releaseOpenPgpService()
                    txtBackupWarning?.setText(R.string.backup_desc_openpgp_provider)
                } else {
                    bindOpenPgpService(provider)
                }
                updateOpenPgpAvailability()
            }
            Constants.BackupType.UNAVAILABLE -> {
                releaseOpenPgpService()
                btnBackup?.isEnabled = false
                btnRestore?.isEnabled = false
            }
        }
    }

    /** Keeps one provider binding per activity; action buttons stay disabled until it is ready. */
    private fun bindOpenPgpService(provider: String) {
        val current = pgpServiceConnection
        if (current != null && pgpServiceProvider == provider) {
            if (current.isBound()) {
                pgpServiceReady = true
                pgpServiceBinding = false
                pgpServiceFailed = false
            } else if (!pgpServiceBinding && !pgpServiceFailed) {
                pgpServiceBinding = true
                pgpConnectionRegistered = true
                current.bindToService()
            }
            return
        }

        releaseOpenPgpService()
        val token = ++pgpConnectionToken
        pgpServiceProvider = provider
        pgpServiceBinding = true
        pgpConnectionRegistered = true
        val connection = OpenPgpServiceConnection(getApplicationContext(), provider,
            object : OpenPgpServiceConnection.OnBound {
                override fun onBound(service: IOpenPgpService2) {
                    if (token != pgpConnectionToken || isFinishing()) return
                    pgpServiceReady = service != null
                    pgpServiceBinding = false
                    pgpServiceFailed = !pgpServiceReady
                    updateOpenPgpAvailability()
                    btnBackup?.post { tryResumePendingPickerAction() }
                }

                override fun onError(error: Exception) {
                    if (token != pgpConnectionToken || isFinishing()) return
                    pgpConnectionRegistered = false
                    pgpServiceReady = false
                    pgpServiceBinding = false
                    pgpServiceFailed = true
                    updateOpenPgpAvailability()
                    val pendingRequest = pendingResumeRequestCode
                    if (pendingRequest != null &&
                        backupTypeForPickerRequest(pendingRequest) == Constants.BackupType.OPEN_PGP) {
                        if (activeActionRequestCode == pendingRequest) discardActiveBackupDocument()
                        clearPendingResumeAction(discardCreatedDocument = true)
                        notifyBackupState(R.string.backup_toast_openpgp_unavailable)
                    }
                }
            })
        pgpServiceConnection = connection
        connection.bindToService()
    }

    private fun releaseOpenPgpService() {
        pgpConnectionToken++
        if (pgpConnectionRegistered) {
            try {
                pgpServiceConnection?.unbindFromService()
            } catch (_: RuntimeException) {
                // The provider may have failed before bindService registered a connection.
            }
        }
        pgpConnectionRegistered = false
        pgpServiceConnection = null
        pgpServiceProvider = null
        pgpServiceReady = false
        pgpServiceBinding = false
        pgpServiceFailed = false
    }

    /** Restore does not need recipients; encryption does. Never enable either before a live service. */
    private fun updateOpenPgpAvailability() {
        if (backupType != Constants.BackupType.OPEN_PGP || isFinishing()) return
        val provider = settings!!.getOpenPGPProvider().trim()
        val connectionReady = pgpServiceConnection?.isBound() == true
        if (provider.isEmpty()) {
            txtBackupWarning?.setText(R.string.backup_desc_openpgp_provider)
            txtBackupWarning?.visibility = View.VISIBLE
            btnBackup?.isEnabled = false
            btnRestore?.isEnabled = false
            return
        }
        if (connectionReady) {
            pgpServiceReady = true
            pgpServiceBinding = false
            pgpServiceFailed = false
        }
        val availability = OpenPgpAvailability.evaluate(
            providerConfigured = true,
            serviceReady = pgpServiceReady,
            recipientCount = pgpEncryptionUserIDs.size
        )
        if (!availability.canRestore) {
            txtBackupWarning?.setText(if (pgpServiceFailed) R.string.backup_desc_openpgp_unavailable
                else R.string.backup_desc_openpgp_connecting)
            txtBackupWarning?.visibility = View.VISIBLE
            btnBackup?.isEnabled = false
            btnRestore?.isEnabled = false
            return
        }
        btnRestore?.isEnabled = availability.canRestore
        btnBackup?.isEnabled = availability.canEncrypt
        if (!availability.canEncrypt) {
            txtBackupWarning?.setText(R.string.backup_desc_openpgp_keyid)
            txtBackupWarning?.visibility = View.VISIBLE
        } else {
            txtBackupWarning?.visibility = View.GONE
        }
    }

    private fun requireOpenPgpService(): IOpenPgpService2? {
        val service = if (pgpServiceReady) pgpServiceConnection?.getService() else null
        if (service == null) {
            pgpServiceReady = false
            pgpServiceBinding = false
            pgpServiceFailed = true
            updateOpenPgpAvailability()
            notifyBackupState(R.string.backup_toast_openpgp_unavailable)
        }
        return service
    }

    private fun setActiveAction(requestCode: Int, uri: Uri, replace: Boolean) {
        activeActionRequestCode = requestCode
        activeActionUri = uri
        activeActionReplace = replace
        waitingForPassword = false
        encryptTargetFile = null
        decryptSourceFile = null
    }

    private fun clearActiveAction() {
        activeActionRequestCode = null
        activeActionUri = null
        activeActionReplace = false
        waitingForPassword = false
        encryptTargetFile = null
        decryptSourceFile = null
    }

    private fun clearPendingResumeAction(discardCreatedDocument: Boolean) {
        val requestCode = pendingResumeRequestCode
        val uri = pendingResumeUri
        if (discardCreatedDocument && requestCode != null && isSaveDocumentRequest(requestCode))
            StorageAccessHelper.deleteContentFile(this, uri)
        pendingResumeRequestCode = null
        pendingResumeUri = null
        pendingResumeReplace = false
    }

    /** A CREATE_DOCUMENT result is a newly created file, so remove it when its backup is canceled. */
    private fun discardActiveBackupDocument() {
        val requestCode = activeActionRequestCode
        val uri = activeActionUri
        if (requestCode != null && isSaveDocumentRequest(requestCode))
            StorageAccessHelper.deleteContentFile(this, uri)
        if (requestCode == pendingResumeRequestCode && uri == pendingResumeUri)
            clearPendingResumeAction(discardCreatedDocument = false)
        clearActiveAction()
    }

    private fun backupTypeForPickerRequest(requestCode: Int): Constants.BackupType? = when (requestCode) {
        Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT,
        Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD,
        Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT -> Constants.BackupType.ENCRYPTED
        Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP,
        Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP -> Constants.BackupType.OPEN_PGP
        Constants.INTENT_BACKUP_OPEN_DOCUMENT_PLAIN -> Constants.BackupType.ENCRYPTED
        else -> null
    }

    private fun tryResumePendingPickerAction() {
        val requestCode = pendingResumeRequestCode ?: return
        if (!canOperate()) return
        val uri = pendingResumeUri
        if (uri == null || uri.getScheme() != "content") {
            pendingResumeRequestCode = null
            pendingResumeUri = null
            notifyBackupState(if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT ||
                requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP)
                R.string.backup_toast_file_creation_failed else R.string.backup_toast_import_failed)
            finishWithResult()
            return
        }
        val replace = pendingResumeReplace
        setActiveAction(requestCode, uri, replace)
        swReplace?.isChecked = replace
        if (backupTypeForPickerRequest(requestCode) == Constants.BackupType.OPEN_PGP &&
            pgpServiceConnection?.isBound() != true) return

        pendingResumeRequestCode = null
        pendingResumeUri = null
        pendingResumeReplace = false
        when (requestCode) {
            Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT -> doRestoreCrypt(uri, false)
            Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD -> doRestoreCrypt(uri, true)
            Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP -> restoreEncryptedWithPGP(uri, null)
            Constants.INTENT_BACKUP_OPEN_DOCUMENT_PLAIN -> restorePlainText(uri)
            Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT -> doBackupCrypt(uri)
            Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP -> backupEncryptedWithPGP(uri, null)
            else -> {
                discardActiveBackupDocument()
                notifyBackupState(R.string.backup_toast_import_failed)
            }
        }
    }

    private fun isSaveDocumentRequest(requestCode: Int): Boolean =
        requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT ||
            requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP

    private fun retainSelectedUriPermission(result: Intent?, uri: Uri?) {
        if (result == null || uri == null || uri.getScheme() != "content") return
        val persistable = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val resultFlags = result.getFlags()
        if (resultFlags and persistable == 0) return
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val takeFlags = resultFlags and readWrite
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags)
        } catch (_: RuntimeException) {
            // ACTION_GET_CONTENT providers need not grant persistable access; the live app grant
            // still covers the same-package continuation while the process remains alive.
        }
    }

    private fun forwardLockedPickerResult(requestCode: Int, resultCode: Int, result: Intent?) {
        val uri = if (resultCode == RESULT_OK) result?.data else null
        if (uri != null && uri.getScheme() == "content") retainSelectedUriPermission(result, uri)
        forwardDeferredAction(requestCode, uri, swReplace?.isChecked == true)
    }

    private fun forwardDeferredAction(requestCode: Int, uri: Uri?, replace: Boolean) {
        val data = Intent().putExtra("reload", reload)
        if (uri != null && uri.getScheme() == "content") {
            data.data = uri
            data.putExtra(Constants.EXTRA_BACKUP_PENDING_ACTION, requestCode)
            data.putExtra(Constants.EXTRA_BACKUP_PENDING_REPLACE, replace)
        }
        clearActiveAction()
        clearPendingResumeAction(discardCreatedDocument = false)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_PICKER_REQUEST, pendingPickerRequestCode ?: INVALID_REQUEST_CODE)
        outState.putBoolean(STATE_LOCKED_WHILE_PICKER, lockedWhilePicker)
        outState.putInt(STATE_PENDING_RESUME_REQUEST, pendingResumeRequestCode ?: INVALID_REQUEST_CODE)
        outState.putParcelable(STATE_PENDING_RESUME_URI, pendingResumeUri)
        outState.putBoolean(STATE_PENDING_RESUME_REPLACE, pendingResumeReplace)
        outState.putInt(STATE_ACTIVE_ACTION, activeActionRequestCode ?: INVALID_REQUEST_CODE)
        outState.putParcelable(STATE_ACTIVE_URI, activeActionUri)
        outState.putBoolean(STATE_ACTIVE_REPLACE, activeActionReplace)
        outState.putBoolean(STATE_WAITING_FOR_PASSWORD, waitingForPassword)
        outState.putParcelable(STATE_PGP_ENCRYPT_TARGET, encryptTargetFile)
        outState.putParcelable(STATE_PGP_DECRYPT_SOURCE, decryptSourceFile)
        outState.putBoolean(STATE_RELOAD, reload)
    }

    /** Finishes the activity, reporting to MainActivity whether entries had to be reloaded. */
    fun finishWithResult() {
        clearActiveAction()
        clearPendingResumeAction(discardCreatedDocument = true)
        val data = Intent()
        data.putExtra("reload", reload)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (allowExit) {
            discardActiveBackupDocument()
            finishWithResult()
        }

        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (allowExit) {
            discardActiveBackupDocument()
            finishWithResult()
            super.onBackPressed()
        }
    }

    public override fun onDestroy() {
        encryptionKey = null
        releaseOpenPgpService()
        super.onDestroy()
    }

    override fun requiresVaultSession(): Boolean =
        !(lockedWhilePicker && pendingPickerRequestCode != null)

    override fun onVaultLocked() {
        encryptionKey = null
        cancelBackgroundTask()
        if (pendingPickerRequestCode != null) {
            // Keep only the pending request code while the external picker finishes. The URI and
            // action are handed to MainActivity, which re-authenticates before restarting work.
            lockedWhilePicker = true
            txtBackupWarning?.setText(R.string.backup_desc_vault_locked_picker)
            txtBackupWarning?.visibility = View.VISIBLE
            btnBackup?.isEnabled = false
            btnRestore?.isEnabled = false
            btnRestorePlainText?.isEnabled = false
            return
        }
        val activeCode = activeActionRequestCode
        val activeUri = activeActionUri
        if (activeCode != null && activeUri != null) {
            // A password dialog, OpenPGP round trip, or in-flight task can also be interrupted by
            // a relock. Resume the same file only after MainActivity authenticates again.
            forwardDeferredAction(activeCode, activeUri, activeActionReplace)
            return
        }
        val resumeCode = pendingResumeRequestCode
        val resumeUri = pendingResumeUri
        if (resumeCode != null && resumeUri != null) {
            forwardDeferredAction(resumeCode, resumeUri, pendingResumeReplace)
            return
        }
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
        val isBackup = activeActionRequestCode?.let(::isSaveDocumentRequest) == true ||
            currentTask == BackupTaskResult.ResultType.BACKUP
        discardActiveBackupDocument()
        notifyBackupState(if (isBackup) R.string.backup_toast_export_failed
            else R.string.backup_toast_import_failed)
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
            discardActiveBackupDocument()
            val failureMessage = result.messageId.takeIf { it != 0 } ?: R.string.backup_toast_export_failed
            notifyBackupState(failureMessage)
        }
    }

    private fun handleRestoreTaskResult(result: BackupTaskResult) {
        if (result.success) {
            if (result.isPGP) {
                val service = requireOpenPgpService() ?: run {
                    clearActiveAction()
                    return
                }
                val input = ByteArrayInputStream(result.payload!!.toByteArray(StandardCharsets.UTF_8))
                val os = BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES)

                val api = OpenPgpApi(this, service)
                val resultIntent: Intent
                try {
                    resultIntent = api.executeApi(result.decryptIntent!!, input, os)
                } catch (error: RuntimeException) {
                    clearActiveAction()
                    notifyBackupState(R.string.backup_toast_import_failed)
                    return
                }
                handleOpenPGPResult(resultIntent, os, result.uri, Constants.INTENT_BACKUP_DECRYPT_PGP)
            } else {
                if (restoreEntries(result.payload!!, false)) finishWithResult()
                else clearActiveAction()
            }
        } else {
            clearActiveAction()
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
        btnRestorePlainText?.isEnabled = !running
    }

    private fun showBackupProgress(running: Boolean) {
        toggleInProgressMode(running)
        progressBackup?.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun showRestoreProgress(running: Boolean) {
        toggleInProgressMode(running)
        progressRestore?.visibility = if (running) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (pendingPickerRequestCode == requestCode) {
            pendingPickerRequestCode = null
            val selectedUri = if (resultCode == RESULT_OK) intent?.data else null
            retainSelectedUriPermission(intent, selectedUri)
            if (lockedWhilePicker || !canOperate()) {
                forwardLockedPickerResult(requestCode, resultCode, intent)
                return
            }
            lockedWhilePicker = false
            if (resultCode == RESULT_OK && selectedUri?.getScheme() != "content") {
                notifyBackupState(if (isSaveDocumentRequest(requestCode))
                    R.string.backup_toast_file_creation_failed else R.string.backup_toast_import_failed)
                return
            }
            if (selectedUri != null) setActiveAction(requestCode, selectedUri, swReplace?.isChecked == true)
        }

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
        } else if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_PLAIN && resultCode == RESULT_OK) {
            if (intent != null)
                restorePlainText(intent.data)
        } else if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP && resultCode == RESULT_OK) {
            if (intent != null)
                backupEncryptedWithPGP(intent.data, null)
        } else if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP && resultCode == RESULT_OK) {
            backupEncryptedWithPGP(encryptTargetFile, intent)
        } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP && resultCode == RESULT_OK) {
            restoreEncryptedWithPGP(decryptSourceFile, intent)
        } else if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP ||
            requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
            notifyOpenPgpFailure(requestCode)
        }
    }

    /* Generic functions for all backup/restore options */

    private fun showOpenFileSelector(intentId: Int) {
        val openDocument = Intent(Intent.ACTION_OPEN_DOCUMENT)
        openDocument.addCategory(Intent.CATEGORY_OPENABLE)
        openDocument.setType("*/*")
        openDocument.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        pendingPickerRequestCode = intentId

        try {
            startActivityForResult(openDocument, intentId)
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Failed to use ACTION_OPEN_DOCUMENT, no matching activity found!")
        } catch (e: SecurityException) {
            Log.d(TAG, "ACTION_OPEN_DOCUMENT is not available in this profile")
        }

        val getContent = Intent(Intent.ACTION_GET_CONTENT)
        getContent.addCategory(Intent.CATEGORY_OPENABLE)
        getContent.setType("*/*")
        getContent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(getContent, intentId)
        } catch (e: ActivityNotFoundException) {
            pendingPickerRequestCode = null
            Log.d(TAG, "Failed to use ACTION_GET_CONTENT, no matching activity found!")
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            pendingPickerRequestCode = null
            Log.d(TAG, "ACTION_GET_CONTENT is not available in this profile")
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
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            pendingPickerRequestCode = intentId
            try {
                startActivityForResult(intent, intentId)
            } catch (error: ActivityNotFoundException) {
                pendingPickerRequestCode = null
                // Some Work Profiles / custom ROMs have no ACTION_CREATE_DOCUMENT handler.
                Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
            } catch (error: SecurityException) {
                pendingPickerRequestCode = null
                Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
            }
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

    /** Imports an explicitly selected plain-text backup; no corresponding export path exists. */
    private fun restorePlainText(uri: Uri?) {
        if (!canOperate()) return
        startBackgroundTask(PlainTextRestoreTask(this, uri), BackupTaskResult.ResultType.RESTORE)
    }

    /* Encrypted backup functions */

    private fun doRestoreCrypt(uri: Uri?, oldFormat: Boolean) {
        if (!canOperate()) return
        val password = settings!!.getBackupPasswordEnc()

        if (password.isEmpty()) {
            waitingForPassword = true
            val pwDialog = PasswordEntryDialog(this, PasswordEntryDialog.Mode.ENTER,
                settings!!.getBlockAccessibility(), settings!!.getBlockAutofill(),
                PasswordEntryDialog.PasswordEnteredCallback { newPassword ->
                    waitingForPassword = false
                    doRestoreCryptWithPassword(uri, newPassword, oldFormat)
                }, PasswordEntryDialog.PasswordCanceledCallback {
                    waitingForPassword = false
                    discardActiveBackupDocument()
                })
            pwDialog.show()
        } else {
            doRestoreCryptWithPassword(uri, password, oldFormat)
        }
    }

    private fun doRestoreCryptWithPassword(uri: Uri?, password: String, oldFormat: Boolean) {
        if (!canOperate()) return
        // ACTION_OPEN_DOCUMENT returns a SAF content URI. It remains usable for cloud and work
        // profile providers even when legacy shared external storage is not mounted.
        val task = EncryptedRestoreTask(this, uri, password, oldFormat)
        startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE)
    }

    private fun doBackupCrypt(uri: Uri?) {
        if (!canOperate()) return
        val password = settings!!.getBackupPasswordEnc()

        if (password.isEmpty()) {
            waitingForPassword = true
            val pwDialog = PasswordEntryDialog(this, PasswordEntryDialog.Mode.UPDATE,
                settings!!.getBlockAccessibility(), settings!!.getBlockAutofill(),
                PasswordEntryDialog.PasswordEnteredCallback { newPassword ->
                    waitingForPassword = false
                    doBackupCryptWithPassword(uri, newPassword)
                }, PasswordEntryDialog.PasswordCanceledCallback {
                    waitingForPassword = false
                    discardActiveBackupDocument()
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
            discardActiveBackupDocument()
            notifyBackupState(R.string.backup_toast_export_failed)
        }
    }

    private fun restoreEncryptedWithPGP(uri: Uri?, decryptIntent: Intent?) {
        if (!canOperate()) return
        if (requireOpenPgpService() == null) {
            clearActiveAction()
            return
        }
        val intent = decryptIntent ?: Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY)

        val task = PGPRestoreTask(this, uri, intent)
        startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE)
    }

    private fun doBackupEncrypted(uri: Uri?, data: String) {
        if (!canOperate()) return
        // The destination is a SAF content URI, not a raw external-storage path. Cloud providers
        // and managed profiles are valid destinations even when shared storage is not mounted.
        val task = PGPBackupTask(this, data, uri)
        startBackgroundTask(task, BackupTaskResult.ResultType.BACKUP)
    }

    private fun backupEncryptedWithPGP(uri: Uri?, encryptIntent: Intent?) {
        if (!canOperate()) return
        val service = requireOpenPgpService() ?: run {
            discardActiveBackupDocument()
            return
        }
        if (pgpEncryptionUserIDs.isEmpty()) {
            discardActiveBackupDocument()
            updateOpenPgpAvailability()
            notifyBackupState(R.string.backup_toast_openpgp_no_recipients)
            return
        }
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
            discardActiveBackupDocument()
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

            intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, pgpEncryptionUserIDs.toTypedArray())
            intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        } else {
            intent = encryptIntent
        }

        val input = ByteArrayInputStream(plainJSON.toByteArray(StandardCharsets.UTF_8))
        val os = BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES)
        val api = OpenPgpApi(this, service)
        val result: Intent
        try {
            result = api.executeApi(intent, input, os)
        } catch (error: RuntimeException) {
            discardActiveBackupDocument()
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
        if (!canOperate()) return
        if (result == null || (os is BoundedOutput && os.overflowed())) {
            notifyOpenPgpFailure(requestCode)
            return
        }

        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> {
                if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP) {
                    val encryptedBackup = os?.let { outputStreamToString(it) } ?: ""
                    if (!OpenPgpOutputPolicy.hasContent(encryptedBackup)) {
                        discardActiveBackupDocument()
                        notifyBackupState(R.string.backup_toast_openpgp_empty)
                        return
                    }
                    doBackupEncrypted(file, encryptedBackup)
                } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                    val restoredJSON = os?.let { outputStreamToString(it) } ?: ""
                    if (!OpenPgpOutputPolicy.hasContent(restoredJSON)) {
                        clearActiveAction()
                        notifyBackupState(R.string.backup_toast_import_failed)
                        return
                    }
                    if (settings!!.getOpenPGPVerify()) {
                        val sigResult = result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE)
                        if (sigResult != null && sigResult.getResult() == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED) {
                            if (!restoreEntries(restoredJSON, true)) clearActiveAction()
                        } else {
                            clearActiveAction()
                            Toast.makeText(this, R.string.backup_toast_openpgp_not_verified, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        if (!restoreEntries(restoredJSON, true)) clearActiveAction()
                    }
                } else {
                    notifyOpenPgpFailure(requestCode)
                }
            }
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                val pi = result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
                if (pi == null) {
                    notifyOpenPgpFailure(requestCode)
                    return
                }

                // Preserve the target URI across the OpenPGP user-interaction round trip.
                if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP) {
                    encryptTargetFile = file
                } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                    decryptSourceFile = file
                }

                try {
                    startIntentSenderForResult(pi.intentSender, requestCode, null, 0, 0, 0)
                } catch (error: IntentSender.SendIntentException) {
                    notifyOpenPgpFailure(requestCode)
                }
            }
            OpenPgpApi.RESULT_CODE_ERROR -> {
                val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                if (error != null) {
                    discardActiveBackupDocument()
                    Toast.makeText(this,
                        String.format(getString(R.string.backup_toast_openpgp_error), error.getMessage()),
                        Toast.LENGTH_LONG).show()
                } else {
                    notifyOpenPgpFailure(requestCode)
                }
            }
            else -> notifyOpenPgpFailure(requestCode)
        }
    }

    /** Shows the generic export or import failure for an OpenPGP request that could not proceed. */
    private fun notifyOpenPgpFailure(requestCode: Int) {
        discardActiveBackupDocument()
        notifyBackupState(if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP)
            R.string.backup_toast_export_failed else R.string.backup_toast_import_failed)
    }

    override fun shouldDestroyOnScreenOff(): Boolean {
        return BackupScreenOffPolicy.shouldFinish(
            allowExit = allowExit,
            pickerPending = pendingPickerRequestCode != null,
            actionPending = activeActionRequestCode != null,
            resumePending = pendingResumeRequestCode != null
        )
    }

    companion object {
        private val TAG: String = BackupActivity::class.java.simpleName
        private const val STATE_PENDING_PICKER_REQUEST = "pending_picker_request"
        private const val STATE_LOCKED_WHILE_PICKER = "locked_while_picker"
        private const val STATE_PENDING_RESUME_REQUEST = "pending_resume_request"
        private const val STATE_PENDING_RESUME_URI = "pending_resume_uri"
        private const val STATE_PENDING_RESUME_REPLACE = "pending_resume_replace"
        private const val STATE_ACTIVE_ACTION = "active_action"
        private const val STATE_ACTIVE_URI = "active_uri"
        private const val STATE_ACTIVE_REPLACE = "active_replace"
        private const val STATE_WAITING_FOR_PASSWORD = "waiting_for_password"
        private const val STATE_PGP_ENCRYPT_TARGET = "pgp_encrypt_target"
        private const val STATE_PGP_DECRYPT_SOURCE = "pgp_decrypt_source"
        private const val STATE_RELOAD = "reload"
        private const val INVALID_REQUEST_CODE = -1
    }
}
