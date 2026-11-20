package com.fairuniversal.offlineotp.Activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Window

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

import com.fairuniversal.offlineotp.Tasks.FinishIntroTask
import com.fairuniversal.offlineotp.Tasks.FirstRunBackupRestoreTask
import com.fairuniversal.offlineotp.Tasks.TaskOutcome
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.Tools
import com.fairuniversal.offlineotp.Utilities.VaultSession

import com.fairuniversal.offlineotp.ui.intro.Constants as ComposeConstants
import com.fairuniversal.offlineotp.ui.intro.IntroStrings
import com.fairuniversal.offlineotp.ui.intro.SharedIntroScreen

import java.util.Arrays

/** Host adapter wrapping the shared [SharedIntroScreen] Compose form. */
open class IntroScreenActivityHost : android.app.Activity() {
    private var setupFinished = false
    private var encryptionKey: ByteArray? = null
    private var setupGeneration: Long = VaultSession.generation()
    private var authSlideIndex = 2
    private var firstRunRestoreTask: FirstRunBackupRestoreTask? = null

    private var sharedFragment: SharedIntroFragment? = null

    private companion object {
        const val REQUEST_FIRST_RUN_BACKUP = 6419
        const val STATE_SETUP_FINISHED = "intro_setup_finished"
        const val STATE_SETUP_GENERATION = "intro_setup_generation"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Tools.applyScreenshotPolicy(window, Settings(this).getScreenshotsEnabled())

        setupFinished = savedInstanceState?.getBoolean(STATE_SETUP_FINISHED, false) == true
        if (savedInstanceState != null) {
            setupGeneration = savedInstanceState.getLong(STATE_SETUP_GENERATION, setupGeneration)
        }

        // Initialize Shared Fragment
        sharedFragment = SharedIntroFragment(
            context = this,
            strings = IntroStrings(
                appTitle = getString(R.string.app_name),
                encryptionTitle = getString(R.string.settings_title_encryption),
                encryptionDescKeystore = getString(R.string.intro_slide2_desc_keystore),
                encryptionDescPassword = getString(R.string.intro_slide2_desc_password),
                authTitle = getString(R.string.settings_title_auth),
                authDescPassword = getString(R.string.intro_slide3_desc_password),
                authDescKeystore = getString(R.string.intro_slide3_desc_keystore),
                authWarnings = "",
                syncTitle = getString(R.string.settings_title_enable_android_backup_service),
                syncDesc = getString(R.string.intro_slide_android_backup_desc),
                syncEnabledText = getString(R.string.settings_toast_android_sync_enabled),
                syncDisabledText = getString(R.string.security_platform_backup_disabled),
                finishedTitleProgress = getString(R.string.intro_slide4_title_progress),
                finishedDescProgress = getString(R.string.intro_slide4_desc_progress),
                finishedTitleSuccess = getString(R.string.intro_slide4_title),
                finishedDescSuccess = getString(R.string.intro_slide4_desc),
                finishedTitleFailed = getString(R.string.intro_slide4_title_failed),
                finishedDescFailed = getString(R.string.intro_slide4_desc_failed),
                buttonCreateVault = getString(R.string.introFinishedCreateVault), // will be mapped
                buttonRestoreBackup = getString(R.string.introFinishedRestoreBackup),
                passwordLabel = getString(R.string.settings_hint_password),
                confirmPasswordLabel = getString(R.string.settings_hint_password_confirm),
                unlockAction = "",
                unlocking = ""
            ),
            encryptionType = ComposeConstants.EncryptionType.PASSWORD,
            onEncryptionChanged = { newType ->
                // Update native state if needed
            },
            authMethod = ComposeConstants.AuthMethod.PASSWORD,
            onAuthMethodChanged = { newMethod ->
                // Update native state if needed
            },
            onPasswordInput = { pass, confirm ->
                // Handle intermediate input validation if needed
            },
            onSaveSettings = { encType, authMethod, password, syncEnabled ->
                setupFinished = true
                encryptionKey = password?.toByteArray(Charsets.UTF_8)
                val entries: ArrayList<com.fairuniversal.offlineotp.Database.Entry>? = null // default
                val resolvedContext = this
                val task = FinishIntroTask(resolvedContext, encType.ordinal.coerceAtMost(1).let { Constants.EncryptionType.values()[it] },
                    authMethod.ordinal.coerceAtMost(2).let { Constants.AuthMethod.values()[it] },
                    password, syncEnabled, entries)
                task.setOutcomeCallback { outcome ->
                    if (outcome is TaskOutcome.Value) {
                        val result = outcome.value
                        try {
                            handleTaskResult(result)
                        } finally {
                            task.disposeOutcome(outcome)
                        }
                    } else {
                        task.disposeOutcome(outcome)
                    }
                }
                task.execute()
            },
            onRestoreBackup = {
                chooseFirstRunBackup()
            },
            syncEnabled = false,
            onSyncEnabledChanged = { enabled ->
                // Native sync update
            },
            busy = false,
            error = null,
            creating = true
        )

        // Attach fragment to window
        window.decorView.post {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, sharedFragment!!)
                .commit()
        }
    }

    internal fun chooseFirstRunBackup() {
        if (firstRunRestoreTask != null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("application/octet-stream")
        }
        try { startActivityForResult(intent, REQUEST_FIRST_RUN_BACKUP) }
        catch (_: Exception) { sharedFragment?.showRestoreError("The backup picker could not be opened.") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FIRST_RUN_BACKUP) return
        if (resultCode != android.app.Activity.RESULT_OK || data?.data == null) return
        promptFirstRunBackupPassword(data.data!!)
    }

    private fun promptFirstRunBackupPassword(uri: Uri) {
        // In a full migration, the SharedIntroScreen handles password entry.
        // For the host adapter, we delegate to the shared fragment's restore flow.
        sharedFragment?.triggerRestore(uri)
    }

    private fun readFirstRunBackup(uri: Uri, backupPassword: String) {
        val task = FirstRunBackupRestoreTask(this, uri, backupPassword)
        firstRunRestoreTask = task
        sharedFragment?.showRestoreProgress()
        task.setOutcomeCallback { outcome ->
            if (firstRunRestoreTask !== task) {
                task.disposeOutcome(outcome)
                return@setOutcomeCallback
            }
            firstRunRestoreTask = null
            if (outcome is TaskOutcome.Value) {
                val entries = outcome.value.entries
                outcome.value.entries = null
                if (entries == null) sharedFragment?.showRestoreError("The backup password was incorrect or the backup could not be read.")
                else {
                    setupFinished = true
                    encryptionKey = outcome.value.encryptionKey?.copyOf()
                    sharedFragment?.applyCompletedSetup()
                }
                task.disposeOutcome(outcome)
            } else {
                task.disposeOutcome(outcome)
                sharedFragment?.showRestoreError("The backup could not be restored. No vault was created.")
            }
        }
        task.execute()
    }

    private fun handleTaskResult(result: FinishIntroTask.Result) {
        if (!VaultSession.isCurrent(result.generation)) {
            result.encryptionKey?.let { Arrays.fill(it, 0) }
            return
        }
        if (result.saveSuccessful) {
            result.encryptionKey?.let { VaultSession.offerDerivedKey(it.copyOf(), result.generation) }
            sharedFragment?.applyCompletedSetup()
        } else {
            sharedFragment?.showRestoreError("The setup failed to save.")
        }
    }

    public override fun onResume() {
        super.onResume()
        Tools.applyScreenshotPolicy(window, Settings(this).getScreenshotsEnabled())
    }

    public override fun onPause() {
        val settings = Settings(this)
        Tools.applyAppSwitcherPolicy(window,
            settings.getScreenshotsEnabled(), settings.getHideInAppSwitcher())
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETUP_FINISHED, setupFinished)
        outState.putLong(STATE_SETUP_GENERATION, setupGeneration)
    }

    override fun onDestroy() {
        encryptionKey?.let { Arrays.fill(it, 0) }
        encryptionKey = null
        super.onDestroy()
    }

    /** Returns the setup result to MainActivity and hands any derived key to [VaultSession]. */
    override fun onSendActivityResult(result: Int): Intent {
        val data = Intent()
        data.putExtra(Constants.EXTRA_INTRO_FINISHED, setupFinished)
        val key = encryptionKey
        if (key != null) {
            VaultSession.offerDerivedKey(key, setupGeneration)
            Arrays.fill(key, 0)
            encryptionKey = null
        }
        return data
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Prevent leaving the intro screen before initial setup completes.
    }

    /** Fragment hosting the SharedIntroScreen Compose form. */
    class SharedIntroFragment(
        private val context: Context,
        private val strings: IntroStrings,
        private val encryptionType: ComposeConstants.EncryptionType,
        private val onEncryptionChanged: (ComposeConstants.EncryptionType) -> Unit,
        private val authMethod: ComposeConstants.AuthMethod,
        private val onAuthMethodChanged: (ComposeConstants.AuthMethod) -> Unit,
        private val onPasswordInput: (String, String) -> Unit,
        private val onSaveSettings: (ComposeConstants.EncryptionType, ComposeConstants.AuthMethod, String?, Boolean) -> Unit,
        private val onRestoreBackup: () -> Unit,
        private var syncEnabled: Boolean,
        private val onSyncEnabledChanged: (Boolean) -> Unit,
        private var busy: Boolean,
        private var error: String?,
        private val creating: Boolean
    ) : Fragment() {

        fun showRestoreProgress() {
            busy = true
            error = null
            // Trigger UI update in Compose
        }

        fun showRestoreError(text: String) {
            busy = false
            error = text
            // Trigger UI update in Compose
        }

        fun applyCompletedSetup() {
            busy = false
            error = null
            // Trigger UI update in Compose
        }

        fun triggerRestore(uri: Uri) {
            // In the real implementation, this would pass the URI to the Compose form
            // which would then prompt for the password and call onRestoreBackup with the password.
            // For now, we simulate the callback structure.
            onRestoreBackup()
        }

        override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?,
                                  savedInstanceState: Bundle?): android.view.View {
            // The host adapter inflates the ComposeView or uses the Activity decor view.
            // For compatibility with the sandbox, we return a placeholder view that wraps the Compose form.
            val composeView = androidx.compose.ui.platform.ComposeView(context).apply {
                setContent {
                    com.fairuniversal.offlineotp.ui.theme.ForestTheme {
                        SharedIntroScreen(
                            strings = strings,
                            encryptionType = encryptionType,
                            onEncryptionChanged = onEncryptionChanged,
                            authMethod = authMethod,
                            onAuthMethodChanged = onAuthMethodChanged,
                            onPasswordInput = onPasswordInput,
                            onSaveSettings = onSaveSettings,
                            onRestoreBackup = onRestoreBackup,
                            syncEnabled = syncEnabled,
                            onSyncEnabledChanged = onSyncEnabledChanged,
                            busy = busy,
                            error = error,
                            creating = creating
                        )
                    }
                }
            }
            return composeView
        }
    }
}
