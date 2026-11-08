package com.fairuniversal.offlineotp.desktop.ui

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.Database.EntryTagFilter
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.View.EntryListPresentation
import com.fairuniversal.offlineotp.desktop.control.BackupCoordinator
import com.fairuniversal.offlineotp.desktop.control.DesktopVaultState
import com.fairuniversal.offlineotp.desktop.control.EntryEditor
import com.fairuniversal.offlineotp.desktop.control.HardwareKeyManager
import com.fairuniversal.offlineotp.desktop.control.TokenTicker
import com.fairuniversal.offlineotp.desktop.control.VaultController
import com.fairuniversal.offlineotp.desktop.model.EntryTableModel
import com.fairuniversal.offlineotp.desktop.qr.QrCodeImage

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Builds the top-level Swing window on the event dispatch thread. The shared Windows/Debian shell
 * presents the privacy-aware table model as Dark Forest cards; vault, entry, backup and timer
 * behavior remains in the headless core/control classes.
 */
object MainWindow {
    const val TITLE = "OfflineOTP"

    fun createFrame(controller: VaultController, hardware: HardwareKeyManager): JFrame {
        ForestTheme.install()
        val table = JTable(EntryTableModel(EntryListPresentation(EntryList()).apply {
            showReorderedEntries()
        }))
        EntryCards.configure(table)
        val screen = MainScreen(table, JButton("Copy selected"))
        val search = screen.search

        lateinit var frame: JFrame
        var state: DesktopVaultState? = null
        var ticker: TokenTicker? = null
        var coordinator: BackupCoordinator? = null
        var editor: EntryEditor? = null
        var clipboardClearTimer: Timer? = null
        var lastCopiedClipboardSecret: String? = null
        var updatingTagChoices = false
        var updatingSortChoice = false

        fun scheduleClipboardClear(secret: String) {
            lastCopiedClipboardSecret = secret
            clipboardClearTimer?.stop()
            clipboardClearTimer = Timer(30_000) {
                val expected = lastCopiedClipboardSecret ?: return@Timer
                try {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val current = clipboard.getData(DataFlavor.stringFlavor) as? String
                    if (current == expected) {
                        clipboard.setContents(StringSelection(""), null)
                    }
                } catch (_: Exception) {
                    // Ignore unavailable clipboard or non-text flavors.
                } finally {
                    if (lastCopiedClipboardSecret == expected) lastCopiedClipboardSecret = null
                }
            }.apply {
                isRepeats = false
                start()
            }
        }

        fun armInactivityTimer() {
            val seconds = controller.savedInactivityLockSeconds()
            if (state != null && seconds > 0) {
                ticker?.restartInactivity(seconds * 1000L)
            } else {
                ticker?.cancelInactivity()
            }
        }

        fun showError(failure: VaultController.Failure) {
            val message = when (failure) {
                VaultController.Failure.MISSING -> "No vault found in the configured directory."
                VaultController.Failure.AUTHENTICATION_FAILED -> "Wrong password or a damaged vault."
                VaultController.Failure.CORRUPT -> "The vault or its settings are corrupt."
                VaultController.Failure.IO_FAILURE -> "Vault storage could not be read or written."
                VaultController.Failure.ALREADY_EXISTS -> "A vault already exists in this directory."
                VaultController.Failure.INVALID_INPUT -> "The password or vault content is invalid."
            }
            JOptionPane.showMessageDialog(frame, message, TITLE, JOptionPane.ERROR_MESSAGE)
        }

        /** Rebuild from the actual vault after imports, edits and restores. */
        fun refreshTagChoices(unlocked: DesktopVaultState, reset: Boolean = false) {
            val previous = if (reset) null else screen.tagFilter.selectedItem as? TagChoice
            val counts = unlocked.entryList.getTagCounts()
            val untagged = unlocked.snapshot().count { it.getTags().isEmpty() }
            val choices = arrayListOf(TagChoice(TagChoice.Kind.ALL), TagChoice(TagChoice.Kind.UNTAGGED, count = untagged))
            EntryTagFilter.sortedNames(counts.keys).forEach { name ->
                choices.add(TagChoice(TagChoice.Kind.TAG, name, counts.getValue(name)))
            }
            val retainedMulti = if (previous?.kind == TagChoice.Kind.MULTIPLE) {
                val surviving = previous.selectedTags.filter { it in counts }
                if (surviving.isNotEmpty() || previous.includeUntagged) {
                    TagChoice(
                        TagChoice.Kind.MULTIPLE,
                        selectedTags = surviving,
                        includeUntagged = previous.includeUntagged,
                        functionality = previous.functionality
                    )
                } else null
            } else null
            val multiChoice = retainedMulti ?: TagChoice(TagChoice.Kind.MULTIPLE)
            choices.add(multiChoice)
            val selected = when {
                retainedMulti != null -> retainedMulti
                previous != null -> choices.firstOrNull { it.sameFilter(previous) } ?: choices.first()
                else -> choices.first()
            }
            updatingTagChoices = true
            try {
                screen.tagFilter.model = javax.swing.DefaultComboBoxModel(choices.toTypedArray())
                screen.tagFilter.selectedItem = selected
            } finally { updatingTagChoices = false }
            if (reset || (previous != null && selected.kind == TagChoice.Kind.ALL && previous.kind != TagChoice.Kind.ALL)) {
                unlocked.applyTagFilter(emptyList(), false)
            } else if (retainedMulti != null && retainedMulti != previous) {
                unlocked.applyTagFilter(retainedMulti.selectedTags, retainedMulti.includeUntagged, retainedMulti.functionality)
            }
        }

        /** The combo is a view of the saved preference; setting it must not re-apply the order. */
        fun selectSortChoice(mode: Constants.SortMode) {
            updatingSortChoice = true
            try {
                for (index in 0 until screen.sortOrder.itemCount) {
                    val choice = screen.sortOrder.getItemAt(index)
                    if (choice.mode == mode) { screen.sortOrder.selectedIndex = index; return }
                }
            } finally { updatingSortChoice = false }
        }

        fun lockVault() {
            val active = state ?: return
            coordinator?.cancelMigrationImport()
            ticker?.close()
            ticker = null
            coordinator = null
            editor = null
            state = null
            active.lock()
            search.text = ""
            updatingTagChoices = true
            try {
                screen.tagFilter.model = javax.swing.DefaultComboBoxModel(arrayOf(TagChoice(TagChoice.Kind.ALL)))
            } finally { updatingTagChoices = false }
            table.model = EntryTableModel(EntryListPresentation(EntryList()).apply {
                showReorderedEntries()
            })
            screen.setUnlocked(false)
        }

        fun bind(session: VaultController.Session.Loaded) {
            state?.lock()
            val savedSort = controller.savedSortMode()
            selectSortChoice(savedSort)
            val unlocked = DesktopVaultState(session.key).apply {
                // Through the state, never its presentation: the selection the state reapplies on
                // every refresh is one VaultFilter, so a direct setCategory() here would be undone
                // by the next refresh.
                applyCategory(EntryListPresentation.Category.entries[screen.categories.selectedIndex])
                applySortMode(savedSort)
                load(session.entries)
            }
            state = unlocked
            editor = EntryEditor(controller, unlocked)
            coordinator = BackupCoordinator(controller, unlocked)
            table.model = EntryTableModel(unlocked.presentation) { entry, visible ->
                if (editor?.setDetailsVisible(entry, visible) != true) {
                    // Do not leave the old revealed row painted behind a modal error dialog.
                    table.paintImmediately(table.visibleRect)
                    JOptionPane.showMessageDialog(frame,
                        "The visibility choice could not be saved. This entry is hidden now, but its previous saved state may return after restart.",
                        TITLE, JOptionPane.ERROR_MESSAGE)
                }
            }
            refreshTagChoices(unlocked, reset = true)
            search.text = ""
            screen.setUnlocked(true)
            ticker?.close()
            ticker = TokenTicker(
                scheduler = SwingScheduler(),
                state = unlocked,
                onInactivity = { lockVault() },
                onTick = {
                    // A tick changes cached values, not the projection. Preserve the selected row.
                    (table.model as? EntryTableModel)?.refreshValues()
                }
            ).also { it.start() }
            armInactivityTimer()
        }

        fun accept(session: VaultController.Session) {
            when (session) {
                is VaultController.Session.Loaded -> bind(session)
                is VaultController.Session.Error -> showError(session.failure)
            }
        }

        fun requireUnlocked(): DesktopVaultState? = state ?: run {
            JOptionPane.showMessageDialog(frame, "Open a vault first.", TITLE,
                JOptionPane.WARNING_MESSAGE)
            null
        }

        // Mutations rebuild the shared projection. Reapply an active search before repainting.
        fun refreshProjection(unlocked: DesktopVaultState) {
            refreshTagChoices(unlocked)
            unlocked.applySearch(search.text)
            (table.model as? EntryTableModel)?.refresh()
        }

        /** Persist usage before writing the selected entry's token/note/key to the clipboard. */
        fun copySelectedToken() {
            requireUnlocked() ?: return
            val viewRow = table.selectedRow
            if (viewRow < 0) {
                JOptionPane.showMessageDialog(frame, "Select an entry first.", TITLE,
                    JOptionPane.WARNING_MESSAGE)
                return
            }
            val row = table.convertRowIndexToModel(viewRow)
            val entry = (table.model as EntryTableModel).entryAt(row)
            if (!entry.areDetailsVisible() || entry.isEmail()) return
            val result = if (entry.isPassword()) {
                editor?.copyPassword(entry)
            } else if (entry.isPasswordArchive()) {
                PasswordImportDialogs.export(frame, entry)
                return
            } else if (entry.isSshKey()) {
                val keys = entry.getSshKeys()
                val choices = ArrayList<String>()
                if (keys.publicKey.isNotBlank()) choices.add("Public key")
                if (keys.privateKey.isNotBlank()) choices.add("Private key")
                // Only offered when the entry stores one, like the keys themselves.
                if (!entry.getSshConnection()?.password.isNullOrEmpty()) choices.add("Password")
                val choice = JOptionPane.showOptionDialog(frame, "Choose which SSH item to copy.", TITLE,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices.toTypedArray(), null)
                if (choice !in choices.indices) return
                when (choices[choice]) {
                    "Password" -> editor?.copySshPassword(entry)
                    else -> editor?.copySshKey(entry, choices[choice] == "Private key")
                }
            } else editor?.copyToken(entry)
            when (result) {
                is EntryEditor.TokenCopyResult.Copied -> {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(result.token), null)
                    scheduleClipboardClear(result.token)
                    (table.model as? EntryTableModel)?.refresh()
                }
                EntryEditor.TokenCopyResult.Unavailable ->
                    JOptionPane.showMessageDialog(frame, "No token is available for that entry yet.",
                        TITLE, JOptionPane.INFORMATION_MESSAGE)
                EntryEditor.TokenCopyResult.PersistenceFailed ->
                    JOptionPane.showMessageDialog(frame, "The item was not copied: the vault could not be written.",
                        TITLE, JOptionPane.ERROR_MESSAGE)
                null -> Unit
            }
        }

        /**
         * Manual ordering, the desktop counterpart of Android's drag handle: the shared
         * [EntryListPresentation.canDrag] rule limits it to the unsorted, unfiltered view.
         */
        fun moveSelected(delta: Int) {
            val unlocked = requireUnlocked() ?: return
            val viewRow = table.selectedRow
            if (viewRow < 0) return
            val target = viewRow + delta
            if (target !in 0 until table.rowCount) return
            val from = table.convertRowIndexToModel(viewRow)
            val to = table.convertRowIndexToModel(target)
            if (editor?.move(from, to) == true) {
                refreshProjection(unlocked)
                if (target in 0 until table.rowCount) table.setRowSelectionInterval(target, target)
            }
        }

        /** Desktop counterpart of Android's **Show QR code** popup item. */
        fun showQrCode() {
            requireUnlocked() ?: return
            val viewRow = table.selectedRow
            if (viewRow < 0) return
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(viewRow))
            if (!QrCodeDialog.show(frame, entry)) {
                JOptionPane.showMessageDialog(frame, QrCodeDialog.UNSUPPORTED, TITLE,
                    JOptionPane.INFORMATION_MESSAGE)
            }
        }

        /** Desktop counterpart of the Android counter chip: explicit HOTP counter advance. */
        fun advanceSelectedHotp() {
            requireUnlocked() ?: return
            val viewRow = table.selectedRow
            if (viewRow < 0) {
                JOptionPane.showMessageDialog(frame, "Select an entry first.", TITLE,
                    JOptionPane.WARNING_MESSAGE)
                return
            }
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(viewRow))
            if (!entry.isCounterBased()) {
                JOptionPane.showMessageDialog(frame, "Only HOTP entries have a counter to advance.",
                    TITLE, JOptionPane.INFORMATION_MESSAGE)
                return
            }
            if (editor?.advanceHotp(entry) == true) {
                (table.model as? EntryTableModel)?.refreshValues()
            } else {
                JOptionPane.showMessageDialog(frame,
                    "The HOTP counter could not be advanced, or the vault could not be written.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }

        fun addEntry(type: Entry.OTPType) {
            val unlocked = requireUnlocked() ?: return
            EntryDialog.prompt(frame, initialType = type, knownTags = unlocked.entryList.getSortedTags())?.let { entry ->
                if (editor?.add(entry) == true) {
                    // The shared mapping decides the tab, exactly as Android's showEntryCategory.
                    screen.categories.selectedIndex = EntryListPresentation.categoryOf(entry).ordinal
                    refreshProjection(unlocked)
                } else {
                    entry.destroy()
                    JOptionPane.showMessageDialog(frame, "The entry already exists or could not be saved.",
                        TITLE, JOptionPane.WARNING_MESSAGE)
                }
            }
        }

        fun importQrImage() {
            val unlocked = requireUnlocked() ?: return
            val active = coordinator ?: return
            BackupDialogs.importQrImage(frame, active)
            refreshProjection(unlocked)
        }

        fun importOtpUri() {
            val unlocked = requireUnlocked() ?: return
            val active = coordinator ?: return
            BackupDialogs.importOtpUri(frame, active)
            refreshProjection(unlocked)
        }

        fun openMultiTagFilterDialog() {
            val unlocked = requireUnlocked() ?: return
            val counts = unlocked.entryList.getTagCounts()
            val untagged = unlocked.snapshot().count { it.getTags().isEmpty() }
            val (currentTags, currentUntagged, currentMode) = unlocked.activeTagFilter()
            val chosen = TagDialog.promptFilter(frame, counts, untagged, currentTags, currentUntagged, currentMode)
            if (chosen == null) {
                refreshTagChoices(unlocked)
                return
            }
            if (chosen.selectedTags.isEmpty() && !chosen.includeUntagged) {
                unlocked.applyTagFilter(emptyList(), false)
                updatingTagChoices = true
                try { screen.tagFilter.selectedIndex = 0 } finally { updatingTagChoices = false }
            } else {
                unlocked.applyTagFilter(chosen.selectedTags, chosen.includeUntagged, chosen.functionality)
                updatingTagChoices = true
                try {
                    val model = screen.tagFilter.model as javax.swing.DefaultComboBoxModel<TagChoice>
                    val multiIndex = (0 until model.size).firstOrNull { model.getElementAt(it).kind == TagChoice.Kind.MULTIPLE }
                    val updated = TagChoice(
                        TagChoice.Kind.MULTIPLE,
                        selectedTags = chosen.selectedTags,
                        includeUntagged = chosen.includeUntagged,
                        functionality = chosen.functionality
                    )
                    if (multiIndex != null) {
                        model.removeElementAt(multiIndex)
                        model.insertElementAt(updated, multiIndex)
                    } else {
                        model.addElement(updated)
                    }
                    screen.tagFilter.selectedItem = updated
                } finally { updatingTagChoices = false }
            }
            unlocked.applySearch(search.text)
            (table.model as? EntryTableModel)?.refresh()
        }

        screen.tagFilter.addActionListener {
            if (!updatingTagChoices) state?.let { unlocked ->
                val choice = screen.tagFilter.selectedItem as? TagChoice ?: return@let
                when (choice.kind) {
                    TagChoice.Kind.ALL -> {
                        unlocked.applyTagFilter(emptyList(), false)
                        (table.model as? EntryTableModel)?.refresh()
                    }
                    TagChoice.Kind.UNTAGGED -> {
                        unlocked.applyTagFilter(emptyList(), true)
                        (table.model as? EntryTableModel)?.refresh()
                    }
                    TagChoice.Kind.TAG -> {
                        unlocked.applyTagFilter(listOf(choice.tag), false)
                        (table.model as? EntryTableModel)?.refresh()
                    }
                    TagChoice.Kind.MULTIPLE -> openMultiTagFilterDialog()
                }
            }
        }

        screen.sortOrder.addActionListener {
            if (!updatingSortChoice) state?.let { unlocked ->
                val choice = screen.sortOrder.selectedItem as? SortChoice ?: return@let
                unlocked.applySortMode(choice.mode)
                controller.saveSortMode(choice.mode)
                refreshProjection(unlocked)
            }
        }

        screen.categories.addChangeListener {
            state?.let { unlocked ->
                unlocked.applyCategory(EntryListPresentation.Category.entries[screen.categories.selectedIndex])
                refreshProjection(unlocked)
            }
        }

        fun exportPreferences() {
            PreferencesDialogs.export(frame, controller.preferences)
        }

        fun importPreferences() {
            if (!PreferencesDialogs.import(frame, controller.preferences)) return
            val mode = controller.savedSortMode()
            selectSortChoice(mode)
            armInactivityTimer()
            state?.let { unlocked ->
                unlocked.applySortMode(mode)
                refreshProjection(unlocked)
            }
        }

        fun importPasswords() {
            val unlocked = requireUnlocked() ?: return
            val active = editor ?: return
            if (PasswordImportDialogs.import(frame, active)) {
                screen.categories.selectedIndex = EntryListPresentation.Category.PASSWORDS.ordinal
                refreshProjection(unlocked)
            }
        }

        fun importThunderbird() {
            val unlocked = requireUnlocked() ?: return
            val active = editor ?: return
            if (ThunderbirdDialogs.importBackup(frame, active)) {
                screen.categories.selectedIndex = EntryListPresentation.Category.EMAILS.ordinal
                refreshProjection(unlocked)
            }
        }

        fun exportThunderbird() {
            val unlocked = requireUnlocked() ?: return
            if (ThunderbirdDialogs.exportBackup(frame, unlocked.snapshot())) {
                // Export does not mutate vault; no projection refresh needed.
            }
        }

        fun importLinScp() {
            val unlocked = requireUnlocked() ?: return
            val active = editor ?: return
            when (LinScpDialogs.importBackup(frame, active)) {
                LinScpDialogs.ImportedTab.SSH_KEYS -> {
                    screen.categories.selectedIndex = EntryListPresentation.Category.SSH_KEYS.ordinal
                    refreshProjection(unlocked)
                }
                LinScpDialogs.ImportedTab.PASSWORDS -> {
                    screen.categories.selectedIndex = EntryListPresentation.Category.PASSWORDS.ordinal
                    refreshProjection(unlocked)
                }
                LinScpDialogs.ImportedTab.NONE -> {}
            }
        }

        fun exportLinScp() {
            val unlocked = requireUnlocked() ?: return
            LinScpDialogs.exportVault(frame, unlocked.snapshot())
        }

        /** The entry under the selection, or null when nothing usable is selected. */
        fun selectedEntry(): Entry? {
            val row = table.selectedRow
            if (row < 0) return null
            return (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(row))
        }

        /** The browser dialect the selected passwords are written in; mixed sets use Firefox. */
        fun selectedPasswordDialect(): PasswordCsvExport.Dialect {
            val dialects = (state?.snapshot() ?: emptyList()).filter { it.areDetailsVisible() }
                .map { PasswordCsvExport.support(it) }
                .filterIsInstance<PasswordCsvExport.Support.Exportable>()
                .map { it.dialect }
                .distinct()
            return dialects.singleOrNull() ?: PasswordCsvExport.Dialect.FIREFOX
        }

        fun editPassword() {
            val unlocked = requireUnlocked() ?: return
            val entry = selectedEntry() ?: return
            val replacement = PasswordImportDialogs.edit(frame, entry) ?: return
            if (editor?.replace(entry, replacement) == true) {
                refreshProjection(unlocked)
            } else {
                replacement.destroy()
                JOptionPane.showMessageDialog(frame, "The password entry could not be saved to the vault.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }

        fun exportSelectedPasswordAsCsv() {
            requireUnlocked() ?: return
            val entry = selectedEntry()?.takeIf { it.areDetailsVisible() } ?: return
            val support = PasswordCsvExport.support(entry) as? PasswordCsvExport.Support.Exportable ?: return
            PasswordImportDialogs.exportCsv(frame, listOf(entry), support.dialect)
        }

        fun exportPasswordsAsCsv() {
            val unlocked = requireUnlocked() ?: return
            PasswordImportDialogs.exportCsv(frame, unlocked.snapshot(), selectedPasswordDialect())
        }

        fun showPasswordDetails() {
            requireUnlocked() ?: return
            val row = table.selectedRow
            if (row < 0) return
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(row))
            PasswordImportDialogs.showDetails(frame, entry)
        }

        fun exportPasswordFile() {
            requireUnlocked() ?: return
            val row = table.selectedRow
            if (row < 0) return
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(row))
            PasswordImportDialogs.export(frame, entry)
        }

        /** Manual FairEmail-compatible account, the desktop counterpart of Android's + action. */
        fun addEmailAccount() {
            val unlocked = requireUnlocked() ?: return
            val active = editor ?: return
            if (EmailDialogs.addAccount(frame, active)) {
                screen.categories.selectedIndex = EntryListPresentation.Category.EMAILS.ordinal
                refreshProjection(unlocked)
            }
        }

        fun importEmailBackup() {
            val unlocked = requireUnlocked() ?: return
            val active = editor ?: return
            if (EmailDialogs.importBackup(frame, active)) {
                screen.categories.selectedIndex = EntryListPresentation.Category.EMAILS.ordinal
                refreshProjection(unlocked)
            }
        }

        fun exportEmailBackup() {
            val unlocked = requireUnlocked() ?: return
            EmailDialogs.exportBackup(frame, unlocked.snapshot())
        }

        fun showEmailDetails() {
            requireUnlocked() ?: return
            val row = table.selectedRow
            if (row < 0) return
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(row))
            EmailDialogs.showDetails(frame, entry)
        }

        fun promptCreateVault() {
            PasswordDialog.prompt(frame, "Create a new vault")?.let { accept(controller.createVault(it)) }
        }
        fun promptOpenVault() {
            PasswordDialog.prompt(frame, "Open vault")?.let { accept(controller.openVault(it)) }
        }
        fun unlockOrCreateVault() {
            if (state != null) {
                JOptionPane.showMessageDialog(frame, "The vault is already unlocked.", TITLE,
                    JOptionPane.INFORMATION_MESSAGE)
                return
            }
            if (!controller.isInitialized()) promptCreateVault() else promptOpenVault()
        }
        fun changeVaultPassword() {
            val unlocked = requireUnlocked() ?: return
            val current = PasswordDialog.prompt(frame, "Current vault password") ?: return
            val next = PasswordDialog.promptNewBackupPassword(frame, "New vault password") ?: return
            when (val outcome = controller.changePassword(current, next)) {
                is VaultController.Session.Loaded -> {
                    outcome.entries.forEach { it.destroy() }
                    unlocked.updateKey(outcome.key)
                    val clearedHardware = hardware.removeEnrollment()
                    val note = if (clearedHardware) {
                        "Vault password updated. Your previous security-key unlock envelope was cleared; re-enroll under Hardware → Security key… if desired."
                    } else {
                        "Vault password updated."
                    }
                    JOptionPane.showMessageDialog(frame, note, TITLE, JOptionPane.INFORMATION_MESSAGE)
                }
                is VaultController.Session.Error -> showError(outcome.failure)
            }
        }
        fun configureAutoLock() {
            data class AutoLockOption(val seconds: Int, val label: String) {
                override fun toString(): String = label
            }
            val options = arrayOf(
                AutoLockOption(0, "Disabled"),
                AutoLockOption(30, "30 seconds"),
                AutoLockOption(60, "1 minute"),
                AutoLockOption(120, "2 minutes"),
                AutoLockOption(300, "5 minutes"),
                AutoLockOption(900, "15 minutes")
            )
            val currentSeconds = controller.savedInactivityLockSeconds()
            val initial = options.firstOrNull { it.seconds == currentSeconds } ?: options.first()
            val chosen = JOptionPane.showInputDialog(
                frame,
                "Lock the vault automatically after inactivity:",
                "Auto-lock timeout",
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                initial
            ) as? AutoLockOption ?: return
            controller.saveInactivityLockSeconds(chosen.seconds)
            armInactivityTimer()
        }
        val addMenu = JPopupMenu().apply {
            add(JMenuItem("Enter account details…").apply { addActionListener { addEntry(Entry.OTPType.TOTP) } })
            add(JMenuItem("Add secure note…").apply { addActionListener { addEntry(Entry.OTPType.NOTE) } })
            add(JMenuItem("Add SSH key…").apply { addActionListener { addEntry(Entry.OTPType.SSH_KEY) } })
            add(JMenuItem("Add email account…").apply { addActionListener { addEmailAccount() } })
            add(JMenuItem("Import passwords…").apply { addActionListener { importPasswords() } })
            add(JMenuItem("Export passwords as browser CSV…").apply { addActionListener { exportPasswordsAsCsv() } })
            add(JMenuItem("Import Thunderbird credentials…").apply { addActionListener { importThunderbird() } })
            add(JMenuItem("Export Thunderbird credentials…").apply { addActionListener { exportThunderbird() } })
            add(JMenuItem("Import LinSCP SSH & sessions…").apply { addActionListener { importLinScp() } })
            add(JMenuItem("Export to LinSCP vault…").apply { addActionListener { exportLinScp() } })
            addSeparator()
            add(JMenuItem("Import QR image…").apply { addActionListener { importQrImage() } })
            add(JMenuItem("Import otpauth:// link…").apply { addActionListener { importOtpUri() } })
        }
        screen.addButton.addActionListener {
            if (requireUnlocked() != null) {
                // Keep the wider menu aligned to the right edge of the action button.
                addMenu.show(screen.addButton, screen.addButton.width - addMenu.preferredSize.width,
                    screen.addButton.height)
            }
        }
        screen.editButton.addActionListener {
            val unlocked = requireUnlocked() ?: return@addActionListener
            val viewRow = table.selectedRow
            if (viewRow < 0) return@addActionListener
            val existing = unlocked.presentation.get(table.convertRowIndexToModel(viewRow))
            if (!existing.areDetailsVisible()) return@addActionListener
            if (existing.isPassword() || existing.isPasswordArchive() || existing.isEmail()) return@addActionListener
            EntryDialog.prompt(frame, existing, knownTags = unlocked.entryList.getSortedTags())?.let { replacement ->
                if (editor?.replace(existing, replacement) == true) {
                    refreshProjection(unlocked)
                } else {
                    replacement.destroy()
                    JOptionPane.showMessageDialog(frame, "The entry could not be saved to the vault.",
                        TITLE, JOptionPane.ERROR_MESSAGE)
                }
            }
        }
        screen.tagsButton.addActionListener {
            val unlocked = requireUnlocked() ?: return@addActionListener
            val row = table.selectedRow
            if (row < 0) return@addActionListener
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(row))
            if (!entry.areDetailsVisible()) return@addActionListener
            TagDialog.prompt(frame, entry.getTags().toList(), unlocked.entryList.getSortedTags())?.let { tags ->
                if (editor?.setTags(entry, tags) == true) {
                    refreshProjection(unlocked)
                } else {
                    JOptionPane.showMessageDialog(frame, "Tags could not be saved; the original tags were retained.",
                        TITLE, JOptionPane.ERROR_MESSAGE)
                }
            }
        }
        screen.deleteButton.addActionListener {
            val unlocked = requireUnlocked() ?: return@addActionListener
            val viewRow = table.selectedRow
            if (viewRow < 0) return@addActionListener
            val row = table.convertRowIndexToModel(viewRow)
            val selected = unlocked.presentation.get(row)
            // Android confirms a removal by name; a single click here must not drop an entry.
            if (JOptionPane.showConfirmDialog(frame,
                    removalConfirmationMessage(selected.getLabel(), selected.getIssuer()),
                    "Remove", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) return@addActionListener
            if (editor?.remove(selected) == true) {
                refreshProjection(unlocked)
            } else {
                JOptionPane.showMessageDialog(frame, "The entry could not be deleted from the vault.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }
        screen.copyButton.addActionListener { copySelectedToken() }

        // Right-click or the keyboard context-menu key opens the same row actions.
        val counterDivider = JPopupMenu.Separator()
        val orderDivider = JPopupMenu.Separator()
        val rowMenu = JPopupMenu().apply {
            add(JMenuItem("Copy selected").apply { addActionListener { copySelectedToken() } })
            add(JMenuItem("Edit…").apply { addActionListener { screen.editButton.doClick() } })
            add(JMenuItem("Tags…").apply { addActionListener { screen.tagsButton.doClick() } })
            add(JMenuItem("Edit password…").apply { addActionListener { editPassword() } })
            add(JMenuItem("Export as browser CSV…").apply { addActionListener { exportSelectedPasswordAsCsv() } })
            add(JMenuItem("View password details…").apply { addActionListener { showPasswordDetails() } })
            add(JMenuItem("Export password file…").apply { addActionListener { exportPasswordFile() } })
            add(JMenuItem("View email details…").apply { addActionListener { showEmailDetails() } })
            add(JMenuItem("Delete").apply { addActionListener { screen.deleteButton.doClick() } })
            add(counterDivider)
            add(JMenuItem("Advance HOTP counter").apply { addActionListener { advanceSelectedHotp() } })
            add(JMenuItem("Show QR code…").apply { addActionListener { showQrCode() } })
            add(orderDivider)
            add(JMenuItem("Move up").apply { addActionListener { moveSelected(-1) } })
            add(JMenuItem("Move down").apply { addActionListener { moveSelected(1) } })
        }
        fun showRowMenu(viewRow: Int, x: Int, y: Int) {
            if (viewRow !in 0 until table.rowCount) return
            table.setRowSelectionInterval(viewRow, viewRow)
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(viewRow))
            counterDivider.isVisible = entry.isCounterBased()
            // Android only drags in the unsorted, unfiltered view, and only encodes OTP entries.
            val canReorder = state?.presentation?.canDrag() == true
            orderDivider.isVisible = canReorder
            rowMenu.components.forEach { component ->
                if (component is JMenuItem) when (component.text) {
                    "Copy selected" -> component.isEnabled = entry.areDetailsVisible() && !entry.isPasswordArchive() && !entry.isEmail()
                    "Edit…" -> component.isEnabled = entry.areDetailsVisible() && !entry.isPassword() && !entry.isPasswordArchive() && !entry.isEmail()
                    "Tags…" -> component.isEnabled = entry.areDetailsVisible()
                    "Edit password…" -> { component.isVisible = entry.isPassword(); component.isEnabled = entry.areDetailsVisible() }
                    "Export as browser CSV…" -> {
                        component.isVisible = entry.isPassword()
                        component.isEnabled = entry.areDetailsVisible() &&
                            PasswordCsvExport.support(entry) is PasswordCsvExport.Support.Exportable
                    }
                    "View password details…" -> { component.isVisible = entry.isPassword(); component.isEnabled = entry.areDetailsVisible() }
                    "Export password file…" -> { component.isVisible = entry.isPasswordArchive(); component.isEnabled = entry.areDetailsVisible() }
                    "View email details…" -> { component.isVisible = entry.isEmail(); component.isEnabled = entry.areDetailsVisible() }
                    "Advance HOTP counter" -> {
                        component.isVisible = entry.isCounterBased()
                        component.isEnabled = entry.areDetailsVisible()
                    }
                    "Show QR code…" -> {
                        component.isVisible = QrCodeImage.payloadFor(entry) != null
                        component.isEnabled = entry.areDetailsVisible()
                    }
                    "Move up" -> component.isEnabled = canReorder && viewRow > 0
                    "Move down" -> component.isEnabled = canReorder && viewRow < table.rowCount - 1
                }
            }
            rowMenu.show(table, x, y)
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) {
                    val row = table.rowAtPoint(event.point)
                    val viewColumn = table.columnAtPoint(event.point)
                    if (row >= 0 && viewColumn >= 0 &&
                        table.convertColumnIndexToModel(viewColumn) != EntryTableModel.Column.SHOW.ordinal) {
                        table.setRowSelectionInterval(row, row)
                        copySelectedToken()
                    }
                }
            }

            override fun mousePressed(event: MouseEvent) = maybeShowRowMenu(event)
            override fun mouseReleased(event: MouseEvent) = maybeShowRowMenu(event)

            private fun maybeShowRowMenu(event: MouseEvent) {
                if (event.isPopupTrigger) showRowMenu(table.rowAtPoint(event.point), event.x, event.y)
            }
        })
        val rowMenuAction = "showEntryMenu"
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), rowMenuAction)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), rowMenuAction)
        }
        table.actionMap.put(rowMenuAction, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                val row = table.selectedRow
                if (row >= 0) {
                    val bounds = table.getCellRect(row, 0, true)
                    showRowMenu(row, bounds.x + 16, bounds.y + bounds.height)
                }
            }
        })
        // Alt+Up/Down reorder without opening the menu, the keyboard equivalent of dragging a card.
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "moveEntryUp")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "moveEntryDown")
        }
        table.actionMap.put("moveEntryUp", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = moveSelected(-1)
        })
        table.actionMap.put("moveEntryDown", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = moveSelected(1)
        })

        search.document.addDocumentListener(object : SimpleDocumentListener {
            override fun update() {
                state?.applySearch(search.text)
                (table.model as? EntryTableModel)?.refresh()
            }
        })

        val menuBar = JMenuBar().apply {
            add(JMenu("Tools").apply {
                mnemonic = KeyEvent.VK_T
                add(JMenuItem("Import QR image…").apply { addActionListener { importQrImage() } })
                add(JMenuItem("Import otpauth:// link…").apply { addActionListener { importOtpUri() } })
                add(JMenuItem("Filter by tags…").apply { addActionListener { openMultiTagFilterDialog() } })
                addSeparator()
                add(JMenuItem("Export preferences…").apply { addActionListener { exportPreferences() } })
                add(JMenuItem("Import preferences…").apply { addActionListener { importPreferences() } })
                addSeparator()
                add(JMenuItem("Import passwords…").apply { addActionListener { importPasswords() } })
                add(JMenuItem("Export selected password file…").apply { addActionListener { exportPasswordFile() } })
                add(JMenuItem("Export passwords as browser CSV…").apply { addActionListener { exportPasswordsAsCsv() } })
                add(JMenuItem("Import Thunderbird credentials…").apply { addActionListener { importThunderbird() } })
                add(JMenuItem("Export Thunderbird credentials…").apply { addActionListener { exportThunderbird() } })
                add(JMenuItem("Import LinSCP SSH & sessions…").apply { addActionListener { importLinScp() } })
                add(JMenuItem("Export to LinSCP vault…").apply { addActionListener { exportLinScp() } })
                addSeparator()
                add(JMenuItem("Import FairEmail backup…").apply { addActionListener { importEmailBackup() } })
                add(JMenuItem("Export FairEmail backup…").apply { addActionListener { exportEmailBackup() } })
                add(JMenuItem("Full Encrypted Backup…").apply {
                    addActionListener { coordinator?.let { BackupDialogs.saveEncryptedBackup(frame, it) } }
                })
                add(JMenuItem("Restore Full Encrypted Backup…").apply {
                    addActionListener {
                        coordinator?.let {
                            BackupDialogs.restoreEncryptedBackup(frame, it)
                            state?.let { unlocked -> refreshProjection(unlocked) }
                        }
                    }
                })
                add(JMenuItem("OpenPGP backup…").apply {
                    addActionListener { coordinator?.let { BackupDialogs.saveOpenPgpBackup(frame, it) } }
                })
                add(JMenuItem("Restore OpenPGP backup…").apply {
                    addActionListener {
                        coordinator?.let {
                            BackupDialogs.restoreOpenPgp(frame, it)
                            state?.let { unlocked -> refreshProjection(unlocked) }
                        }
                    }
                })
                addSeparator()
                add(JMenuItem("Lock vault").apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_L,
                        Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
                    addActionListener { lockVault() }
                })
                add(JMenuItem("Unlock vault…").apply { addActionListener { unlockOrCreateVault() } })
                add(JMenuItem("Change vault password…").apply { addActionListener { changeVaultPassword() } })
                add(JMenuItem("Auto-lock timeout…").apply { addActionListener { configureAutoLock() } })
            })
            add(JMenu("Hardware").apply {
                mnemonic = KeyEvent.VK_H
                add(JMenuItem("Security key…").apply {
                    addActionListener {
                        val unlocked = requireUnlocked() ?: return@addActionListener
                        HardwareKeyDialog.show(frame, hardware, unlocked)
                    }
                })
                add(JMenuItem("Unlock with security key…").apply {
                    addActionListener {
                        if (!hardware.isEnrolled()) {
                            JOptionPane.showMessageDialog(frame,
                                "No hardware unlock is enrolled for this vault directory.",
                                TITLE, JOptionPane.INFORMATION_MESSAGE)
                            return@addActionListener
                        }
                        HardwareKeyDialog.requestUnlock(frame, hardware) { key ->
                            accept(controller.unlockWithKey(key))
                        }
                    }
                })
            })
            add(JMenu("Help").apply {
                add(JMenuItem("About OfflineOTP").apply { addActionListener { AboutDialog.show(frame) } })
            })
        }

        val activityListener = AWTEventListener { event ->
            val src = event.source as? Component
            if (src != null && state != null && SwingUtilities.isDescendingFrom(src, frame)) {
                armInactivityTimer()
            }
        }
        val activityMask = AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK
        Toolkit.getDefaultToolkit().addAWTEventListener(activityListener, activityMask)

        frame = JFrame(TITLE).apply {
            iconImage = DesktopIcon.load()
            jMenuBar = menuBar
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            // Auto-prompt like Android: first launch → Create, later → Open.
            // Exposed for display tests so they do not click header buttons that no longer exist.
            rootPane.putClientProperty("offlineotp.promptCreateVault", Runnable { promptCreateVault() })
            rootPane.putClientProperty("offlineotp.promptOpenVault", Runnable { promptOpenVault() })
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(event: java.awt.event.WindowEvent) {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener)
                    clipboardClearTimer?.stop()
                    ticker?.close()
                    state?.lock()
                    hardware.close()
                }
                override fun windowOpened(event: java.awt.event.WindowEvent) {
                    // Parity with Android: no persistent New/Open buttons. First launch auto-asks to
                    // create, later launches auto-ask to open – the header and Vault menu stay vault-free.
                    javax.swing.SwingUtilities.invokeLater {
                        if (!controller.isInitialized()) promptCreateVault()
                        else if (state == null) promptOpenVault()
                    }
                }
            })
            contentPane = screen.panel
            minimumSize = Dimension(720, 520)
            pack()
            setLocationRelativeTo(null)
        }
        return frame
    }
}
