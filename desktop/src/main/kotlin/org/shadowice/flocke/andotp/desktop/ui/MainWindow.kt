package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.control.BackupCoordinator
import org.shadowice.flocke.andotp.desktop.control.DesktopVaultState
import org.shadowice.flocke.andotp.desktop.control.EntryEditor
import org.shadowice.flocke.andotp.desktop.control.HardwareKeyManager
import org.shadowice.flocke.andotp.desktop.control.TokenTicker
import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
        var updatingTagChoices = false

        fun setStatus(message: String) {
            screen.status.text = message
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
            setStatus(message)
            JOptionPane.showMessageDialog(frame, message, TITLE, JOptionPane.ERROR_MESSAGE)
        }

        /** Rebuild from the actual vault after imports, edits and restores. */
        fun refreshTagChoices(unlocked: DesktopVaultState, reset: Boolean = false) {
            val previous = if (reset) null else screen.tagFilter.selectedItem as? TagChoice
            val counts = unlocked.entryList.getTagCounts()
            val untagged = unlocked.snapshot().count { it.getTags().isEmpty() }
            val choices = arrayListOf(TagChoice(TagChoice.Kind.ALL), TagChoice(TagChoice.Kind.UNTAGGED, count = untagged))
            counts.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { name ->
                choices.add(TagChoice(TagChoice.Kind.TAG, name, counts.getValue(name)))
            }
            val selected = previous?.let { old -> choices.firstOrNull { it.sameFilter(old) } } ?: choices.first()
            updatingTagChoices = true
            try {
                screen.tagFilter.model = javax.swing.DefaultComboBoxModel(choices.toTypedArray())
                screen.tagFilter.selectedItem = selected
            } finally { updatingTagChoices = false }
            if (reset || (previous != null && !selected.sameFilter(previous)))
                unlocked.applyTagFilter(emptyList(), false)
        }

        fun bind(session: VaultController.Session.Loaded) {
            val unlocked = DesktopVaultState(session.key).apply {
                presentation.setCategory(EntryListPresentation.Category.entries[screen.categories.selectedIndex])
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
            ticker = TokenTicker(SwingScheduler(), unlocked) {
                // A tick changes cached values, not the projection. Preserve the selected row.
                (table.model as? EntryTableModel)?.refreshValues()
            }.also { it.start() }
            setStatus("${session.entries.size} entries unlocked")
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
            if (!entry.areDetailsVisible()) return
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
                val choice = JOptionPane.showOptionDialog(frame, "Choose which SSH key to copy.", TITLE,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices.toTypedArray(), null)
                if (choice !in choices.indices) return
                editor?.copySshKey(entry, choices[choice] == "Private key")
            } else editor?.copyToken(entry)
            when (result) {
                is EntryEditor.TokenCopyResult.Copied -> {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(result.token), null)
                    (table.model as? EntryTableModel)?.refresh()
                    setStatus(when {
                        entry.isPassword() -> "Password copied to the clipboard"
                        entry.isSshKey() -> "SSH key copied to the clipboard"
                        entry.isNote() -> "Note copied to the clipboard"
                        else -> "Token copied to the clipboard"
                    })
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
                setStatus("HOTP counter advanced")
            } else {
                JOptionPane.showMessageDialog(frame,
                    "The HOTP counter could not be advanced, or the vault could not be written.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }

        fun addEntry(type: Entry.OTPType) {
            val unlocked = requireUnlocked() ?: return
            EntryDialog.prompt(frame, initialType = type, knownTags = unlocked.entryList.getAllTags())?.let { entry ->
                if (editor?.add(entry) == true) {
                    screen.categories.selectedIndex = when { entry.isNote() -> 1; entry.isSshKey() -> 2; else -> 0 }
                    refreshProjection(unlocked)
                    setStatus("Entry added")
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

        screen.tagFilter.addActionListener {
            if (!updatingTagChoices) state?.let { unlocked ->
                val choice = screen.tagFilter.selectedItem as? TagChoice ?: return@let
                when (choice.kind) {
                    TagChoice.Kind.ALL -> unlocked.applyTagFilter(emptyList(), false)
                    TagChoice.Kind.UNTAGGED -> unlocked.applyTagFilter(emptyList(), true)
                    TagChoice.Kind.TAG -> unlocked.applyTagFilter(listOf(choice.tag), false)
                }
                (table.model as? EntryTableModel)?.refresh()
            }
        }

        screen.categories.addChangeListener {
            state?.let { unlocked ->
                unlocked.presentation.setCategory(EntryListPresentation.Category.entries[screen.categories.selectedIndex])
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

        screen.newVaultButton.addActionListener {
            PasswordDialog.prompt(frame, "Create a new vault")?.let { accept(controller.createVault(it)) }
        }
        screen.openVaultButton.addActionListener {
            PasswordDialog.prompt(frame, "Open vault")?.let { accept(controller.openVault(it)) }
        }
        val addMenu = JPopupMenu().apply {
            add(JMenuItem("Enter account details…").apply { addActionListener { addEntry(Entry.OTPType.TOTP) } })
            add(JMenuItem("Add secure note…").apply { addActionListener { addEntry(Entry.OTPType.NOTE) } })
            add(JMenuItem("Add SSH key…").apply { addActionListener { addEntry(Entry.OTPType.SSH_KEY) } })
            add(JMenuItem("Import passwords…").apply { addActionListener { importPasswords() } })
            addSeparator()
            add(JMenuItem("Import QR image…").apply { addActionListener { importQrImage() } })
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
            if (existing.isPassword() || existing.isPasswordArchive()) return@addActionListener
            EntryDialog.prompt(frame, existing, knownTags = unlocked.entryList.getAllTags())?.let { replacement ->
                if (editor?.replace(existing, replacement) == true) {
                    refreshProjection(unlocked)
                    setStatus("Entry updated")
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
            TagDialog.prompt(frame, entry.getTags().toList(), unlocked.entryList.getAllTags())?.let { tags ->
                if (editor?.setTags(entry, tags) == true) {
                    refreshProjection(unlocked)
                    setStatus("Tags updated")
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
            if (editor?.removeAt(unlocked.presentation.sourceIndex(row)) == true) {
                refreshProjection(unlocked)
                setStatus("Entry deleted")
            } else {
                JOptionPane.showMessageDialog(frame, "The entry could not be deleted from the vault.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }
        screen.copyButton.addActionListener { copySelectedToken() }

        // Right-click or the keyboard context-menu key opens the same row actions.
        val counterDivider = JPopupMenu.Separator()
        val rowMenu = JPopupMenu().apply {
            add(JMenuItem("Copy selected").apply { addActionListener { copySelectedToken() } })
            add(JMenuItem("Edit…").apply { addActionListener { screen.editButton.doClick() } })
            add(JMenuItem("Tags…").apply { addActionListener { screen.tagsButton.doClick() } })
            add(JMenuItem("View password details…").apply { addActionListener { showPasswordDetails() } })
            add(JMenuItem("Export password file…").apply { addActionListener { exportPasswordFile() } })
            add(JMenuItem("Delete").apply { addActionListener { screen.deleteButton.doClick() } })
            add(counterDivider)
            add(JMenuItem("Advance HOTP counter").apply { addActionListener { advanceSelectedHotp() } })
        }
        fun showRowMenu(viewRow: Int, x: Int, y: Int) {
            if (viewRow !in 0 until table.rowCount) return
            table.setRowSelectionInterval(viewRow, viewRow)
            val entry = (table.model as EntryTableModel).entryAt(table.convertRowIndexToModel(viewRow))
            counterDivider.isVisible = entry.isCounterBased()
            rowMenu.components.forEach { component ->
                if (component is JMenuItem) when (component.text) {
                    "Copy selected" -> component.isEnabled = entry.areDetailsVisible() && !entry.isPasswordArchive()
                    "Edit…" -> component.isEnabled = entry.areDetailsVisible() && !entry.isPassword() && !entry.isPasswordArchive()
                    "Tags…" -> component.isEnabled = entry.areDetailsVisible()
                    "View password details…" -> { component.isVisible = entry.isPassword(); component.isEnabled = entry.areDetailsVisible() }
                    "Export password file…" -> { component.isVisible = entry.isPasswordArchive(); component.isEnabled = entry.areDetailsVisible() }
                    "Advance HOTP counter" -> {
                        component.isVisible = entry.isCounterBased()
                        component.isEnabled = entry.areDetailsVisible()
                    }
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

        search.document.addDocumentListener(object : SimpleDocumentListener {
            override fun update() {
                state?.applySearch(search.text)
                (table.model as? EntryTableModel)?.refresh()
            }
        })

        val menuBar = JMenuBar().apply {
            add(JMenu("Vault").apply {
                mnemonic = KeyEvent.VK_V
                add(JMenuItem("New vault…").apply { addActionListener { screen.newVaultButton.doClick() } })
                add(JMenuItem("Open vault…").apply { addActionListener { screen.openVaultButton.doClick() } })
            })
            add(JMenu("Tools").apply {
                mnemonic = KeyEvent.VK_T
                add(JMenuItem("Import QR image…").apply { addActionListener { importQrImage() } })
                add(JMenuItem("Import passwords…").apply { addActionListener { importPasswords() } })
                add(JMenuItem("Export selected password file…").apply { addActionListener { exportPasswordFile() } })
                add(JMenuItem("Encrypted backup…").apply {
                    addActionListener { coordinator?.let { BackupDialogs.saveEncryptedBackup(frame, it) } }
                })
                add(JMenuItem("Restore encrypted backup…").apply {
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
        }

        frame = JFrame(TITLE).apply {
            iconImage = DesktopIcon.load()
            jMenuBar = menuBar
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(event: java.awt.event.WindowEvent) {
                    ticker?.close()
                    hardware.close()
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
