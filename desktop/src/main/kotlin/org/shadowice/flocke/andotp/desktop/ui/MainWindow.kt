package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.control.BackupCoordinator
import org.shadowice.flocke.andotp.desktop.control.DesktopVaultState
import org.shadowice.flocke.andotp.desktop.control.EntryEditor
import org.shadowice.flocke.andotp.desktop.control.HardwareKeyManager
import org.shadowice.flocke.andotp.desktop.control.TokenTicker
import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToolBar
import javax.swing.SwingConstants
import javax.swing.WindowConstants

/**
 * Builds the top-level Swing window.
 *
 * Heavyweight by construction: call only on the event dispatch thread with a display. The frame is
 * deliberately thin — vault, entry, backup and timer logic lives in headless `:core`/control
 * classes; this shell wires menus, a search field and the table model to them. Opening a vault
 * prompts for a password, builds a [DesktopVaultState] and starts the one-second [TokenTicker].
 */
object MainWindow {
    const val TITLE = "OfflineOTP"

    fun createFrame(controller: VaultController, hardware: HardwareKeyManager): JFrame {
        val table = JTable(EntryTableModel(EntryListPresentation(EntryList()).apply {
            showReorderedEntries()
        }))
        val status = JLabel(" ", SwingConstants.LEFT)
        val search = JTextField(20)

        lateinit var frame: JFrame
        var state: DesktopVaultState? = null
        var ticker: TokenTicker? = null
        var coordinator: BackupCoordinator? = null
        var editor: EntryEditor? = null

        fun setStatus(message: String) {
            status.text = message
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

        fun bind(session: VaultController.Session.Loaded) {
            val unlocked = DesktopVaultState(session.key).apply { load(session.entries) }
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
            table.columnModel.getColumn(EntryTableModel.Column.SHOW.ordinal).apply {
                preferredWidth = 55
                maxWidth = 70
            }
            search.text = ""

            ticker?.close()
            ticker = TokenTicker(SwingScheduler(), unlocked) {
                (table.model as? EntryTableModel)?.refresh()
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

        /** Places the selected entry's current token on the clipboard, Android copy parity:
         *  last-used/frequency are updated and the vault re-persisted before any clipboard write. */
        fun copySelectedToken() {
            requireUnlocked() ?: return
            val row = table.selectedRow
            if (row < 0) {
                JOptionPane.showMessageDialog(frame, "Select an entry first.", TITLE,
                    JOptionPane.WARNING_MESSAGE)
                return
            }
            val entry = (table.model as EntryTableModel).entryAt(row)
            if (!entry.areDetailsVisible()) return
            val result = if (entry.isSshKey()) {
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
                        entry.isSshKey() -> "SSH key copied to the clipboard"
                        entry.isNote() -> "Note copied to the clipboard"
                        else -> "Token copied to the clipboard"
                    })
                }
                EntryEditor.TokenCopyResult.Unavailable ->
                    JOptionPane.showMessageDialog(frame,
                        "No token is available for that entry yet.",
                        TITLE, JOptionPane.INFORMATION_MESSAGE)
                EntryEditor.TokenCopyResult.PersistenceFailed ->
                    JOptionPane.showMessageDialog(frame,
                        "The item was not copied: the vault could not be written.",
                        TITLE, JOptionPane.ERROR_MESSAGE)
                null -> Unit
            }
        }

        /** Desktop counterpart of the Android counter chip: explicit HOTP counter advance. */
        fun advanceSelectedHotp() {
            requireUnlocked() ?: return
            val row = table.selectedRow
            if (row < 0) {
                JOptionPane.showMessageDialog(frame, "Select an entry first.", TITLE,
                    JOptionPane.WARNING_MESSAGE)
                return
            }
            val entry = (table.model as EntryTableModel).entryAt(row)
            if (!entry.isCounterBased()) {
                JOptionPane.showMessageDialog(frame, "Only HOTP entries have a counter to advance.",
                    TITLE, JOptionPane.INFORMATION_MESSAGE)
                return
            }
            if (editor?.advanceHotp(entry) == true) {
                (table.model as? EntryTableModel)?.refresh()
                setStatus("HOTP counter advanced")
            } else {
                JOptionPane.showMessageDialog(frame,
                    "The HOTP counter could not be advanced, or the vault could not be written.",
                    TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }

        val toolbar = JToolBar().apply {
            isFloatable = false
            add(JButton("New vault…").apply {
                addActionListener {
                    PasswordDialog.prompt(frame, "Create a new vault")?.let {
                        accept(controller.createVault(it))
                    }
                }
            })
            add(JButton("Open vault…").apply {
                addActionListener {
                    PasswordDialog.prompt(frame, "Open vault")?.let {
                        accept(controller.openVault(it))
                    }
                }
            })
            add(JButton("Add entry…").apply {
                addActionListener {
                    val unlocked = requireUnlocked() ?: return@addActionListener
                    EntryDialog.prompt(frame)?.let { entry ->
                        if (editor?.add(entry) == true) {
                            unlocked.presentation.showReorderedEntries()
                            (table.model as EntryTableModel).refresh()
                            setStatus("Entry added")
                        } else {
                            entry.destroy()
                            JOptionPane.showMessageDialog(frame, "The entry already exists or could not be saved.",
                                TITLE, JOptionPane.WARNING_MESSAGE)
                        }
                    }
                }
            })
            add(JButton("Edit…").apply {
                addActionListener {
                    val unlocked = requireUnlocked() ?: return@addActionListener
                    val row = table.selectedRow
                    if (row < 0) {
                        JOptionPane.showMessageDialog(frame, "Select an entry first.", TITLE,
                            JOptionPane.WARNING_MESSAGE)
                        return@addActionListener
                    }
                    val existing = unlocked.presentation.get(row)
                    if (!existing.areDetailsVisible()) return@addActionListener
                    EntryDialog.prompt(frame, existing)?.let { replacement ->
                        if (editor?.replace(existing, replacement) == true) {
                            (table.model as EntryTableModel).refresh()
                            setStatus("Entry updated")
                        } else {
                            replacement.destroy()
                            JOptionPane.showMessageDialog(frame,
                                "The entry could not be saved to the vault.",
                                TITLE, JOptionPane.ERROR_MESSAGE)
                        }
                    }
                }
            })
            add(JButton("Delete").apply {
                addActionListener {
                    val unlocked = requireUnlocked() ?: return@addActionListener
                    val row = table.selectedRow
                    if (row < 0) {
                        JOptionPane.showMessageDialog(frame, "Select an entry first.", TITLE,
                            JOptionPane.WARNING_MESSAGE)
                        return@addActionListener
                    }
                    if (editor?.removeAt(unlocked.presentation.sourceIndex(row)) == true) {
                        (table.model as EntryTableModel).refresh()
                        setStatus("Entry deleted")
                    } else {
                        JOptionPane.showMessageDialog(frame,
                            "The entry could not be deleted from the vault.",
                            TITLE, JOptionPane.ERROR_MESSAGE)
                    }
                }
            })
            add(JButton("Copy token / note / key").apply {
                toolTipText = "Copy the selected item (or double-click its row); choose an SSH key explicitly"
                addActionListener { copySelectedToken() }
            })
        }

        // Right-click context menu; the HOTP counter item only appears for counter-based entries.
        val rowMenu = JPopupMenu().apply {
            add(JMenuItem("Copy token / note / key").apply { addActionListener { copySelectedToken() } })
            addSeparator()
            add(JMenuItem("Advance HOTP counter").apply {
                addActionListener { advanceSelectedHotp() }
            })
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) {
                    val row = table.rowAtPoint(event.point)
                    if (row >= 0 && table.columnAtPoint(event.point) != EntryTableModel.Column.SHOW.ordinal) {
                        table.setRowSelectionInterval(row, row)
                        copySelectedToken()
                    }
                }
            }

            override fun mousePressed(event: MouseEvent) = showRowMenu(event)
            override fun mouseReleased(event: MouseEvent) = showRowMenu(event)

            private fun showRowMenu(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                val row = table.rowAtPoint(event.point)
                if (row < 0) return
                table.setRowSelectionInterval(row, row)
                rowMenu.components.forEach { component ->
                    if (component is JMenuItem && component.text == "Advance HOTP counter") {
                        component.isVisible =
                            (table.model as EntryTableModel).entryAt(row).isCounterBased()
                    }
                }
                rowMenu.show(table, event.x, event.y)
            }
        })

        search.toolTipText = "Search issuer, label or tags"
        search.document.addDocumentListener(object : SimpleDocumentListener {
            override fun update() {
                state?.applySearch(search.text)
                (table.model as? EntryTableModel)?.refresh()
            }
        })

        val menuBar = JMenuBar().apply {
            add(JMenu("Vault").apply {
                add(JMenuItem("New vault…").apply {
                    addActionListener {
                        PasswordDialog.prompt(frame, "Create a new vault")?.let {
                            accept(controller.createVault(it))
                        }
                    }
                })
                add(JMenuItem("Open vault…").apply {
                    addActionListener {
                        PasswordDialog.prompt(frame, "Open vault")?.let {
                            accept(controller.openVault(it))
                        }
                    }
                })
            })
            add(JMenu("Tools").apply {
                add(JMenuItem("Import QR image…").apply {
                    addActionListener {
                        val active = coordinator ?: return@addActionListener
                        BackupDialogs.importQrImage(frame, active)
                        (table.model as? EntryTableModel)?.refresh()
                    }
                })
                add(JMenuItem("Encrypted backup…").apply {
                    addActionListener { coordinator?.let { BackupDialogs.saveEncryptedBackup(frame, it) } }
                })
                add(JMenuItem("Restore encrypted backup…").apply {
                    addActionListener {
                        coordinator?.let {
                            BackupDialogs.restoreEncryptedBackup(frame, it)
                            (table.model as EntryTableModel).refresh()
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
                            (table.model as EntryTableModel).refresh()
                        }
                    }
                })
            })
            add(JMenu("Hardware").apply {
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

        val top = JPanel(BorderLayout()).apply {
            add(JLabel(" Search: "), BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }
        val content = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }

        frame = JFrame(TITLE).apply {
            jMenuBar = menuBar
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(event: java.awt.event.WindowEvent) {
                    ticker?.close()
                    hardware.close()
                }
            })
            contentPane = JPanel(BorderLayout()).apply {
                add(top, BorderLayout.NORTH)
                add(content, BorderLayout.CENTER)
            }
            minimumSize = Dimension(720, 420)
            pack()
            setLocationRelativeTo(null)
        }
        return frame
    }
}
