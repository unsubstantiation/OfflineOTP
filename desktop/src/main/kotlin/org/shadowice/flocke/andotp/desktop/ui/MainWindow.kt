package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.control.BackupCoordinator
import org.shadowice.flocke.andotp.desktop.control.DesktopVaultState
import org.shadowice.flocke.andotp.desktop.control.EntryEditor
import org.shadowice.flocke.andotp.desktop.control.TokenTicker
import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
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

    fun createFrame(controller: VaultController): JFrame {
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
            table.model = EntryTableModel(unlocked.presentation)
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
                            JOptionPane.showMessageDialog(frame, "An equal entry already exists.",
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
                    EntryDialog.prompt(frame, existing)?.let { replacement ->
                        editor?.replace(existing, replacement)
                        (table.model as EntryTableModel).refresh()
                    }
                }
            })
            add(JButton("Delete").apply {
                addActionListener {
                    val unlocked = requireUnlocked() ?: return@addActionListener
                    val row = table.selectedRow
                    if (row >= 0 && editor?.removeAt(unlocked.presentation.sourceIndex(row)) == true) {
                        (table.model as EntryTableModel).refresh()
                        setStatus("Entry deleted")
                    }
                }
            })
        }

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
