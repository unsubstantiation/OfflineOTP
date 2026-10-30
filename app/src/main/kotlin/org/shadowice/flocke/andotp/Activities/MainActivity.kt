package org.shadowice.flocke.andotp.Activities

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.zxing.integration.android.IntentIntegrator
import com.google.android.material.tabs.TabLayout
import com.leinardi.android.speeddial.FabWithLabelView
import com.leinardi.android.speeddial.SpeedDialView

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Dialogs.HideableDialog
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog
import org.shadowice.flocke.andotp.Dialogs.EmailEntryDialog
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod
import org.shadowice.flocke.andotp.Utilities.Constants.EncryptionType
import org.shadowice.flocke.andotp.Utilities.Constants.SortMode
import org.shadowice.flocke.andotp.Utilities.AuthenticationResultPolicy
import org.shadowice.flocke.andotp.Utilities.BrowserPasswordCsv
import org.shadowice.flocke.andotp.Utilities.PasswordImport
import org.shadowice.flocke.andotp.Utilities.EmailMaterial
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.HandlerTimerScheduler
import org.shadowice.flocke.andotp.Utilities.GoogleAuthenticatorMigration
import org.shadowice.flocke.andotp.Utilities.MigrationBatchCollector
import org.shadowice.flocke.andotp.Utilities.MigrationBatchStatus
import org.shadowice.flocke.andotp.Utilities.MigrationError
import org.shadowice.flocke.andotp.Utilities.MigrationParseResult
import org.shadowice.flocke.andotp.Utilities.PendingMigrationImport
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.NotificationHelper
import org.shadowice.flocke.andotp.Utilities.PendingEntryImport
import org.shadowice.flocke.andotp.Utilities.ScanQRCodeFromFile
import org.shadowice.flocke.andotp.Utilities.ScreenTimerController
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper
import org.shadowice.flocke.andotp.Utilities.SessionLockReason
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.View.EntriesCardAdapter
import org.shadowice.flocke.andotp.View.ItemTouchHelper.SimpleItemTouchHelperCallback
import org.shadowice.flocke.andotp.View.TagsAdapter

import java.util.Arrays
import java.util.HashMap

import javax.crypto.SecretKey

/** Main screen displaying the OTP entry list, search, tag drawer, and authentication flow. */
open class MainActivity : BaseActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var adapter: EntriesCardAdapter? = null
    private var speedDial: SpeedDialView? = null
    private var passwordImportMode: PasswordImport.Mode? = null
    private var passwordExportName: String? = null
    private var passwordExportDigest: String? = null
    private var passwordPendingUri: Uri? = null
    private var sortMenu: MenuItem? = null
    private var searchMenu: MenuItem? = null
    private var touchHelperCallback: SimpleItemTouchHelperCallback? = null

    private var encryptionType: EncryptionType = EncryptionType.KEYSTORE
    private var requireAuthentication = false
    private var authenticationPending = false
    private var authenticationGeneration: Long = 0
    private var pendingBackupActionCode: Int? = null
    private var pendingBackupUri: Uri? = null
    private var pendingBackupReplace = false
    private var pendingImport: PendingEntryImport? = null
    private var activeImportDialog: AlertDialog? = null
    private var migrationCollector: MigrationBatchCollector? = null
    private var pendingMigrationImport: PendingMigrationImport? = null
    private var activeMigrationDialog: AlertDialog? = null
    private var awaitingMigrationQr = false
    private val processObserver = ProcessLifecycleObserver()

    private var recreateActivity = false
    private var focusSearchOnCreate = false

    private var screenTimers: ScreenTimerController? = null

    private var tagsDrawerLayout: DrawerLayout? = null
    private var tagsDrawerListView: ListView? = null
    private var tagsDrawerAdapter: TagsAdapter? = null
    private var tagsToggle: ActionBarDrawerToggle? = null
    private var filterString: String? = null

    private var progressBar: ProgressBar? = null
    private var emptyListView: TextView? = null
    private var timeoutAnimator: ObjectAnimator? = null

    private fun scanQRCode() {
        IntentIntegrator(this)
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setCaptureActivity(SecureCaptureActivity::class.java)
            .initiateScan()
    }

    private var introPending = false
    private var introGeneration: Long = 0

    private fun showFirstTimeWarning() {
        if (introPending) return
        introPending = true
        introGeneration = VaultSession.generation()
        val introIntent = Intent(this, IntroScreenActivity::class.java)
        startActivityForResult(introIntent, Constants.INTENT_MAIN_INTRO)
    }

    /** Starts the configured authentication flow, showing [messageId] as prompt. */
    fun authenticate(messageId: Int) {
        if (authenticationPending) return
        val attempt = VaultSession.beginUnlock().generation
        requireAuthentication = false
        authenticationPending = true
        authenticationGeneration = attempt
        val authMethod = settings!!.getAuthMethod()
        if (authMethod == AuthMethod.DEVICE) {
            val manager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager?
            if (manager == null || !manager.isKeyguardSecure()) {
                vaultUnavailable()
                return
            }
            val challenge = manager.createConfirmDeviceCredentialIntent(
                getString(R.string.dialog_title_auth), getString(R.string.dialog_msg_auth))
            if (challenge == null) {
                vaultUnavailable()
                return
            }
            startActivityForResult(challenge, Constants.INTENT_MAIN_AUTHENTICATE)
        } else if (authMethod == AuthMethod.PASSWORD || authMethod == AuthMethod.PIN) {
            val challenge = Intent(this, AuthenticateActivity::class.java)
            challenge.putExtra(Constants.EXTRA_AUTH_MESSAGE, messageId)
            challenge.putExtra(Constants.EXTRA_AUTH_ALLOW_HARDWARE, true)
            startActivityForResult(challenge, Constants.INTENT_MAIN_AUTHENTICATE)
        } else {
            vaultUnavailable()
        }
    }

    private fun vaultUnavailable() {
        authenticationPending = false
        VaultSession.fail(org.shadowice.flocke.andotp.Utilities.SessionFailure.VAULT_UNAVAILABLE)
        AlertDialog.Builder(this)
            .setTitle(R.string.security_vault_error_title)
            .setMessage(R.string.security_vault_error)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { _, _ -> finishAndRemoveTask() })
            .show()
    }

    override fun onVaultLocked() {
        emailExportPending?.fill(0)
        emailExportPending = null
        emailExportGeneration = -1L
        emailImportBytes?.fill(0)
        emailImportBytes = null
        emailImportPrompt?.dismiss()
        emailImportPrompt = null
        emailExportSelectionDialog?.dismiss()
        emailExportSelectionDialog = null
        authenticationPending = false
        introPending = false
        requireAuthentication = true
        dismissPendingImport()
        dismissMigrationImport()
        adapter?.lockEntries()
        screenTimers?.pause()
        val cards = findViewById<View>(R.id.cardList)
        cards?.visibility = View.INVISIBLE
    }

    private fun afterAuthentication() {
        if (VaultSession.getKey() == null || isFinishing()) return
        adapter?.retryPendingAutomaticBackup()
        checkAutomaticTime()
        if (adapter != null) {
            launchPendingBackupActionIfReady()
            resumePasswordOperation()
            resumeEmailImport()
        }
    }

    /** Reopens a picker-selected operation only after MainActivity has re-established the vault. */
    private fun launchPendingBackupActionIfReady() {
        if (isFinishing() || authenticationPending || adapter == null || VaultSession.getKey() == null) return
        val actionCode = pendingBackupActionCode ?: return
        val uri = pendingBackupUri
        if (uri == null || uri.getScheme() != "content") {
            clearPendingBackupAction(discardCreatedDocument = true)
            Toast.makeText(this, R.string.backup_toast_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        val resume = Intent(this, BackupActivity::class.java)
            .putExtra(Constants.EXTRA_BACKUP_PENDING_ACTION, actionCode)
            .putExtra(Constants.EXTRA_BACKUP_PENDING_REPLACE, pendingBackupReplace)
        resume.data = uri
        pendingBackupActionCode = null
        pendingBackupUri = null
        pendingBackupReplace = false
        startActivityForResult(resume, Constants.INTENT_MAIN_BACKUP)
    }

    private fun clearPendingBackupAction(discardCreatedDocument: Boolean) {
        val actionCode = pendingBackupActionCode
        val uri = pendingBackupUri
        if (discardCreatedDocument && uri != null &&
            (actionCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT ||
                actionCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP)) {
            StorageAccessHelper.deleteContentFile(this, uri)
        }
        pendingBackupActionCode = null
        pendingBackupUri = null
        pendingBackupReplace = false
    }

    /** Keep TalkBack traversal inside the expanded add menu and restore it when it closes. */
    private fun setSpeedDialAccessibility(menuOpen: Boolean) {
        val backgroundImportance = if (menuOpen) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        findViewById<View>(R.id.main_app_bar)?.setImportantForAccessibility(backgroundImportance)
        findViewById<View>(R.id.main_content)?.setImportantForAccessibility(backgroundImportance)

        val overlay = findViewById<View>(R.id.speedDialOverlay) ?: return
        overlay.setImportantForAccessibility(if (menuOpen)
            View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        overlay.contentDescription = if (menuOpen) getString(R.string.button_close_menu) else null
    }

    /** Request accessibility focus on the first visible action when the menu expands. */
    private fun focusFirstSpeedDialAction(dial: SpeedDialView) {
        val firstActionId = dial.getActionItems().firstOrNull()?.getId() ?: return
        val actionButton = dial.findViewById<FabWithLabelView>(firstActionId)?.getFab() ?: return
        actionButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    /** Anchor the main FAB at the bottom after expansion; shorter screens can scroll to the rest. */
    private fun prepareExpandedSpeedDial(dial: SpeedDialView) {
        val scroll = findViewById<ScrollView>(R.id.speedDialScroll) ?: return
        val observer = scroll.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // A detached view's observer can be replaced when it attaches, transferring
                // this listener. Remove it from the current observer as well as the original.
                val currentObserver = scroll.viewTreeObserver
                if (currentObserver.isAlive) currentObserver.removeOnGlobalLayoutListener(this)
                if (observer !== currentObserver && observer.isAlive)
                    observer.removeOnGlobalLayoutListener(this)
                if (dial.isOpen()) {
                    scroll.scrollTo(0, dial.height)
                    focusFirstSpeedDialAction(dial)
                }
            }
        })
    }

    private fun restoreSortMode() {
        val currentAdapter = adapter
        val callback = touchHelperCallback
        if (settings != null && currentAdapter != null && callback != null) {
            val mode = settings!!.getSortMode()
            currentAdapter.setSortMode(mode)

            callback.setDragEnabled(mode == SortMode.UNSORTED)
        }
    }

    private fun saveSortMode(mode: SortMode) {
        settings?.setSortMode(mode)
    }

    /** Loads the entries into the adapter and refreshes the tag drawer. */
    private fun populateAdapter() {
        val currentAdapter = adapter ?: return
        if (!currentAdapter.loadEntries()) {
            vaultUnavailable()
            return
        }
        tagsDrawerAdapter?.setTags(TagsAdapter.createTagsMap(currentAdapter.getEntries(), settings!!))
        // The drawer rows carry their entry counts (#994); setTags() only replaces the rows, so the
        // counts have to be pushed here as well or they stay empty until the first entry mutation.
        tagsDrawerAdapter?.setTagCounts(currentAdapter.getTagCounts())
        currentAdapter.filterByTags(tagsDrawerAdapter?.getActiveTags() ?: ArrayList())
    }

    private fun checkAutomaticTime() {
        val autoTime = AndroidSettings.Global.getInt(getContentResolver(), AndroidSettings.Global.AUTO_TIME, 0)

        if (autoTime == 0)
            HideableDialog.ShowHidableDialog(this, R.string.dialog_title_auto_time,
                R.string.dialog_msg_auto_time, R.string.settings_key_dialog_hide_auto_time)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            passwordImportMode = PasswordImport.Mode.entries.getOrNull(savedInstanceState.getInt("password_mode", -1))
            passwordExportName = savedInstanceState.getString("password_export_name")
            passwordExportDigest = savedInstanceState.getString("password_export_digest")
            passwordPendingUri = savedInstanceState.getParcelable("password_pending_uri")
            pendingBackupActionCode = savedInstanceState.getInt(STATE_PENDING_BACKUP_ACTION, INVALID_BACKUP_ACTION)
                .takeIf { it != INVALID_BACKUP_ACTION }
            pendingBackupUri = savedInstanceState.getParcelable(STATE_PENDING_BACKUP_URI)
            pendingBackupReplace = savedInstanceState.getBoolean(STATE_PENDING_BACKUP_REPLACE, false)
            val savedAuthPending = savedInstanceState.getBoolean(STATE_AUTH_PENDING, false)
            val savedAuthGen = savedInstanceState.getLong(STATE_AUTH_GENERATION, -1L)
            if (savedAuthPending && savedAuthGen >= 0L && VaultSession.isCurrent(savedAuthGen)) {
                authenticationPending = true
                authenticationGeneration = savedAuthGen
            }
            val savedIntroPending = savedInstanceState.getBoolean(STATE_INTRO_PENDING, false)
            val savedIntroGen = savedInstanceState.getLong(STATE_INTRO_GENERATION, -1L)
            if (savedIntroPending && savedIntroGen >= 0L && VaultSession.isCurrent(savedIntroGen)) {
                introPending = true
                introGeneration = savedIntroGen
            }
        }

        setTitle(R.string.app_name)

        if (!settings!!.getScreenshotsEnabled())
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContentView(R.layout.activity_main)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.toolbar))
        setSupportActionBar(toolbar)

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        settings!!.registerPreferenceChangeListener(this)

        encryptionType = settings!!.getEncryption()

        // Reuse the in-process session across Activity recreation; never serialize keys in saved state.
        requireAuthentication = !authenticationPending && !introPending && VaultSession.getKey() == null &&
                (settings!!.getAuthMethod() != AuthMethod.NONE || encryptionType == EncryptionType.PASSWORD)

        setBroadcastCallback {
            if (settings!!.getRelockOnScreenOff()) onVaultLocked()
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        if (!settings!!.getFirstTimeWarningShown()) {
            if (!introPending) showFirstTimeWarning()
        } else if (!requireAuthentication && !authenticationPending && !introPending) {
            afterAuthentication()
        }

        val dial = requireNotNull(findViewById<SpeedDialView>(R.id.speedDial))
        speedDial = dial
        dial.inflate(R.menu.menu_fab)

        dial.getMainFab().contentDescription = getString(R.string.button_add)

        dial.setOnActionSelectedListener { speedDialActionItem ->
            if (VaultSession.getKey() == null) return@setOnActionSelectedListener false
            val actionId = speedDialActionItem.getId()

            if (actionId == R.id.fabScanQR)
                scanQRCode()
            else if (actionId == R.id.fabEnterDetails)
                ManualEntryDialog.show(this, settings!!, adapter!!)
            else if (actionId == R.id.fabAddSecureNote)
                ManualEntryDialog.showNew(this, settings!!, adapter!!, Entry.OTPType.NOTE)
            else if (actionId == R.id.fabAddSshKey)
                ManualEntryDialog.showNew(this, settings!!, adapter!!, Entry.OTPType.SSH_KEY)
            else if (actionId == R.id.fabImportPasswords)
                choosePasswordImport()
            else if (actionId == R.id.fabAddEmail)
                EmailEntryDialog.show(this) { entry -> adapter?.addEntry(entry) }
            else if (actionId == R.id.fabImportEmail)
                chooseEmailImport()
            else if (actionId == R.id.fabExportEmail)
                chooseEmailExport()
            else if (actionId == R.id.fabScanQRFromImage)
                showOpenFileSelector(Constants.INTENT_MAIN_QR_OPEN_IMAGE)

            false
        }

        dial.setOnChangeListener(object : SpeedDialView.OnChangeListener {
            override fun onMainActionSelected(): Boolean {
                return false
            }

            override fun onToggleChanged(isOpen: Boolean) {
                if (isOpen) {
                    dial.getMainFab().contentDescription = getString(R.string.button_close_menu)
                } else {
                    dial.getMainFab().contentDescription = getString(R.string.button_add)
                }
                setSpeedDialAccessibility(isOpen)
                if (isOpen) prepareExpandedSpeedDial(dial)
            }
        })
        // SpeedDialView may restore its expanded state before this listener is installed.
        setSpeedDialAccessibility(dial.isOpen())
        if (dial.isOpen()) prepareExpandedSpeedDial(dial)

        progressBar = requireNotNull(findViewById<ProgressBar>(R.id.progressBar))
        emptyListView = requireNotNull(findViewById<TextView>(R.id.emptyListView))

        val recList = requireNotNull(findViewById<RecyclerView>(R.id.cardList))
        recList.setHasFixedSize(true)
        val llm = LinearLayoutManager(this)
        llm.setOrientation(LinearLayoutManager.VERTICAL)
        recList.setLayoutManager(llm)

        val drawerAdapter = TagsAdapter(this, HashMap<String, Boolean?>())
        tagsDrawerAdapter = drawerAdapter
        val cardAdapter = EntriesCardAdapter(this, drawerAdapter)
        adapter = cardAdapter
        val categories = requireNotNull(findViewById<TabLayout>(R.id.entry_categories))
        listOf(R.string.tab_otp, R.string.tab_notes, R.string.tab_ssh_keys, R.string.tab_passwords, R.string.tab_email)
            .forEach { categories.addTab(categories.newTab().setText(getString(it))) }
        categories.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val category = EntryListPresentation.Category.entries.getOrNull(tab.position) ?: return
                cardAdapter.setCategory(category)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        val selectedCategory = savedInstanceState?.getInt("entry_category", 0) ?: 0
        categories.getTabAt(selectedCategory)?.select()
        cardAdapter.setCategory(EntryListPresentation.Category.entries.getOrElse(selectedCategory) { EntryListPresentation.Category.OTP })
        cardAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                hideProgressBar()
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                super.onItemRangeChanged(positionStart, itemCount)
                hideProgressBar()
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                super.onItemRangeChanged(positionStart, itemCount, payload)
                hideProgressBar()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                hideProgressBar()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                hideProgressBar()
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                super.onItemRangeMoved(fromPosition, toPosition, itemCount)
                hideProgressBar()
            }
        })

        recList.setAdapter(cardAdapter)

        val callback = SimpleItemTouchHelperCallback(cardAdapter)
        touchHelperCallback = callback
        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(recList)

        NotificationHelper.initializeNotificationChannels(this)
        restoreSortMode()

        var durationScale = AndroidSettings.Global.getFloat(getContentResolver(),
            AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
        if (durationScale == 0f)
            durationScale = 1f

        animatorDuration = (1000 / durationScale).toLong()

        cardAdapter.setCallback(object : EntriesCardAdapter.Callback {
            override fun onMoveEventStart() {
                stopUpdater()
            }

            override fun onMoveEventStop() {
                startUpdater()
            }
        })

        screenTimers = ScreenTimerController(HandlerTimerScheduler(), Runnable { renderTokenTick() },
            Runnable { authenticate(R.string.auth_msg_authenticate) })

        setupDrawer()

        if (savedInstanceState != null) {
            setFilterString(savedInstanceState.getString("filterString", ""))
        }

        if (settings!!.isFocusSearchOnStartEnabled())
            focusSearchMenu()
    }

    private fun checkIntent() {
        val callingIntent = getIntent()
        if (callingIntent != null && callingIntent.getAction() != null) {
            // Cache and reset the action to prevent the same intent from being evaluated multiple times
            val intentAction = callingIntent.getAction()
            val intentData = if (intentAction == Intent.ACTION_VIEW) callingIntent.getDataString() else null
            callingIntent.setAction(null)
            if (intentAction == Intent.ACTION_VIEW) callingIntent.data = null

            when (intentAction) {
                INTENT_SCAN_QR -> scanQRCode()
                INTENT_IMPORT_QR -> showOpenFileSelector(Constants.INTENT_MAIN_QR_OPEN_IMAGE)
                INTENT_ENTER_DETAILS -> ManualEntryDialog.show(this, settings!!, adapter!!)
                Intent.ACTION_VIEW -> {
                    if (GoogleAuthenticatorMigration.isMigration(intentData)) {
                        addMigrationQRCode(intentData)
                        return
                    }
                    if (migrationCollector != null) {
                        Toast.makeText(this, R.string.google_import_expected_qr, Toast.LENGTH_LONG).show()
                        showMigrationProgress()
                        return
                    }
                    var parsedEntry: Entry? = null
                    try {
                        val epoch = VaultSession.generation()
                        val entry = Entry(intentData)
                        parsedEntry = entry
                        showImportConfirmation(entry, epoch, R.string.security_import_title,
                            importPreviewText(entry), android.R.string.ok, R.string.toast_intent_creation_failed)
                        parsedEntry = null // The confirmation owns the entry until it is consumed or dismissed.
                    } catch (_: Exception) {
                        parsedEntry?.destroy()
                        Toast.makeText(this, R.string.toast_intent_creation_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * The confirmation text for an externally supplied entry: the account plus the parameters that
     * change its codes, so the user can see what is about to be added before it is committed.
     */
    private fun importPreviewText(entry: Entry): String {
        val details = if (entry.getType() == Entry.OTPType.MOTP)
            getString(R.string.security_import_motp_details)
        else
            getString(R.string.security_import_details, entry.getType().name,
                entry.getDigits(), entry.getAlgorithm().name)
        val lines = arrayListOf(entry.getIssuer() ?: "", entry.getLabel() ?: "", details)
        if (entry.hasNonDefaultTokenStep())
            lines.add(getString(R.string.security_import_period, entry.getTokenStepSeconds()))
        return lines.filter { it.isNotEmpty() }.joinToString("\n")
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        tagsToggle?.syncState()
    }

    // Controls for the updater background task
    fun stopUpdater() {
        screenTimers?.stopTicks()
    }

    fun startUpdater() {
        screenTimers?.startTicks()
    }

    /**
     * Replaces the cached intent when a new intent arrives on an existing instance so [onResume]
     * evaluates the new action (after authentication if the vault is locked).
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (!isFinishing() && !authenticationPending && adapter?.encryptionKey != null)
            checkIntent()
    }

    public override fun onResume() {
        super.onResume()

        if (isFinishing() || authenticationPending || introPending) return
        if (!settings!!.getFirstTimeWarningShown()) return
        if (requireAuthentication) {
            if (settings!!.getAuthMethod() != AuthMethod.NONE ||
                    settings!!.getEncryption() == EncryptionType.PASSWORD) {
                authenticate(R.string.auth_msg_authenticate)
                return
            }
            requireAuthentication = false
        }
        val currentAdapter = adapter ?: return
        if (currentAdapter.encryptionKey == null) {
            val sessionKey = VaultSession.getKey()
            if (sessionKey != null) {
                currentAdapter.encryptionKey = sessionKey
                populateAdapter()
            } else {
                updateEncryption(null)
            }
        } else {
            populateAdapter()
        }
        if (authenticationPending || currentAdapter.encryptionKey == null || isFinishing()) return
        // The inactivity deadline also covers the time spent in the background or with the
        // screen off, where the timer callback could not run. Lock before handling any intent so
        // an idle-expired session is never used. Re-arm first, so the unlock that this request
        // triggers is not immediately considered idle again.
        if (inactivityRelockExpired()) {
            restartInactivityTimer()
            authenticate(R.string.auth_msg_authenticate)
            return
        }
        currentAdapter.retryPendingAutomaticBackup()
        checkIntent()
        restartInactivityTimer()

        val current = filterString
        if (current != null) {
            // Keep the search filter applied after a resume
            setFilterString(current)
        }

        val cardList = requireNotNull(findViewById<View>(R.id.cardList))
        if (cardList.visibility == View.INVISIBLE)
            cardList.visibility = View.VISIBLE

        startUpdater()
        launchPendingBackupActionIfReady()
        resumePasswordOperation()
    }

    public override fun onPause() {
        if (settings!!.getAuthMethod() == AuthMethod.DEVICE)
            runOnUiThread {
                requireNotNull(findViewById<View>(R.id.cardList)).visibility = View.INVISIBLE
            }
        super.onPause()
        stopUpdater()

        // Keep the idle deadline: time in the background must count towards the timeout.
        screenTimers?.suspendForeground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filterString", filterString)
        outState.putInt("entry_category", findViewById<TabLayout>(R.id.entry_categories)?.selectedTabPosition ?: 0)
        outState.putInt("password_mode", passwordImportMode?.ordinal ?: -1)
        outState.putString("password_export_name", passwordExportName)
        outState.putString("password_export_digest", passwordExportDigest)
        outState.putParcelable("password_pending_uri", passwordPendingUri)
        outState.putInt(STATE_PENDING_BACKUP_ACTION, pendingBackupActionCode ?: INVALID_BACKUP_ACTION)
        outState.putParcelable(STATE_PENDING_BACKUP_URI, pendingBackupUri)
        outState.putBoolean(STATE_PENDING_BACKUP_REPLACE, pendingBackupReplace)
        outState.putBoolean(STATE_AUTH_PENDING, authenticationPending)
        outState.putLong(STATE_AUTH_GENERATION, authenticationGeneration)
        outState.putBoolean(STATE_INTRO_PENDING, introPending)
        outState.putLong(STATE_INTRO_GENERATION, introGeneration)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == null) return
        if (key == getString(R.string.settings_key_label_size) ||
                key == getString(R.string.settings_key_label_display) ||
                key == getString(R.string.settings_key_split_group_size) ||
                key == getString(R.string.settings_key_thumbnail_size) ||
                key == getString(R.string.settings_key_show_individual_timeouts)) {
            adapter?.notifyDataSetChanged()
        } else if (key == getString(R.string.settings_key_search_includes)) {
            adapter?.clearFilter()
            filterString?.let { setFilterString(it) }
        } else if (key == getString(R.string.settings_key_tap_single) ||
                key == getString(R.string.settings_key_tap_double) ||
                key == getString(R.string.settings_key_theme) ||
                key == getString(R.string.settings_key_lang) ||
                key == getString(R.string.settings_key_enable_screenshot) ||
                key == getString(R.string.settings_key_tag_functionality) ||
                key == getString(R.string.settings_key_label_highlight_token) ||
                key == getString(R.string.settings_key_card_layout) ||
                key == getString(R.string.settings_key_theme_mode) ||
                key == getString(R.string.settings_key_theme_black_auto) ||
                key == getString(R.string.settings_key_hide_global_timeout) ||
                key == getString(R.string.settings_key_hide_issuer) ||
                key == getString(R.string.settings_key_show_prev_token)) {
            recreateActivity = true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent)
        if (result != null) {
            val wasAwaiting = awaitingMigrationQr
            awaitingMigrationQr = false
            if (result.getContents() != null) {
                addQRCode(result.getContents())
            } else if (wasAwaiting) {
                showMigrationProgress()
            }
        } else if (requestCode == Constants.INTENT_MAIN_BACKUP) {
            if (resultCode == RESULT_OK && intent != null) {
                if (intent.hasExtra(Constants.EXTRA_BACKUP_PENDING_ACTION)) {
                    val actionCode = intent.getIntExtra(Constants.EXTRA_BACKUP_PENDING_ACTION, INVALID_BACKUP_ACTION)
                    val actionUri = intent.data
                    if (actionCode != INVALID_BACKUP_ACTION && actionUri?.getScheme() == "content") {
                        pendingBackupActionCode = actionCode
                        pendingBackupUri = actionUri
                        pendingBackupReplace = intent.getBooleanExtra(Constants.EXTRA_BACKUP_PENDING_REPLACE, false)
                    } else if (actionUri != null &&
                        (actionCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT ||
                            actionCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP)) {
                        StorageAccessHelper.deleteContentFile(this, actionUri)
                    }
                }
                if (intent.getBooleanExtra("reload", false)) {
                    adapter?.loadEntries()
                    refreshTags()
                }
                launchPendingBackupActionIfReady()
            }
        } else if (requestCode == Constants.INTENT_MAIN_SETTINGS && resultCode == RESULT_OK) {
            val encryptionChanged = intent != null &&
                    intent.getBooleanExtra(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, false)
            if (encryptionChanged) {
                val key = VaultSession.getKey()
                if (key != null) adapter?.encryptionKey = key
                else requireAuthentication = true
            }

            if (recreateActivity) {
                recreate()
            }
        } else if (requestCode == Constants.INTENT_MAIN_AUTHENTICATE) {
            val accepted = AuthenticationResultPolicy.accepts(
                resultOk = resultCode == RESULT_OK,
                requestPending = authenticationPending,
                sessionGenerationCurrent = VaultSession.isCurrent(authenticationGeneration)
            )
            if (!accepted) {
                // Cancellation, stale callbacks, and results without an outstanding challenge all
                // fail closed; the auth activity never receives permission to expose the vault.
                authenticationPending = false
                clearPendingBackupAction(discardCreatedDocument = true)
                VaultSession.lock(SessionLockReason.AUTHENTICATION)
                Toast.makeText(getBaseContext(), R.string.toast_auth_failed_fatal, Toast.LENGTH_LONG).show()
                finishAndRemoveTask()
            } else {
                authenticationPending = false
                requireAuthentication = false
                val authKey = VaultSession.takeDerivedKey(authenticationGeneration)
                try {
                    updateEncryption(authKey, authenticationGeneration)
                } finally {
                    if (authKey != null) Arrays.fill(authKey, 0)
                }
                afterAuthentication()
            }
        } else if (requestCode == Constants.INTENT_MAIN_EMAIL_IMPORT) {
            if (resultCode == RESULT_OK && intent?.data?.getScheme() == "content") {
                emailImportPendingUri = intent.data
                resumeEmailImport()
            }
        } else if (requestCode == Constants.INTENT_MAIN_EMAIL_EXPORT) {
            if (resultCode == RESULT_OK && intent?.data?.getScheme() == "content") saveEmailExport(intent.data!!)
            else { emailExportPending?.fill(0); emailExportPending = null; emailExportGeneration = -1L }
        } else if (requestCode == Constants.INTENT_MAIN_PASSWORD_IMPORT) {
            if (resultCode == RESULT_OK && passwordImportMode != null && intent?.data?.getScheme() == "content") {
                passwordPendingUri = intent.data
                resumePasswordOperation()
            } else {
                passwordImportMode = null
            }
        } else if (requestCode == Constants.INTENT_MAIN_PASSWORD_EXPORT) {
            if (resultCode == RESULT_OK && intent?.data?.getScheme() == "content" &&
                passwordExportName != null && passwordExportDigest != null) {
                passwordPendingUri = intent.data
                resumePasswordOperation()
            } else {
                if (resultCode == RESULT_OK && intent?.data?.getScheme() == "content") StorageAccessHelper.deleteContentFile(this, intent.data)
                passwordExportName = null
                passwordExportDigest = null
            }
        } else if (requestCode == Constants.INTENT_MAIN_QR_OPEN_IMAGE) {
            val wasAwaiting = awaitingMigrationQr
            awaitingMigrationQr = false
            val contents = if (resultCode == RESULT_OK && intent != null)
                ScanQRCodeFromFile.scanQRImage(this, intent.data) else null
            if (contents != null) addQRCode(contents) else if (wasAwaiting) showMigrationProgress()
        } else if (requestCode == Constants.INTENT_MAIN_INTRO) {
            introPending = false
            var setupFinished = false

            if (resultCode == RESULT_OK && intent != null)
                setupFinished = intent.getBooleanExtra(Constants.EXTRA_INTRO_FINISHED, false)

            if (setupFinished) {
                requireAuthentication = false

                val key = VaultSession.takeDerivedKey(introGeneration)
                try {
                    updateEncryption(key, introGeneration)
                } finally {
                    if (key != null) Arrays.fill(key, 0)
                }

                afterAuthentication()
            } else {
                finishAndRemoveTask()
            }
        }
    }

    /** (Re)creates the adapter's encryption key and reloads the entries. */
    private fun updateEncryption(newKey: ByteArray?) {
        updateEncryption(newKey, VaultSession.generation())
    }

    private fun updateEncryption(newKey: ByteArray?, expectedGeneration: Long) {
        synchronized(DatabaseHelper.DatabaseFileLock) {
            if (!VaultSession.isCurrent(expectedGeneration)) {
                vaultUnavailable()
                return
            }
            var encryptionKey: SecretKey? = null

            encryptionType = settings!!.getEncryption()

            if (encryptionType == EncryptionType.KEYSTORE) {
                encryptionKey = KeyStoreHelper.loadEncryptionKeyFromKeyStore(this, false)
            } else if (encryptionType == EncryptionType.PASSWORD) {
                if (newKey != null && newKey.isNotEmpty()) {
                    encryptionKey = EncryptionHelper.generateSymmetricKey(newKey)
                } else {
                    authenticate(R.string.auth_msg_confirm_encryption)
                }
            }

            if (encryptionKey == null) {
                if (!authenticationPending) vaultUnavailable()
                return
            }
            val loaded = DatabaseHelper.readVault(this, encryptionKey)
            if (loaded !is VaultReadResult.Loaded) {
                vaultUnavailable()
                return
            }
            loaded.close()
            if (VaultSession.activate(encryptionKey, expectedGeneration) !is SessionCommandResult.Applied) {
                vaultUnavailable()
                return
            }
            adapter?.encryptionKey = encryptionKey
            populateAdapter()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        getMenuInflater().inflate(R.menu.menu_main, menu)

        sortMenu = requireNotNull(menu.findItem(R.id.menu_sort))

        val currentAdapter = adapter
        if (currentAdapter != null) {
            val mode = currentAdapter.getSortMode()

            if (mode == SortMode.UNSORTED) {
                sortMenu?.setIcon(R.drawable.ic_sort_inverted_white)
                menu.findItem(R.id.menu_sort_none)?.setChecked(true)
            } else if (mode == SortMode.ISSUER) {
                sortMenu?.setIcon(R.drawable.ic_sort_inverted_label_white)
                menu.findItem(R.id.menu_sort_issuer)?.setChecked(true)
            } else if (mode == SortMode.LABEL) {
                sortMenu?.setIcon(R.drawable.ic_sort_inverted_label_white)
                menu.findItem(R.id.menu_sort_label)?.setChecked(true)
            } else if (mode == SortMode.LAST_USED) {
                sortMenu?.setIcon(R.drawable.ic_sort_inverted_time_white)
                menu.findItem(R.id.menu_sort_last_used)?.setChecked(true)
            } else if (mode == SortMode.MOST_USED) {
                sortMenu?.setIcon(R.drawable.ic_sort_inverted_time_white)
                menu.findItem(R.id.menu_sort_most_used)?.setChecked(true)
            }
        }

        val menuSearch = requireNotNull(menu.findItem(R.id.menu_search))
        searchMenu = menuSearch
        val searchView = menuSearch.getActionView() as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                setFilterString(newText ?: "")
                return false
            }
        })

        menuSearch.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(menuItem: MenuItem): Boolean {
                speedDial?.visibility = View.GONE
                touchHelperCallback?.setDragEnabled(false)
                sortMenu?.setVisible(false)
                return true
            }

            override fun onMenuItemActionCollapse(menuItem: MenuItem): Boolean {
                speedDial?.visibility = View.VISIBLE

                if (adapter == null || adapter!!.getSortMode() == SortMode.UNSORTED)
                    touchHelperCallback?.setDragEnabled(true)

                sortMenu?.setVisible(true)

                return true
            }
        })

        if (focusSearchOnCreate) {
            menuSearch.expandActionView()
            focusSearchOnCreate = false
        }

        return true
    }

    /** Expands the search view (deferred to the next menu creation if needed). */
    private fun focusSearchMenu() {
        val menu = searchMenu
        if (menu != null)
            menu.expandActionView()
        else
            focusSearchOnCreate = true
    }

    /** Applies the search text, or the tag filter when the search is empty. */
    private fun setFilterString(newText: String) {
        val currentAdapter = adapter ?: return
        currentAdapter.setSearchQuery(newText)
        filterString = newText
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.getItemId()

        if (id == R.id.action_backup) {
            val backupIntent = Intent(this, BackupActivity::class.java)
            if (VaultSession.getKey() == null) return true
            startActivityForResult(backupIntent, Constants.INTENT_MAIN_BACKUP)
        } else if (id == R.id.action_hardware) {
            if (VaultSession.getKey() == null) return true
            startActivity(Intent(this, HardwareKeyActivity::class.java))
            return true
        } else if (id == R.id.action_settings) {
            val settingsIntent = Intent(this, SettingsActivity::class.java)
            if (VaultSession.getKey() == null) return true
            startActivityForResult(settingsIntent, Constants.INTENT_MAIN_SETTINGS)
        } else if (id == R.id.action_about) {
            val aboutIntent = Intent(this, AboutActivity::class.java)
            startActivity(aboutIntent)
            return true
        } else if (id == R.id.menu_sort_none) {
            item.setChecked(true)
            sortMenu?.setIcon(R.drawable.ic_sort_inverted_white)
            saveSortMode(SortMode.UNSORTED)
            if (adapter != null) {
                adapter!!.setSortMode(SortMode.UNSORTED)
                touchHelperCallback?.setDragEnabled(true)
            }
        } else if (id == R.id.menu_sort_issuer) {
            item.setChecked(true)
            sortMenu?.setIcon(R.drawable.ic_sort_inverted_label_white)
            saveSortMode(SortMode.ISSUER)
            if (adapter != null) {
                adapter!!.setSortMode(SortMode.ISSUER)
                touchHelperCallback?.setDragEnabled(false)
            }
        } else if (id == R.id.menu_sort_label) {
            item.setChecked(true)
            sortMenu?.setIcon(R.drawable.ic_sort_inverted_label_white)
            saveSortMode(SortMode.LABEL)
            if (adapter != null) {
                adapter!!.setSortMode(SortMode.LABEL)
                touchHelperCallback?.setDragEnabled(false)
            }
        } else if (id == R.id.menu_sort_last_used) {
            item.setChecked(true)
            sortMenu?.setIcon(R.drawable.ic_sort_inverted_time_white)
            saveSortMode(SortMode.LAST_USED)
            if (adapter != null) {
                adapter!!.setSortMode(SortMode.LAST_USED)
                touchHelperCallback?.setDragEnabled(false)
            }
            if (!settings!!.getUsedTokensDialogShown())
                showUsedTokensDialog()
        } else if (id == R.id.menu_sort_most_used) {
            item.setChecked(true)
            sortMenu?.setIcon(R.drawable.ic_sort_inverted_time_white)
            saveSortMode(SortMode.MOST_USED)
            if (adapter != null) {
                adapter!!.setSortMode(SortMode.MOST_USED)
                touchHelperCallback?.setDragEnabled(false)
            }
            if (!settings!!.getUsedTokensDialogShown())
                showUsedTokensDialog()
        } else if (tagsToggle?.onOptionsItemSelected(item) == true) {
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    private fun showUsedTokensDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_used_tokens)
            .setMessage(R.string.dialog_msg_used_tokens)
            .setPositiveButton(android.R.string.ok,
                DialogInterface.OnClickListener { _, _ -> settings!!.setUsedTokensDialogShown(true) })
            .create()
            .show()
    }

    private fun setupDrawer() {
        val drawerList = requireNotNull(findViewById<ListView>(R.id.tags_list_in_drawer))
        tagsDrawerListView = drawerList
        val drawerLayout = requireNotNull(findViewById<DrawerLayout>(R.id.drawer_layout))
        tagsDrawerLayout = drawerLayout

        val toggle = object : ActionBarDrawerToggle(this, drawerLayout, R.string.drawer_open, R.string.drawer_close) {
            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)

                getSupportActionBar()?.setTitle(R.string.label_tags)

                invalidateOptionsMenu()
            }

            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)

                getSupportActionBar()?.setTitle(R.string.app_name)

                invalidateOptionsMenu()
            }
        }
        tagsToggle = toggle

        toggle.setDrawerIndicatorEnabled(true)
        drawerLayout.addDrawerListener(toggle)

        val noTagsButton = requireNotNull(findViewById<CheckedTextView>(R.id.no_tags_entries))
        val allTagsButton = requireNotNull(findViewById<CheckedTextView>(R.id.all_tags_in_drawer))
        val drawerAdapter = requireNotNull(tagsDrawerAdapter)

        allTagsButton.setOnClickListener { view ->
            val checkedTextView = view as CheckedTextView
            checkedTextView.isChecked = !checkedTextView.isChecked

            settings!!.setAllTagsToggle(checkedTextView.isChecked)

            for (i in 0 until drawerList.childCount) {
                val childCheckBox = drawerList.getChildAt(i) as CheckedTextView
                childCheckBox.isChecked = checkedTextView.isChecked
            }

            for (tag in drawerAdapter.getTags()) {
                drawerAdapter.setTagState(tag, checkedTextView.isChecked)
                settings!!.setTagToggle(tag, checkedTextView.isChecked)
            }

            if (checkedTextView.isChecked &&
                    settings!!.getTagFunctionality() == Constants.TagFunctionality.SINGLE &&
                    !noTagsButton.isChecked) {
                noTagsButton.isChecked = true
                settings!!.setNoTagsToggle(true)
            }

            if (checkedTextView.isChecked) {
                adapter?.filterByTags(drawerAdapter.getActiveTags())
            } else {
                adapter?.filterByTags(ArrayList())
            }
        }
        allTagsButton.isChecked = settings!!.getAllTagsToggle()

        noTagsButton.setOnClickListener { view ->
            val checkedTextView = view as CheckedTextView
            checkedTextView.isChecked = !checkedTextView.isChecked

            if (settings!!.getTagFunctionality() == Constants.TagFunctionality.SINGLE) {
                checkedTextView.isChecked = true
                allTagsButton.isChecked = false
                settings!!.setAllTagsToggle(false)

                for (tag in drawerAdapter.getTags()) {
                    settings!!.setTagToggle(tag, false)
                    drawerAdapter.setTagState(tag, false)
                }
            }

            settings!!.setNoTagsToggle(checkedTextView.isChecked)
            adapter?.filterByTags(drawerAdapter.getActiveTags())
        }
        noTagsButton.isChecked = settings!!.getNoTagsToggle()

        drawerList.adapter = drawerAdapter
        drawerList.onItemClickListener = AdapterView.OnItemClickListener { _, view, position, _ ->
            val checkedTextView = view as CheckedTextView
            // The row text carries the entry count (#994, "tag (3)"), so the clicked tag is
            // resolved by position; parsing the text stored a toggle for a tag that does not exist
            // and left the filter unchanged.
            val tag = drawerAdapter.getItem(position) ?: return@OnItemClickListener

            if (settings!!.getTagFunctionality() == Constants.TagFunctionality.SINGLE) {
                allTagsButton.isChecked = false
                settings!!.setAllTagsToggle(false)
                noTagsButton.isChecked = false
                settings!!.setNoTagsToggle(false)

                for (tag in drawerAdapter.getTags()) {
                    settings!!.setTagToggle(tag, false)
                    drawerAdapter.setTagState(tag, false)
                }
                checkedTextView.isChecked = true
            } else {
                checkedTextView.isChecked = !checkedTextView.isChecked
            }

            settings!!.setTagToggle(tag, checkedTextView.isChecked)
            drawerAdapter.setTagState(tag, checkedTextView.isChecked)

            if (!checkedTextView.isChecked) {
                allTagsButton.isChecked = false
                settings!!.setAllTagsToggle(false)
            }

            if (settings!!.getTagFunctionality() != Constants.TagFunctionality.SINGLE &&
                    drawerAdapter.allTagsActive()) {
                allTagsButton.isChecked = true
                settings!!.setAllTagsToggle(true)
            }

            adapter?.filterByTags(drawerAdapter.getActiveTags())
        }

        adapter?.filterByTags(drawerAdapter.getActiveTags())
    }

    /** Rebuilds the tag drawer after entries were added or removed. */
    fun refreshTags() {
        val drawerAdapter = tagsDrawerAdapter ?: return
        val tagsHashMap = HashMap<String, Boolean?>()
        for (tag in drawerAdapter.getTags()) {
            tagsHashMap[tag] = false
        }
        for (tag in drawerAdapter.getActiveTags()) {
            tagsHashMap[tag] = true
        }
        val activateNewTags = settings!!.getAllTagsToggle() ||
            settings!!.getTagFunctionality() != Constants.TagFunctionality.SINGLE
        for (tag in adapter?.getTags() ?: ArrayList()) {
            if (!tagsHashMap.containsKey(tag))
                tagsHashMap[tag] = activateNewTags
        }
        drawerAdapter.setTags(tagsHashMap)
        // Recompute the counts: a restored/imported entry set changes them, and a tag that is new
        // to the drawer has no count yet (#994).
        adapter?.let { drawerAdapter.setTagCounts(it.getTagCounts()) }
        adapter?.filterByTags(drawerAdapter.getActiveTags())
    }

    public override fun onUserInteraction() {
        super.onUserInteraction()
        restartInactivityTimer()
    }

    /** The screen translates preferences to policy; the timer owns only scheduling. */
    private fun restartInactivityTimer() {
        val timers = screenTimers ?: return
        timers.restartInactivity(relockInactivityDelayMillis())
    }

    /** Zero when inactivity relocking is not configured; the timer treats it as disabled. */
    private fun relockInactivityDelayMillis(): Long {
        if (settings!!.getAuthMethod() == AuthMethod.NONE || !settings!!.getAuthInactivity())
            return 0L
        return 1000L * settings!!.getAuthInactivityDelay()
    }

    /** True when the idle deadline passed while the screen could not run the timer callback. */
    private fun inactivityRelockExpired(): Boolean {
        val timers = screenTimers ?: return false
        return relockInactivityDelayMillis() > 0L && timers.hasInactivityExpired()
    }

    /** Rendering stays in the Activity; scheduling and teardown do not. */
    private fun renderTokenTick() {
        if (!settings!!.isHideGlobalTimeoutEnabled()) {
            val progress = (TokenCalculator.TOTP_DEFAULT_PERIOD -
                    (System.currentTimeMillis() / 1000) % TokenCalculator.TOTP_DEFAULT_PERIOD).toInt()
            val bar = progressBar
            if (bar != null) {
                bar.progress = progress * 100
                timeoutAnimator?.cancel()
                val animation = ObjectAnimator.ofInt(bar, "progress", (progress - 1) * 100)
                animation.setDuration(animatorDuration)
                animation.setInterpolator(LinearInterpolator())
                timeoutAnimator = animation
                animation.start()
            }
        }
        adapter?.updateTimeBasedTokens()
    }

    fun showEntryCategory(entry: Entry) {
        val category = when {
            entry.isEmail() -> EntryListPresentation.Category.EMAILS
            entry.isPassword() || entry.isPasswordArchive() -> EntryListPresentation.Category.PASSWORDS
            entry.isNote() -> EntryListPresentation.Category.NOTES
            entry.isSshKey() -> EntryListPresentation.Category.SSH_KEYS
            else -> EntryListPresentation.Category.OTP
        }
        findViewById<TabLayout>(R.id.entry_categories)?.getTabAt(category.ordinal)?.select()
    }

    private fun chooseEmailImport() {
        if (VaultSession.getKey() == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(intent, Constants.INTENT_MAIN_EMAIL_IMPORT) }
        catch (_: Exception) { Toast.makeText(this, R.string.email_import_failed, Toast.LENGTH_LONG).show() }
    }

    private fun chooseEmailExport() {
        val emailEntries = adapter?.getEntries()?.filter { it.isEmail() } ?: emptyList()
        if (emailEntries.isEmpty()) {
            Toast.makeText(this, R.string.email_export_empty, Toast.LENGTH_LONG).show()
            return
        }
        val selectionGeneration = VaultSession.generation()
        val selected = BooleanArray(emailEntries.size)
        val labels = emailEntries.map { "${it.getLabel()} (${it.getIssuer()})" }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.email_export_select)
            .setMessage(R.string.email_export_warning)
            .setMultiChoiceItems(labels, selected) { _, index, checked -> selected[index] = checked }
            .setPositiveButton(R.string.email_export) { _, _ ->
                if (!VaultSession.isCurrent(selectionGeneration) || VaultSession.getKey() == null) return@setPositiveButton
                val chosen = emailEntries.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) return@setPositiveButton
                val accounts = try { chosen.map { it.getEmailAccount() } }
                    catch (_: Exception) { Toast.makeText(this, R.string.email_export_failed, Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val file = try { EmailMaterial.exportBackup(accounts) }
                    catch (_: Exception) { Toast.makeText(this, R.string.email_export_failed, Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val uriIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "fairemail-accounts.json")
                try {
                    emailExportPending = file
                    emailExportGeneration = VaultSession.generation()
                    startActivityForResult(uriIntent, Constants.INTENT_MAIN_EMAIL_EXPORT)
                } catch (_: Exception) {
                    file.fill(0); emailExportPending = null; emailExportGeneration = -1L
                    Toast.makeText(this, R.string.email_export_failed, Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).create()
        emailExportSelectionDialog = dialog
        dialog.setOnDismissListener { if (emailExportSelectionDialog === dialog) emailExportSelectionDialog = null }
        dialog.show()
    }

    private var emailExportPending: ByteArray? = null
    private var emailExportGeneration: Long = -1L
    private var emailImportPendingUri: Uri? = null
    private var emailImportPrompt: AlertDialog? = null
    private var emailImportBytes: ByteArray? = null
    private var emailExportSelectionDialog: AlertDialog? = null

    private fun resumeEmailImport() {
        val uri = emailImportPendingUri ?: return
        val current = adapter ?: return
        if (authenticationPending || introPending || VaultSession.getKey() == null ||
            current.encryptionKey !== VaultSession.getKey() || inactivityRelockExpired()) return
        emailImportPendingUri = null
        importEmailDocument(uri)
    }

    private fun importEmailDocument(uri: Uri) {
        val bytes = try {
            getContentResolver().openInputStream(uri).use { BoundedInputReader.read(it, 8 * 1024 * 1024) }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.email_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            importEmailBytes(bytes, null)
            bytes.fill(0)
        } catch (_: Exception) {
            // FairEmail's normal export can be password encrypted. Prompt only when a plain/gzip
            // import did not parse; password widgets are transient and never saved in view state.
            val password = android.widget.EditText(this).apply {
                isSaveEnabled = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            emailImportBytes = bytes
            val dialog = AlertDialog.Builder(this).setTitle(R.string.email_backup_password)
                .setView(password)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try { importEmailBytes(bytes, password.text.toString()) }
                    catch (_: Exception) { Toast.makeText(this, R.string.email_import_failed, Toast.LENGTH_LONG).show() }
                }.setNegativeButton(android.R.string.cancel, null)
                .create()
            emailImportPrompt = dialog
            dialog.setOnDismissListener {
                password.setText("")
                bytes.fill(0)
                if (emailImportBytes === bytes) emailImportBytes = null
                if (emailImportPrompt === dialog) emailImportPrompt = null
            }
            dialog.show()
        }
    }

    private fun importEmailBytes(bytes: ByteArray, password: String?) {
        val incoming = ArrayList<Entry>()
        try {
            for (account in EmailMaterial.importBackup(bytes, password))
                incoming.add(Entry.email(account.account, account.identity, account.identities))
            val result = adapter?.importEntries(incoming)
            if (result == null) throw IllegalStateException("Vault unavailable")
            Toast.makeText(this, R.string.email_import_success, Toast.LENGTH_LONG).show()
            findViewById<TabLayout>(R.id.entry_categories)?.getTabAt(EntryListPresentation.Category.EMAILS.ordinal)?.select()
        } catch (error: Exception) {
            incoming.forEach { it.destroy() }
            throw error
        }
    }

    private fun saveEmailExport(uri: Uri) {
        val bytes = emailExportPending ?: return
        emailExportPending = null
        val generation = emailExportGeneration
        emailExportGeneration = -1L
        val authorized = VaultSession.isCurrent(generation) && VaultSession.getKey() != null
        val saved = authorized && try {
            StorageAccessHelper.saveFile(this, uri, bytes, java.util.function.BooleanSupplier {
                VaultSession.isCurrent(generation) && VaultSession.getKey() != null
            })
        } catch (_: Exception) { false }
        bytes.fill(0)
        if (!saved) StorageAccessHelper.deleteContentFile(this, uri)
        Toast.makeText(this, if (saved) R.string.email_export_success else R.string.email_export_failed, Toast.LENGTH_LONG).show()
    }

    private fun choosePasswordImport() {
        if (VaultSession.getKey() == null) return
        val generation = VaultSession.generation()
        AlertDialog.Builder(this).setTitle(R.string.password_import_prompt)
            .setItems(arrayOf(getString(R.string.password_import_individual), getString(R.string.password_import_archive))) { _, index ->
                if (!VaultSession.isCurrent(generation) || VaultSession.getKey() == null) return@setItems
                passwordExportName = null
                passwordExportDigest = null
                passwordImportMode = if (index == 0) PasswordImport.Mode.INDIVIDUAL else PasswordImport.Mode.ARCHIVE
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                try { startActivityForResult(intent, Constants.INTENT_MAIN_PASSWORD_IMPORT) }
                catch (_: Exception) {
                    passwordImportMode = null
                    Toast.makeText(this, R.string.password_import_failed, Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** A picker may outlive a lock/recreation. Only resume after a fresh vault unlock. */
    private fun resumePasswordOperation() {
        val uri = passwordPendingUri ?: return
        val current = adapter ?: return
        if (authenticationPending || introPending || VaultSession.getKey() == null ||
            current.encryptionKey !== VaultSession.getKey() || inactivityRelockExpired()) return
        passwordPendingUri = null
        val mode = passwordImportMode
        if (mode != null) {
            passwordImportMode = null
            importPasswordDocument(uri, mode)
            return
        }
        val name = passwordExportName
        val digest = passwordExportDigest
        passwordExportName = null
        passwordExportDigest = null
        val generation = VaultSession.generation()
        val entry = if (name != null && digest != null) current.findPasswordArchive(name, digest) else null
        val bytes = if (entry != null) current.passwordArchiveForExport(entry, generation) else null
        val saved = if (bytes == null) false else try {
            StorageAccessHelper.saveFile(this, uri, bytes, java.util.function.BooleanSupplier {
                !inactivityRelockExpired() && current.canExportPasswordArchive(entry!!, generation)
            })
        } finally { bytes.fill(0) }
        if (!saved) StorageAccessHelper.deleteContentFile(this, uri)
        Toast.makeText(this, if (saved) R.string.password_export_success else R.string.password_export_failed, Toast.LENGTH_LONG).show()
    }

    private fun importPasswordDocument(uri: Uri?, mode: PasswordImport.Mode) {
        var bytes: ByteArray? = null
        try {
            bytes = getContentResolver().openInputStream(uri!!).use {
                BoundedInputReader.read(it, BrowserPasswordCsv.MAX_FILE_BYTES)
            }
            val filename = StorageAccessHelper.getContentFileName(this, uri).ifBlank { "passwords.csv" }
            val incoming = PasswordImport.prepare(bytes, filename, mode)
            val outcome = adapter?.importEntries(incoming) ?: run {
                incoming.forEach { it.destroy() }
                throw IllegalStateException()
            }
            Toast.makeText(this, R.string.password_import_success, Toast.LENGTH_LONG).show()
            findViewById<TabLayout>(R.id.entry_categories)?.getTabAt(EntryListPresentation.Category.PASSWORDS.ordinal)?.select()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.password_import_failed, Toast.LENGTH_LONG).show()
        } finally { bytes?.fill(0) }
    }

    /** Called only from a revealed archive's action menu. */
    fun requestPasswordArchiveExport(entry: Entry) {
        val generation = VaultSession.generation()
        if (adapter?.canExportPasswordArchive(entry, generation) != true) return
        AlertDialog.Builder(this).setTitle(R.string.password_export).setMessage(R.string.password_export_warning)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (adapter?.canExportPasswordArchive(entry, generation) != true) return@setPositiveButton
                val digest = adapter?.passwordArchiveDigest(entry, generation) ?: return@setPositiveButton
                passwordImportMode = null
                passwordExportName = entry.getLabel()
                passwordExportDigest = digest
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, entry.getLabel())
                try { startActivityForResult(intent, Constants.INTENT_MAIN_PASSWORD_EXPORT) }
                catch (_: Exception) {
                    passwordExportName = null
                    passwordExportDigest = null
                    Toast.makeText(this, R.string.password_export_failed, Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Suppress("SameParameterValue")
    private fun showOpenFileSelector(intentId: Int): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("image/*")
        return try {
            startActivityForResult(intent, intentId)
            true
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
            false
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Imports a standard provisioning QR or a Google Authenticator export QR. */
    private fun addQRCode(result: String?) {
        if (TextUtils.isEmpty(result)) {
            if (migrationCollector != null) showMigrationProgress()
            return
        }
        if (GoogleAuthenticatorMigration.isMigration(result)) {
            addMigrationQRCode(result)
            return
        }
        if (migrationCollector != null) {
            Toast.makeText(this, R.string.google_import_expected_qr, Toast.LENGTH_LONG).show()
            showMigrationProgress()
            return
        }
        var parsedEntry: Entry? = null
        val epoch = VaultSession.generation()
        try {
            val entry = Entry(result)
            parsedEntry = entry
            synchronized(VaultSession.MutationLock) {
                val currentAdapter = adapter
                val key = VaultSession.getKey()
                if (!VaultSession.isCurrent(epoch) || key == null || currentAdapter == null ||
                        currentAdapter.encryptionKey !== key) {
                    entry.destroy()
                } else {
                    val issuer = entry.getIssuer().trim()
                    if (currentAdapter.hasIssuer(issuer)) {
                        showDuplicateIssuerConfirmation(entry, issuer)
                    } else {
                        saveImportedEntry(entry, epoch, R.string.toast_invalid_qr_code)
                    }
                }
                parsedEntry = null // The dialog or save path has consumed the entry.
            }
        } catch (_: Exception) {
            parsedEntry?.destroy()
            Toast.makeText(this, R.string.toast_invalid_qr_code, Toast.LENGTH_LONG).show()
        }
    }

    /** Accumulates only matching export parts and never commits a partial batch. */
    private fun addMigrationQRCode(contents: String?) {
        val epoch = VaultSession.generation()
        val parsed = GoogleAuthenticatorMigration.parse(contents)
        if (parsed is MigrationParseResult.Failure) {
            val message = if (parsed.error == MigrationError.UNSUPPORTED)
                R.string.google_import_unsupported else R.string.google_import_invalid
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (migrationCollector != null) showMigrationProgress()
            return
        }
        val payload = (parsed as MigrationParseResult.Success).payload
        synchronized(VaultSession.MutationLock) {
            val key = VaultSession.getKey()
            if (isFinishing() || !VaultSession.isCurrent(epoch) || key == null || adapter?.encryptionKey !== key) {
                payload.close()
                dismissMigrationImport()
                return
            }
            var collector = migrationCollector
            if (collector == null) {
                dismissPendingImport()
                dismissMigrationImport()
                collector = MigrationBatchCollector(epoch)
                migrationCollector = collector
            }
            when (collector.add(payload, VaultSession.generation(), true)) {
                MigrationBatchStatus.ADDED -> showMigrationProgress()
                MigrationBatchStatus.DUPLICATE -> {
                    Toast.makeText(this, R.string.google_import_already_scanned, Toast.LENGTH_LONG).show()
                    showMigrationProgress()
                }
                MigrationBatchStatus.DIFFERENT_EXPORT -> {
                    Toast.makeText(this, R.string.google_import_wrong_batch, Toast.LENGTH_LONG).show()
                    showMigrationProgress()
                }
                MigrationBatchStatus.TOO_MANY -> {
                    dismissMigrationImport()
                    Toast.makeText(this, R.string.google_import_too_many, Toast.LENGTH_LONG).show()
                }
                MigrationBatchStatus.STALE -> dismissMigrationImport()
                MigrationBatchStatus.COMPLETE -> {
                    val accounts = try {
                        collector.takeAccounts(VaultSession.generation(), true)
                    } catch (_: RuntimeException) {
                        dismissMigrationImport()
                        Toast.makeText(this, R.string.google_import_invalid, Toast.LENGTH_LONG).show()
                        return
                    }
                    migrationCollector = null
                    if (accounts == null) {
                        dismissMigrationImport()
                        return
                    }
                    val entries = ArrayList<Entry>()
                    try {
                        accounts.forEach { entries.add(Entry(it)) }
                        showMigrationConfirmation(entries, epoch)
                    } catch (_: RuntimeException) {
                        entries.forEach { it.destroy() }
                        Toast.makeText(this, R.string.google_import_invalid, Toast.LENGTH_LONG).show()
                    } finally {
                        accounts.forEach { it.close() }
                    }
                }
            }
        }
    }

    /** Offers either camera or image input for the next part; cancellation wipes the batch. */
    private fun showMigrationProgress() {
        val collector = migrationCollector ?: return
        if (isFinishing() || VaultSession.getKey() == null || adapter?.encryptionKey !== VaultSession.getKey()) {
            dismissMigrationImport()
            return
        }
        val previous = activeMigrationDialog
        activeMigrationDialog = null
        previous?.dismiss()
        var continuing = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.google_import_title)
            .setMessage(getString(R.string.google_import_progress, collector.received, collector.expected))
            .setPositiveButton(R.string.button_scan_qr) { _, _ ->
                if (migrationCollector !== collector || VaultSession.getKey() == null) {
                    dismissMigrationImport()
                } else {
                    continuing = true
                    awaitingMigrationQr = true
                    try {
                        scanQRCode()
                    } catch (_: RuntimeException) {
                        awaitingMigrationQr = false
                        Toast.makeText(this, R.string.google_import_scan_failed, Toast.LENGTH_LONG).show()
                        showMigrationProgress()
                    }
                }
            }
            .setNeutralButton(R.string.button_qr_from_image) { _, _ ->
                if (migrationCollector !== collector || VaultSession.getKey() == null) {
                    dismissMigrationImport()
                } else {
                    continuing = true
                    awaitingMigrationQr = true
                    if (!showOpenFileSelector(Constants.INTENT_MAIN_QR_OPEN_IMAGE)) {
                        awaitingMigrationQr = false
                        showMigrationProgress()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        activeMigrationDialog = dialog
        dialog.setOnDismissListener {
            if (activeMigrationDialog === dialog) {
                activeMigrationDialog = null
                if (!continuing) dismissMigrationImport()
            }
        }
        dialog.show()
    }

    /** Displays account names but never the exported secrets. */
    private fun migrationPreview(entries: List<Entry>): String {
        fun safeText(value: String): String = value.filterNot {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }.trim().take(100)
        val names = entries.take(12).map { entry ->
            val parts = listOf(safeText(entry.getIssuer()), safeText(entry.getLabel())).filter { it.isNotEmpty() }
            if (parts.isEmpty()) getString(R.string.google_import_unnamed) else parts.joinToString(" — ")
        }.toMutableList()
        if (entries.size > names.size) names.add(getString(R.string.google_import_more, entries.size - names.size))
        return getString(R.string.google_import_confirm, entries.size) + "\n\n" + names.joinToString("\n") +
            "\n\n" + getString(R.string.google_import_duplicate_notice)
    }

    /** Owns the complete batch until confirmation, dismissal, activity destruction or lock. */
    private fun showMigrationConfirmation(entries: ArrayList<Entry>, epoch: Long) {
        synchronized(VaultSession.MutationLock) {
            val key = VaultSession.getKey()
            if (isFinishing() || !VaultSession.isCurrent(epoch) || key == null || adapter?.encryptionKey !== key) {
                entries.forEach { it.destroy() }
                return
            }
            val previous = activeMigrationDialog
            activeMigrationDialog = null
            previous?.dismiss()
            val pending = PendingMigrationImport(entries, epoch)
            try {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.google_import_title)
                    .setMessage(migrationPreview(entries))
                    .setPositiveButton(R.string.google_import_button) { _, _ ->
                        synchronized(VaultSession.MutationLock) {
                            val currentKey = VaultSession.getKey()
                            val currentAdapter = adapter
                            val authorized = !isFinishing() && currentKey != null &&
                                currentAdapter != null && currentAdapter.encryptionKey === currentKey
                            val confirmed = pending.take(VaultSession.generation(), authorized)
                            if (confirmed != null && currentAdapter != null) {
                                try {
                                    val outcome = currentAdapter.importEntries(confirmed)
                                    if (outcome == null) {
                                        Toast.makeText(this, R.string.google_import_save_failed, Toast.LENGTH_LONG).show()
                                    } else {
                                        refreshTags()
                                        Toast.makeText(this, getString(R.string.google_import_finished,
                                            outcome.added, outcome.skipped), Toast.LENGTH_LONG).show()
                                    }
                                } catch (_: RuntimeException) {
                                    Toast.makeText(this, R.string.google_import_save_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                pendingMigrationImport = pending
                activeMigrationDialog = dialog
                dialog.setOnDismissListener {
                    pending.close()
                    if (pendingMigrationImport === pending) pendingMigrationImport = null
                    if (activeMigrationDialog === dialog) activeMigrationDialog = null
                }
                dialog.show()
            } catch (error: RuntimeException) {
                if (pendingMigrationImport === pending) dismissMigrationImport() else pending.close()
                throw error
            }
        }
    }

    /** Clears every owned account before a dialog can deliver a delayed callback. */
    private fun dismissMigrationImport() {
        awaitingMigrationQr = false
        migrationCollector?.close()
        migrationCollector = null
        pendingMigrationImport?.close()
        pendingMigrationImport = null
        val dialog = activeMigrationDialog
        activeMigrationDialog = null
        dialog?.dismiss()
    }

    private fun showDuplicateIssuerConfirmation(entry: Entry, issuer: String) {
        showImportConfirmation(entry, VaultSession.generation(), R.string.dialog_title_duplicate_issuer,
            getString(R.string.dialog_msg_duplicate_issuer, issuer), R.string.button_add_anyway,
            R.string.toast_invalid_qr_code)
    }

    /** Owns the parsed entry until it is confirmed, cancelled, dismissed, locked or destroyed. */
    private fun showImportConfirmation(entry: Entry, epoch: Long, title: Int, message: String,
                                       positiveButton: Int, failureMessage: Int) {
        synchronized(VaultSession.MutationLock) {
            val key = VaultSession.getKey()
            if (isFinishing() || !VaultSession.isCurrent(epoch) || key == null ||
                    adapter?.encryptionKey !== key) {
                entry.destroy()
                return
            }
            dismissPendingImport()
            val pending = PendingEntryImport(entry, epoch)
            try {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positiveButton) { _, _ ->
                        synchronized(VaultSession.MutationLock) {
                            val currentKey = VaultSession.getKey()
                            val authorized = !isFinishing() && currentKey != null &&
                                    adapter?.encryptionKey === currentKey
                            val confirmed = pending.take(VaultSession.generation(), authorized)
                            if (confirmed != null) saveImportedEntry(confirmed, epoch, failureMessage)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                pendingImport = pending
                activeImportDialog = dialog
                dialog.setOnDismissListener {
                    pending.close()
                    if (pendingImport === pending) pendingImport = null
                    if (activeImportDialog === dialog) activeImportDialog = null
                }
                dialog.show()
            } catch (error: RuntimeException) {
                if (pendingImport === pending) dismissPendingImport() else pending.close()
                throw error
            }
        }
    }

    /** Clears the secret before dismissing; Android may deliver the dismiss callback later. */
    private fun dismissPendingImport() {
        pendingImport?.close()
        pendingImport = null
        val dialog = activeImportDialog
        activeImportDialog = null
        dialog?.dismiss()
    }

    /** Takes ownership and rechecks the original session at the mutation boundary. */
    private fun saveImportedEntry(entry: Entry, epoch: Long, failureMessage: Int) {
        var transferredToAdapter = false
        try {
            synchronized(VaultSession.MutationLock) {
                val currentAdapter = adapter
                val key = VaultSession.getKey()
                if (isFinishing() || !VaultSession.isCurrent(epoch) || key == null ||
                        currentAdapter == null || currentAdapter.encryptionKey !== key) {
                    entry.destroy()
                    return
                }
                entry.updateOTP(false)
                entry.setLastUsed(System.currentTimeMillis())
                currentAdapter.addEntry(entry)
                transferredToAdapter = true
                refreshTags()
            }
        } catch (_: Exception) {
            if (!transferredToAdapter) entry.destroy()
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
        }
    }

    /** Locks the app when it is sent to the background (if enabled). */
    private inner class ProcessLifecycleObserver : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // No key is loaded while the system device-credential challenge is pending.
            if (settings!!.getRelockOnBackground() &&
                    !(authenticationPending && settings!!.getAuthMethod() == AuthMethod.DEVICE))
                VaultSession.lock(SessionLockReason.BACKGROUND)
        }
    }

    public override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (speedDial?.isOpen() == true) {
                speedDial?.close()
                return true
            }

            if (tagsDrawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
                tagsDrawerLayout?.closeDrawer(GravityCompat.START)
                return true
            }

            return super.onKeyDown(keyCode, event)
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        dismissPendingImport()
        dismissMigrationImport()
        timeoutAnimator?.cancel()
        timeoutAnimator = null
        settings!!.unregisterPreferenceChangeListener(this)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        adapter?.lockEntries()
        screenTimers?.close()
        if (isFinishing()) {
            if (passwordExportDigest != null && passwordPendingUri != null)
                StorageAccessHelper.deleteContentFile(this, passwordPendingUri)
            VaultSession.lock()
        }
        super.onDestroy()
    }

    /**
     * Hides the progress bar (also when the global timeout is turned off) and shows the
     * "how to add tokens" hint while the list is empty.
     */
    private fun hideProgressBar() {
        val itemCount = adapter?.getItemCount() ?: 0
        progressBar?.visibility =
            if (settings!!.isHideGlobalTimeoutEnabled() || itemCount <= 0) View.GONE else View.VISIBLE
        emptyListView?.visibility = if (itemCount > 0) View.GONE else View.VISIBLE
    }

    override fun shouldDestroyOnScreenOff(): Boolean {
        return false
    }

    companion object {
        private const val STATE_PENDING_BACKUP_ACTION = "pending_backup_action"
        private const val STATE_PENDING_BACKUP_URI = "pending_backup_uri"
        private const val STATE_PENDING_BACKUP_REPLACE = "pending_backup_replace"
        private const val STATE_AUTH_PENDING = "main_auth_pending"
        private const val STATE_AUTH_GENERATION = "main_auth_generation"
        private const val STATE_INTRO_PENDING = "main_intro_pending"
        private const val STATE_INTRO_GENERATION = "main_intro_generation"
        private const val INVALID_BACKUP_ACTION = -1
        private const val INTENT_SCAN_QR = "org.shadowice.flocke.andotp.intent.SCAN_QR"
        private const val INTENT_IMPORT_QR = "org.shadowice.flocke.andotp.intent.IMPORT_QR"
        private const val INTENT_ENTER_DETAILS = "org.shadowice.flocke.andotp.intent.ENTER_DETAILS"

        /** Duration of token progress bar animations, scaled by the system animator duration scale. */
        @JvmField
        var animatorDuration: Long = 1000
    }
}
