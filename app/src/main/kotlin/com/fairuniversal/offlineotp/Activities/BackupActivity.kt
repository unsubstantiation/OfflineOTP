package com.fairuniversal.offlineotp.Activities

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.ViewGroup
import android.widget.Toast

import androidx.appcompat.widget.Toolbar


import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.openpgp.RpgpOpenPgpPort
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Tasks.BackupTaskResult
import com.fairuniversal.offlineotp.Tasks.EncryptedBackupTask
import com.fairuniversal.offlineotp.Tasks.EncryptedRestoreTask
import com.fairuniversal.offlineotp.Tasks.OpenPgpKeyFileBackupTask
import com.fairuniversal.offlineotp.Tasks.OpenPgpKeyFileRestoreTask
import com.fairuniversal.offlineotp.Tasks.PGPBackupTask
import com.fairuniversal.offlineotp.Tasks.PGPRestoreTask
import com.fairuniversal.offlineotp.Tasks.TaskFailure
import com.fairuniversal.offlineotp.Tasks.TaskOutcome
import com.fairuniversal.offlineotp.Tasks.UiBasedBackgroundTask
import com.fairuniversal.offlineotp.Utilities.BackupHelper
import com.fairuniversal.offlineotp.Utilities.BackupScreenOffPolicy
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import com.fairuniversal.offlineotp.Utilities.BoundedOutput
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.DatabaseHelper
import com.fairuniversal.offlineotp.Utilities.OpenPgpAvailability
import com.fairuniversal.offlineotp.Utilities.OpenPgpKeyFilePolicy
import com.fairuniversal.offlineotp.Utilities.OpenPgpMode
import com.fairuniversal.offlineotp.Utilities.OpenPgpOutputPolicy
import com.fairuniversal.offlineotp.Utilities.OpenPgpRecipientIds
import com.fairuniversal.offlineotp.Utilities.StorageAccessHelper
import com.fairuniversal.offlineotp.Utilities.VaultReadResult
import com.fairuniversal.offlineotp.Utilities.VaultRestoreOperation
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.ui.host.BiometricEnableComposeView
import com.fairuniversal.offlineotp.ui.host.BackupOverviewComposeView
import com.fairuniversal.offlineotp.ui.portable.PortableToolGroup
import com.fairuniversal.offlineotp.ui.portable.PortableToolItem
import com.fairuniversal.offlineotp.ui.host.RestoreModeComposeView
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.EncryptedExportComposeView
import com.fairuniversal.offlineotp.ui.host.EncryptedRestorePasswordComposeView
import com.fairuniversal.offlineotp.ui.host.EntryCopyComposeView
import com.fairuniversal.offlineotp.ui.host.OpenPgpExportComposeView

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    private var backupOverview: BackupOverviewComposeView? = null
    private var backupEnabled = false
    private var restoreEnabled = false
    private var backupDescription = ""
    private var backupWarning: String? = null
    private var replaceExisting = false
    private var activeRestoreModeDialog: ComposeDialogHost? = null
    private var activePasswordDialog: ComposeDialogHost? = null
    private var activeKeyFileDialog: ComposeDialogHost? = null
    internal fun currentKeyFileDialog(): ComposeDialogHost? = activeKeyFileDialog
    private val openPgpPort by lazy { RpgpOpenPgpPort() }
    private val keyFileBackendAvailable by lazy { openPgpPort.isBackendAvailable() }

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
            replaceExisting = savedInstanceState.getBoolean(STATE_REPLACE_MODE, false)
            savedInstanceState.getString(STATE_CURRENT_TASK)?.let { savedTask ->
                currentTask = try {
                    BackupTaskResult.ResultType.valueOf(savedTask)
                } catch (_: IllegalArgumentException) {
                    BackupTaskResult.ResultType.NONE
                }
            }
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
        toolbar.visibility = View.GONE // Shared Compose owns title/navigation.

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.content_backup)
        val v = stub.inflate()

        encryptionKey = VaultSession.getKey()
        if (encryptionKey == null && !(lockedWhilePicker && pendingPickerRequestCode != null)) {
            finish()
            return
        }
        if (encryptionKey != null) restoreOperation = VaultRestoreOperation(this)

        val requestedTool = if (savedInstanceState == null) requestedVaultTool() else null
        val resumeType = pendingResumeRequestCode?.let(::backupTypeForPickerRequest)
            ?: requestedTool?.let(::backupTypeForPickerRequest)
        if (encryptionKey != null) {
            setupBackupType(resumeType ?: settings!!.getDefaultBackupType(), persistSelection = resumeType == null)
        } else {
            backupWarning = getString(R.string.backup_desc_vault_locked_picker)
            backupEnabled = false
            restoreEnabled = false
        }

        val overviewGeneration = VaultSession.generation()
        val overview = BackupOverviewComposeView(this) { key ->
            if (VaultSession.isCurrent(overviewGeneration)) dispatchBackupOverviewAction(key)
        }
        overview.onBack = { onSupportNavigateUp() }
        backupOverview = overview
        (v as ViewGroup).addView(overview, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        publishBackupOverview()
        if (encryptionKey != null) tryResumePendingPickerAction()
        if (requestedTool != null && pendingPickerRequestCode == null && pendingResumeRequestCode == null && canOperate()) {
            // A shortcut opens the normal picker; all password/key-file/provider checks remain in
            // the existing result path. OpenPGP provider setup may still require the Backup screen.
            when (requestedTool) {
                Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT,
                Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP -> chooseRestoreMode(requestedTool)
                Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT -> showSaveFileSelector(
                    Constants.BACKUP_MIMETYPE_CRYPT, Constants.BackupType.ENCRYPTED, requestedTool)
                Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP -> showSaveFileSelector(
                    Constants.BACKUP_MIMETYPE_PGP, Constants.BackupType.OPEN_PGP, requestedTool)
            }
        }
    }

    /** Non-secret projection of the existing engine/provider state into the shared page. */
    private fun publishBackupOverview() {
        val view = backupOverview ?: return
        val ready = canOperate() && allowExit && pendingPickerRequestCode == null
        val formatNames = resources.getStringArray(R.array.backup_list_type_names)
        val formats = listOf(Constants.BackupType.ENCRYPTED, Constants.BackupType.OPEN_PGP).map { type ->
            val label = formatNames.getOrNull(type.ordinal) ?: type.name
            PortableToolItem(key = "format:" + type.name, label = label,
                value = if (type == backupType) "Selected" else "", enabled = ready)
        }
        view.render(
            title = getString(R.string.backup_activity_title),
            description = backupDescription,
            groups = listOf(
                PortableToolGroup(getString(R.string.backup_label_type), formats),
                PortableToolGroup("", listOf(
                    PortableToolItem(key = "backup", label = getString(R.string.backup_button_backup),
                        enabled = ready && backupEnabled),
                    PortableToolItem(key = "restore", label = getString(R.string.backup_button_restore),
                        enabled = ready && restoreEnabled)
                ))
            ),
            warning = backupWarning.orEmpty(),
            busy = !allowExit
        )
    }

    /** Revalidate current session/availability rather than trusting a previously displayed frame. */
    private fun dispatchBackupOverviewAction(key: String) {
        if (!canOperate() || !allowExit || pendingPickerRequestCode != null) return
        when (key) {
            "format:ENCRYPTED" -> {
                setupBackupType(Constants.BackupType.ENCRYPTED)
            }
            "format:OPEN_PGP" -> {
                setupBackupType(Constants.BackupType.OPEN_PGP)
            }
            "backup" -> if (backupEnabled) when (backupType) {
                Constants.BackupType.ENCRYPTED -> showSaveFileSelector(Constants.BACKUP_MIMETYPE_CRYPT,
                    Constants.BackupType.ENCRYPTED, Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT)
                Constants.BackupType.OPEN_PGP -> showSaveFileSelector(Constants.BACKUP_MIMETYPE_PGP,
                    Constants.BackupType.OPEN_PGP, Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP)
                Constants.BackupType.UNAVAILABLE -> Unit
            }
            "restore" -> if (restoreEnabled) when (backupType) {
                Constants.BackupType.ENCRYPTED -> chooseRestoreMode(Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT)
                Constants.BackupType.OPEN_PGP -> chooseRestoreMode(Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP)
                Constants.BackupType.UNAVAILABLE -> Unit
            }
        }
        publishBackupOverview()
    }

    private fun requestedVaultTool(): Int? {
        val request = getIntent() ?: return null
        val generation = request.getStringExtra(Constants.EXTRA_VAULT_TOOL_GENERATION)?.toLongOrNull() ?: return null
        if (!VaultSession.isCurrent(generation) || VaultSession.getKey() == null || isFinishing()) return null
        return request.getIntExtra(Constants.EXTRA_VAULT_TOOL_ACTION, INVALID_REQUEST_CODE)
            .takeIf { backupTypeForPickerRequest(it) != null }
    }

    /** Which OpenPGP engine the user selected; read fresh so a Settings change takes effect. */
    private fun openPgpMode(): OpenPgpMode = settings!!.getOpenPGPMode()

    private fun usesKeyFileMode(): Boolean = openPgpMode() == OpenPgpMode.KEY_FILE

    /**
     * Offers the in-app engine's actions only when the native backend and the needed key documents
     * are present. The buttons stay disabled with a specific reason otherwise, mirroring the
     * provider mode rather than failing after the user has picked a destination.
     */
    private fun updateKeyFileAvailability() {
        try {
            if (backupType != Constants.BackupType.OPEN_PGP || isFinishing()) return
            val recipients = settings!!.getOpenPGPRecipientFiles()
            val secretConfigured = settings!!.getOpenPGPSecretFile().isNotEmpty()
            val availability = OpenPgpKeyFilePolicy.availability(
                backendAvailable = keyFileBackendAvailable,
                recipientCount = recipients.size,
                secretKeyConfigured = secretConfigured
            )
            backupEnabled = availability.canEncrypt
            restoreEnabled = availability.canRestore
            val warning = when {
                !keyFileBackendAvailable ->
                    R.string.backup_desc_openpgp_backend_unavailable
                !availability.canEncrypt -> R.string.backup_desc_openpgp_keyfiles_recipients
                !availability.canRestore -> R.string.backup_desc_openpgp_keyfiles_secret
                else -> 0
            }
            if (warning == 0) {
                backupWarning = null
            } else {
                backupWarning = getString(warning)
            }
        } finally { publishBackupOverview() }
    }

    /** Shows the controls and warnings for the selected backup type and remembers the choice. */
    private fun setupBackupType(type: Constants.BackupType, persistSelection: Boolean = true) {
        try {
            backupType = type
            if (persistSelection) settings!!.setDefaultBackupType(type)

            when (type) {
                Constants.BackupType.ENCRYPTED -> {
                    releaseOpenPgpService()
                    backupDescription = getString(R.string.backup_label_crypt)
                    backupWarning = null
                    backupEnabled = true
                    restoreEnabled = true
                }
                Constants.BackupType.OPEN_PGP -> {
                    if (usesKeyFileMode()) {
                        // No provider is involved, so do not hold a binding to one.
                        releaseOpenPgpService()
                        backupDescription = getString(R.string.backup_label_pgp_keyfiles)
                        updateKeyFileAvailability()
                        return
                    }
                    backupDescription = getString(R.string.backup_label_pgp)
                    pgpEncryptionUserIDs = OpenPgpRecipientIds.parse(settings!!.getOpenPGPEncryptionUserIDs())
                    val provider = settings!!.getOpenPGPProvider().trim()
                    if (provider.isEmpty()) {
                        releaseOpenPgpService()
                        backupWarning = getString(R.string.backup_desc_openpgp_provider)
                    } else {
                        bindOpenPgpService(provider)
                    }
                    updateOpenPgpAvailability()
                }
                Constants.BackupType.UNAVAILABLE -> {
                    releaseOpenPgpService()
                    backupEnabled = false
                    restoreEnabled = false
                }
            }
        } finally { publishBackupOverview() }
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
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (token == pgpConnectionToken && !isFinishing()) tryResumePendingPickerAction()
                    }
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
                        showBackupFeedback(R.string.backup_toast_openpgp_unavailable)
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
        try {
            if (backupType != Constants.BackupType.OPEN_PGP || isFinishing()) return
            if (usesKeyFileMode()) {
                updateKeyFileAvailability()
                return
            }
            val provider = settings!!.getOpenPGPProvider().trim()
            val connectionReady = pgpServiceConnection?.isBound() == true
            if (provider.isEmpty()) {
                backupWarning = getString(R.string.backup_desc_openpgp_provider)
                backupEnabled = false
                restoreEnabled = false
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
                backupWarning = getString(if (pgpServiceFailed) R.string.backup_desc_openpgp_unavailable
                    else R.string.backup_desc_openpgp_connecting)
                backupEnabled = false
                restoreEnabled = false
                return
            }
            restoreEnabled = availability.canRestore
            backupEnabled = availability.canEncrypt
            if (!availability.canEncrypt) {
                backupWarning = getString(R.string.backup_desc_openpgp_keyid)
            } else {
                backupWarning = null
            }
        } finally { publishBackupOverview() }
    }

    private fun requireOpenPgpService(): IOpenPgpService2? {
        val service = if (pgpServiceReady) pgpServiceConnection?.getService() else null
        if (service == null) {
            pgpServiceReady = false
            pgpServiceBinding = false
            pgpServiceFailed = true
            updateOpenPgpAvailability()
            showBackupFeedback(R.string.backup_toast_openpgp_unavailable)
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
        Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT -> Constants.BackupType.ENCRYPTED
        Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP,
        Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP -> Constants.BackupType.OPEN_PGP
        else -> null
    }

    private fun tryResumePendingPickerAction() {
        val requestCode = pendingResumeRequestCode ?: return
        if (!canOperate()) return
        val uri = pendingResumeUri
        if (uri == null || uri.getScheme() != "content") {
            pendingResumeRequestCode = null
            pendingResumeUri = null
            completedBackupNotice = if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT ||
                requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP) "export_failed" else "import_failed"
            finishWithResult()
            return
        }
        val replace = pendingResumeReplace
        setActiveAction(requestCode, uri, replace)
        replaceExisting = replace
        if (backupTypeForPickerRequest(requestCode) == Constants.BackupType.OPEN_PGP &&
            !usesKeyFileMode() && pgpServiceConnection?.isBound() != true) return

        pendingResumeRequestCode = null
        pendingResumeUri = null
        pendingResumeReplace = false
        when (requestCode) {
            Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT -> doRestoreCrypt(uri)
            Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP -> restoreEncryptedWithPGP(uri, null)
            Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT -> doBackupCrypt(uri)
            Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP -> backupEncryptedWithPGP(uri, null)
            else -> {
                discardActiveBackupDocument()
                showBackupFeedback(R.string.backup_toast_import_failed)
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
        forwardDeferredAction(requestCode, uri, replaceExisting)
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
        outState.putBoolean(STATE_REPLACE_MODE, replaceExisting)
        outState.putString(STATE_CURRENT_TASK, currentTask.name)
    }

    private var completedBackupNotice: String? = null

    /** Finishes the activity, reporting to MainActivity whether entries had to be reloaded. */
    fun finishWithResult() {
        clearActiveAction()
        clearPendingResumeAction(discardCreatedDocument = true)
        val data = Intent()
        data.putExtra("reload", reload)
        if (canOperate()) completedBackupNotice?.let {
            data.putExtra("backup_feedback_kind", it)
            data.putExtra("backup_feedback_generation", VaultSession.generation().toString())
        }
        completedBackupNotice = null
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
        clearBackupFeedback()
        backupOverview?.clear()
        backupOverview = null
        activeRestoreModeDialog?.dismiss()
        activeRestoreModeDialog = null
        activeKeyFileDialog?.dismiss()
        activeKeyFileDialog = null
        activePasswordDialog?.dismiss()
        activePasswordDialog = null
        encryptionKey = null
        releaseOpenPgpService()
        super.onDestroy()
    }

    override fun requiresVaultSession(): Boolean =
        !(lockedWhilePicker && pendingPickerRequestCode != null)

    override fun onVaultLocked() {
        completedBackupNotice = null
        clearBackupFeedback()
        backupOverview?.clear(getString(R.string.backup_desc_vault_locked_picker))
        encryptionKey = null
        waitingForPassword = false
        activeRestoreModeDialog?.dismiss()
        activeRestoreModeDialog = null
        activeKeyFileDialog?.dismiss()
        activeKeyFileDialog = null
        activePasswordDialog?.dismiss()
        activePasswordDialog = null
        cancelBackgroundTask()
        if (pendingPickerRequestCode != null) {
            // Keep only the pending request code while the external picker finishes. The URI and
            // action are handed to MainActivity, which re-authenticates before restarting work.
            lockedWhilePicker = true
            backupWarning = getString(R.string.backup_desc_vault_locked_picker)
            backupEnabled = false
            restoreEnabled = false
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

    private val feedbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var feedbackExpiry: Runnable? = null

    private fun clearBackupFeedback() {
        feedbackExpiry?.let { feedbackHandler.removeCallbacks(it) }
        feedbackExpiry = null
    }

    private fun showBackupFeedback(message: Int) = showBackupFeedback(getString(message))

    private fun showBackupFeedback(message: String) {
        if (!canOperate()) return
        val page = backupOverview ?: return
        clearBackupFeedback()
        val revision = page.showNotice(message)
        val expiry = Runnable { page.dismissNotice(revision) }
        feedbackExpiry = expiry
        feedbackHandler.postDelayed(expiry, 5000L)
    }

    override fun onTaskFailure(error: TaskFailure) {
        val isBackup = activeActionRequestCode?.let(::isSaveDocumentRequest) == true ||
            currentTask == BackupTaskResult.ResultType.BACKUP
        discardActiveBackupDocument()
        showBackupFeedback(if (isBackup) R.string.backup_toast_export_failed
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
            // Do not forward arbitrary task payloads (or provider text) through Activity results.
            completedBackupNotice = "export_success"
            finishWithResult()
        } else {
            discardActiveBackupDocument()
            val failureMessage = result.messageId.takeIf { it != 0 } ?: R.string.backup_toast_export_failed
            showBackupFeedback(failureMessage)
        }
    }

    private fun handleRestoreTaskResult(result: BackupTaskResult) {
        if (result.success) {
            if (result.isPGP) {
                val service = requireOpenPgpService() ?: run {
                    clearActiveAction()
                    return
                }
                val ciphertextBytes = result.rawPayload ?: result.payload!!.toByteArray(StandardCharsets.UTF_8)
                val input = ByteArrayInputStream(ciphertextBytes)
                val os = BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES)

                val api = OpenPgpApi(this, service)
                val resultIntent: Intent
                try {
                    resultIntent = api.executeApi(result.decryptIntent!!, input, os)
                } catch (error: RuntimeException) {
                    clearActiveAction()
                    showBackupFeedback(R.string.backup_toast_import_failed)
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
                showBackupFeedback(result.messageId)
            else
                showBackupFeedback(R.string.backup_toast_import_failed)
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
        try {
            allowExit = !running

            backupEnabled = !running
            restoreEnabled = !running
            if (!running && backupType == Constants.BackupType.OPEN_PGP) {
                updateOpenPgpAvailability()
            }
        } finally { publishBackupOverview() }
    }

    private fun showBackupProgress(running: Boolean) {
        try {
            toggleInProgressMode(running)
        } finally { publishBackupOverview() }
    }

    private fun showRestoreProgress(running: Boolean) {
        try {
            toggleInProgressMode(running)
        } finally { publishBackupOverview() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        try {
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
                    showBackupFeedback(if (isSaveDocumentRequest(requestCode))
                        R.string.backup_toast_file_creation_failed else R.string.backup_toast_import_failed)
                    return
                }
                if (selectedUri != null) setActiveAction(requestCode, selectedUri, replaceExisting)
            }

            if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT && resultCode == RESULT_OK) {
                if (intent != null) {
                    doRestoreCrypt(intent.data)
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
            } else if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP ||
                requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                notifyOpenPgpFailure(requestCode)
            }
        } finally { publishBackupOverview() }
    }

    /* Generic functions for all backup/restore options */

    /** Snapshot mode before the external picker; never let a stale prompt start an operation. */
    private fun chooseRestoreMode(requestCode: Int) {
        if (!canOperate() || !allowExit) return
        if (requestCode != Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT &&
            requestCode != Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP) return
        val generation = VaultSession.generation()
        activeRestoreModeDialog?.dismiss()
        var host: RestoreModeComposeView? = null
        val closeHost = {
            val h = host
            if (activeRestoreModeDialog === h) activeRestoreModeDialog = null
            h?.dismiss()
            Unit
        }
        host = RestoreModeComposeView(this,
            onConfirm = { replace ->
                val ownsPrompt = activeRestoreModeDialog === host
                closeHost()
                if (ownsPrompt && VaultSession.isCurrent(generation) && canOperate() && allowExit) {
                    replaceExisting = replace
                    showOpenFileSelector(requestCode)
                }
            }, onDismiss = closeHost)
        activeRestoreModeDialog = host
        host.show()
    }

    private fun showOpenFileSelector(intentId: Int) {
        try {
            val openDocument = Intent(Intent.ACTION_OPEN_DOCUMENT)
            openDocument.addCategory(Intent.CATEGORY_OPENABLE)
            openDocument.setType("*/*")
            openDocument.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            pendingPickerRequestCode = intentId
            publishBackupOverview()

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
                showBackupFeedback(R.string.backup_toast_file_selection_failed)
            } catch (e: SecurityException) {
                pendingPickerRequestCode = null
                Log.d(TAG, "ACTION_GET_CONTENT is not available in this profile")
                showBackupFeedback(R.string.backup_toast_file_selection_failed)
            }
        } finally { publishBackupOverview() }
    }

    /** Uses the system file picker, or backs up straight to the configured location when "ask" is off. */
    private fun showSaveFileSelector(mimeType: String, backupType: Constants.BackupType, intentId: Int) {
        try {
            if (settings!!.getBackupAsk()) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType(mimeType)
                intent.putExtra(Intent.EXTRA_TITLE, BackupHelper.backupFilename(this, backupType))
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                pendingPickerRequestCode = intentId
                publishBackupOverview()
                try {
                    startActivityForResult(intent, intentId)
                } catch (error: ActivityNotFoundException) {
                    pendingPickerRequestCode = null
                    // Some Work Profiles / custom ROMs have no ACTION_CREATE_DOCUMENT handler.
                    showBackupFeedback(R.string.backup_toast_file_selection_failed)
                } catch (error: SecurityException) {
                    pendingPickerRequestCode = null
                    showBackupFeedback(R.string.backup_toast_file_selection_failed)
                }
            } else {
                if (settings!!.isBackupLocationSet()) {
                    if (intentId == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT) {
                        doBackupCrypt(null)
                    } else if (intentId == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP) {
                        backupEncryptedWithPGP(null, null)
                    }
                } else {
                    showBackupFeedback(R.string.backup_toast_no_location)
                }
            }
        } finally { publishBackupOverview() }
    }

    /** Adds the restored entries (replacing the current ones if selected) and saves the database. */
    private fun restoreEntries(text: String, finish: Boolean): Boolean {
        if (!canOperate()) return false
        val app = getApplicationContext()
        val callback = UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult> { outcome ->
            val message = when (outcome) {
                is TaskOutcome.Failure -> R.string.backup_toast_export_failed
                is TaskOutcome.Value -> if (outcome.value.success) 0 else
                    outcome.value.messageId.takeIf { it != 0 } ?: R.string.backup_toast_export_failed
                is TaskOutcome.Cancelled -> 0
            }
            if (message != 0) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
        val result = restoreOperation!!.restore(text, replaceExisting,
            encryptionKey, VaultSession.generation(), BooleanSupplier { canOperate() }, callback)
        if (result == VaultRestoreOperation.Result.SAVED) {
            reload = true
            completedBackupNotice = "import_success"
            if (finish) finishWithResult()
            return true
        }
        if (result == VaultRestoreOperation.Result.EMPTY_IMPORT)
            showBackupFeedback(R.string.backup_toast_import_no_entries)
        else if (result == VaultRestoreOperation.Result.SAVE_FAILED
            || result == VaultRestoreOperation.Result.SESSION_REVOKED
            || result == VaultRestoreOperation.Result.RECOVERY_REQUIRED)
            showBackupFeedback(R.string.backup_toast_import_save_failed)
        else
            showBackupFeedback(R.string.backup_toast_import_failed)
        return false
    }

    /* Encrypted backup functions */

    private fun doRestoreCrypt(uri: Uri?) {
        if (!canOperate()) return
        // Every file may have a different historical password, including after a password change.
        waitingForPassword = true
        activePasswordDialog?.dismiss()
        var passwordSubmitted = false
        var pwDialog: EncryptedRestorePasswordComposeView? = null
        val closeDialog = {
            val h = pwDialog
            if (activePasswordDialog === h) activePasswordDialog = null
            h?.dismiss()
            Unit
        }
        pwDialog = EncryptedRestorePasswordComposeView(
            activity = this,
            decodeDescription = getString(R.string.backup_label_crypt),
            onDecode = { newPassword ->
                passwordSubmitted = true
                waitingForPassword = false
                closeDialog()
                doRestoreCryptWithPassword(uri, newPassword)
            },
            onDismiss = closeDialog
        )
        pwDialog.setOnDismissListener {
            if (!passwordSubmitted) {
                waitingForPassword = false
                if (activePasswordDialog === pwDialog) activePasswordDialog = null
                if (canOperate()) discardActiveBackupDocument()
            }
        }
        activePasswordDialog = pwDialog
        pwDialog.show()
    }

    private fun doRestoreCryptWithPassword(uri: Uri?, password: String) {
        if (!canOperate()) return
        // ACTION_OPEN_DOCUMENT returns a SAF content URI. It remains usable for cloud and work
        // profile providers even when legacy shared external storage is not mounted.
        val task = EncryptedRestoreTask(this, uri, password)
        startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE)
    }

    private fun doBackupCrypt(uri: Uri?) {
        if (!canOperate()) return
        // Manual full-file copies and independently password-encrypted exports are distinct actions.
        run {
            waitingForPassword = true
            activePasswordDialog?.dismiss()
            var passwordSubmitted = false
            var pwDialog: EncryptedExportComposeView? = null
            val closeDialog = {
                val h = pwDialog
                if (activePasswordDialog === h) activePasswordDialog = null
                h?.dismiss()
                Unit
            }
            pwDialog = EncryptedExportComposeView(
                activity = this,
                minPasswordLength = Constants.AUTH_MIN_PASSWORD_LENGTH,
                tooShortMessage = getString(
                    R.string.settings_label_short_password,
                    Constants.AUTH_MIN_PASSWORD_LENGTH
                ),
                onConfirm = { newPassword ->
                    passwordSubmitted = true
                    waitingForPassword = false
                    closeDialog()
                    doBackupCryptWithPassword(uri, newPassword)
                    null
                },
                onDismiss = closeDialog,
                onSnapshot = {
                    if (activePasswordDialog === pwDialog && canOperate()) {
                        passwordSubmitted = true; waitingForPassword = false
                        closeDialog()
                        startBackgroundTask(com.fairuniversal.offlineotp.Tasks.VaultSnapshotBackupTask(this, uri), BackupTaskResult.ResultType.BACKUP)
                    }
                },
                onMigrateAndSnapshot = { currentPassword ->
                    if (activePasswordDialog === pwDialog && canOperate()) {
                        passwordSubmitted = true; waitingForPassword = false
                        closeDialog()
                        startBackgroundTask(com.fairuniversal.offlineotp.Tasks.VaultSnapshotBackupTask(this, uri, currentPassword), BackupTaskResult.ResultType.BACKUP)
                    }
                }
            )
            pwDialog.setOnDismissListener {
                if (!passwordSubmitted) {
                    waitingForPassword = false
                    if (activePasswordDialog === pwDialog) activePasswordDialog = null
                    if (canOperate()) discardActiveBackupDocument()
                }
            }
            activePasswordDialog = pwDialog
            pwDialog.show()
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
            showBackupFeedback(R.string.backup_toast_export_failed)
        }
    }

    /** Confirms recipient key files through the shared OpenPGP export dialog, then encrypts. */
    private fun backupWithKeyFile(uri: Uri?) {
        if (!canOperate()) return
        val recipients = settings!!.getOpenPGPRecipientFiles()
        if (recipients.isEmpty()) {
            discardActiveBackupDocument()
            updateKeyFileAvailability()
            showBackupFeedback(R.string.backup_toast_openpgp_no_recipients)
            return
        }
        val recipientNames = recipients.map { raw ->
            val parsed = Uri.parse(raw)
            val resolved = StorageAccessHelper.getContentFileName(this, parsed)
            resolved.takeIf { it.isNotBlank() && (it != "backup" || raw.endsWith("/backup")) }
                ?: raw.substringAfterLast('/').takeIf(String::isNotBlank)
                ?: getString(R.string.settings_title_openpgp_recipient_files)
        }
        waitingForPassword = true
        activePasswordDialog?.dismiss()
        activePasswordDialog = null
        activeKeyFileDialog?.dismiss()
        var confirmed = false
        var exportDialog: OpenPgpExportComposeView? = null
        val closeExportDialog = {
            val h = exportDialog
            if (activeKeyFileDialog === h) activeKeyFileDialog = null
            h?.dismiss()
            Unit
        }
        exportDialog = OpenPgpExportComposeView(
            activity = this,
            recipientNames = recipientNames,
            maxRecipients = OpenPgpKeyFilePolicy.MAX_RECIPIENTS,
            onExport = {
                confirmed = true
                waitingForPassword = false
                closeExportDialog()
                startKeyFileBackup(uri, recipients)
            },
            onDismiss = closeExportDialog
        )
        exportDialog.setOnDismissListener {
            if (!confirmed) {
                waitingForPassword = false
                if (activeKeyFileDialog === exportDialog) activeKeyFileDialog = null
                discardActiveBackupDocument()
            }
        }
        activeKeyFileDialog = exportDialog
        exportDialog.show()
    }

    private fun startKeyFileBackup(uri: Uri?, recipients: List<String>) {
        if (!canOperate()) {
            discardActiveBackupDocument()
            return
        }
        try {
            val entries = readOwnedEntries()
            try {
                startBackgroundTask(
                    OpenPgpKeyFileBackupTask(this, entries, recipients, openPgpPort, uri),
                    BackupTaskResult.ResultType.BACKUP)
            } finally {
                for (entry in entries) entry.destroy()
            }
        } catch (error: RuntimeException) {
            discardActiveBackupDocument()
            showBackupFeedback(R.string.backup_toast_export_failed)
        }
    }

    /**
     * Asks how the selected secret key is protected, then decrypts with the bundled backend.
     *
     * An unprotected key needs no passphrase, so the user can say so explicitly rather than being
     * forced to type one. The passphrase is never stored; the task owns and clears it.
     */
    private fun restoreWithKeyFile(uri: Uri?) {
        if (!canOperate()) return
        val secretFile = settings!!.getOpenPGPSecretFile()
        if (secretFile.isEmpty()) {
            clearActiveAction()
            updateKeyFileAvailability()
            showBackupFeedback(R.string.backup_toast_openpgp_no_secret_key)
            return
        }
        waitingForPassword = true
        activePasswordDialog?.dismiss()
        activePasswordDialog = null
        activeKeyFileDialog?.dismiss()
        var chooser: EntryCopyComposeView? = null
        val closeChooser = {
            val h = chooser
            if (activeKeyFileDialog === h) activeKeyFileDialog = null
            h?.dismiss()
            Unit
        }
        chooser = EntryCopyComposeView(
            activity = this,
            title = getString(R.string.backup_dialog_openpgp_passphrase_title),
            cancelLabel = getString(R.string.backup_dialog_openpgp_passphrase_cancel),
            fields = listOf(
                getString(R.string.backup_dialog_openpgp_passphrase_protected),
                getString(R.string.backup_dialog_openpgp_passphrase_unprotected)
            ),
            message = getString(R.string.backup_dialog_openpgp_passphrase_message),
            onSelect = { index ->
                closeChooser()
                if (index == 0) {
                    promptForKeyFilePassphrase(uri, secretFile)
                } else {
                    waitingForPassword = false
                    startKeyFileRestore(uri, secretFile, null)
                }
            },
            onDismiss = {
                closeChooser()
                waitingForPassword = false
                clearActiveAction()
            }
        )
        activeKeyFileDialog = chooser
        chooser.show()
    }

    private fun promptForKeyFilePassphrase(uri: Uri?, secretFile: String) {
        if (!canOperate()) {
            waitingForPassword = false
            return
        }
        activePasswordDialog?.dismiss()
        var passphraseSubmitted = false
        var pwDialog: BiometricEnableComposeView? = null
        val closeDialog = {
            val h = pwDialog
            if (activePasswordDialog === h) activePasswordDialog = null
            h?.dismiss()
            Unit
        }
        pwDialog = BiometricEnableComposeView(
            activity = this,
            title = getString(R.string.dialog_title_enter_password),
            detail = getString(R.string.backup_dialog_openpgp_passphrase_message),
            passwordLabel = getString(R.string.settings_hint_password),
            confirmLabel = getString(android.R.string.ok),
            cancelLabel = getString(android.R.string.cancel),
            onConfirm = { passphrase ->
                if (passphrase.isEmpty()) {
                    getString(R.string.backup_dialog_openpgp_passphrase_message)
                } else {
                    val copied = passphrase.toCharArray()
                    passphraseSubmitted = true
                    waitingForPassword = false
                    closeDialog()
                    startKeyFileRestore(uri, secretFile, copied)
                    null
                }
            },
            onDismiss = closeDialog
        )
        pwDialog.setOnDismissListener {
            if (!passphraseSubmitted) {
                waitingForPassword = false
                if (activePasswordDialog === pwDialog) activePasswordDialog = null
                if (canOperate()) clearActiveAction()
            }
        }
        activePasswordDialog = pwDialog
        pwDialog.show()
    }

    private fun startKeyFileRestore(uri: Uri?, secretFile: String, passphrase: CharArray?) {
        if (!canOperate()) {
            passphrase?.fill('\u0000')
            return
        }
        startBackgroundTask(
            OpenPgpKeyFileRestoreTask(this, uri, secretFile, passphrase, openPgpPort),
            BackupTaskResult.ResultType.RESTORE)
    }

    private fun restoreEncryptedWithPGP(uri: Uri?, decryptIntent: Intent?) {
        if (!canOperate()) return
        if (usesKeyFileMode()) {
            restoreWithKeyFile(uri)
            return
        }
        if (requireOpenPgpService() == null) {
            clearActiveAction()
            return
        }
        val intent = decryptIntent ?: Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY)

        val task = PGPRestoreTask(this, uri, intent)
        startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE)
    }

    private fun doBackupEncrypted(uri: Uri?, data: String, rawBytes: ByteArray? = null) {
        if (!canOperate()) return
        // The destination is a SAF content URI, not a raw external-storage path. Cloud providers
        // and managed profiles are valid destinations even when shared storage is not mounted.
        val task = PGPBackupTask(this, data, uri).apply {
            rawPayload = rawBytes
        }
        startBackgroundTask(task, BackupTaskResult.ResultType.BACKUP)
    }

    private fun backupEncryptedWithPGP(uri: Uri?, encryptIntent: Intent?) {
        if (!canOperate()) return
        if (usesKeyFileMode()) {
            backupWithKeyFile(uri)
            return
        }
        val service = requireOpenPgpService() ?: run {
            discardActiveBackupDocument()
            return
        }
        if (pgpEncryptionUserIDs.isEmpty()) {
            discardActiveBackupDocument()
            updateOpenPgpAvailability()
            showBackupFeedback(R.string.backup_toast_openpgp_no_recipients)
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
            showBackupFeedback(R.string.backup_toast_export_failed)
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
            showBackupFeedback(R.string.backup_toast_export_failed)
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
                    val rawEncrypted = os?.toByteArray()
                    val encryptedBackup = os?.let { outputStreamToString(it) } ?: ""
                    if (rawEncrypted == null || rawEncrypted.isEmpty() ||
                            !OpenPgpOutputPolicy.hasContent(encryptedBackup)) {
                        discardActiveBackupDocument()
                        showBackupFeedback(R.string.backup_toast_openpgp_empty)
                        return
                    }
                    doBackupEncrypted(file, encryptedBackup, rawEncrypted)
                } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                    val restoredJSON = os?.let { outputStreamToString(it) } ?: ""
                    if (!OpenPgpOutputPolicy.hasContent(restoredJSON)) {
                        clearActiveAction()
                        showBackupFeedback(R.string.backup_toast_import_failed)
                        return
                    }
                    if (settings!!.getOpenPGPVerify()) {
                        val sigResult = result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE)
                        if (sigResult != null && sigResult.getResult() == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED) {
                            if (!restoreEntries(restoredJSON, true)) clearActiveAction()
                        } else {
                            clearActiveAction()
                            showBackupFeedback(R.string.backup_toast_openpgp_not_verified)
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
                    showBackupFeedback(
                        String.format(getString(R.string.backup_toast_openpgp_error), error.getMessage()))
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
        showBackupFeedback(if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP)
            R.string.backup_toast_export_failed else R.string.backup_toast_import_failed)
    }

    override fun shouldDestroyOnScreenOff(): Boolean {
        return BackupScreenOffPolicy.shouldFinish(
            allowExit = allowExit,
            pickerPending = pendingPickerRequestCode != null,
            actionPending = activeActionRequestCode != null ||
                waitingForPassword ||
                encryptTargetFile != null ||
                decryptSourceFile != null,
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
        private const val STATE_REPLACE_MODE = "replaceExisting"
        private const val STATE_CURRENT_TASK = "current_task"
        private const val INVALID_REQUEST_CODE = -1
    }
}
