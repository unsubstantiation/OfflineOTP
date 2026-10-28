package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.HardwareOathClient
import org.shadowice.flocke.andotp.desktop.control.DesktopVaultState
import org.shadowice.flocke.andotp.desktop.control.HardwareKeyManager

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/**
 * Modal security-key console, the desktop counterpart of Android's HardwareKeyActivity:
 * OATH account listing/calculation, vault-entry copy-to-key and FIDO2 PRF enrollment/removal.
 *
 * Heavyweight by construction (display-only, compile-verified in the sandbox): every protocol
 * decision runs in the headless [HardwareKeyManager], jobs execute on [SwingWorker] threads with a
 * cancelable progress dialog, and no provider exception text ever reaches the UI.
 */
object HardwareKeyDialog {

    private const val NAME_COL = 0
    private const val TYPE_COL = 1
    private const val CODE_COL = 2

    fun show(parent: JFrame, manager: HardwareKeyManager, state: DesktopVaultState) {
        val dialog = JDialog(parent, "Security key", true)
        val pin = JPasswordField(20)
        val accounts = ArrayList<HardwareOathClient.Account>()
        val tableModel = object : DefaultTableModel(arrayOf("Account", "Type", "Code"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val table = JTable(tableModel)
        var deviceId = ""

        fun pinChars(): CharArray? {
            val chars = pin.password
            return if (chars.isEmpty()) null else chars
        }

        fun resultMessage(result: HardwareKeyManager.Result<*>) = when (result) {
            is HardwareKeyManager.Result.Ok -> null
            HardwareKeyManager.Result.NoKey -> "No security key was detected. Insert a key and try again."
            HardwareKeyManager.Result.Canceled -> "The security-key operation was canceled."
            HardwareKeyManager.Result.Failed -> "The security-key operation failed."
        }

        fun <T> runJob(
            title: String,
            block: (HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<T>,
            onOk: (T) -> Unit
        ) = runHardwareJob(parent, title, block) { result ->
            resultMessage(result)?.let { JOptionPane.showMessageDialog(dialog, it, MainWindow.TITLE,
                JOptionPane.WARNING_MESSAGE) }
            if (result is HardwareKeyManager.Result.Ok) onOk(result.value)
        }

        val listButton = JButton("List accounts").apply {
            addActionListener {
                runJob("Reading security key", { token ->
                    manager.listOathAccounts(pinChars(), token)
                }) { listed ->
                    deviceId = listed.deviceId
                    accounts.clear()
                    accounts.addAll(listed.items)
                    renderAccounts(tableModel, accounts)
                }
            }
        }

        val calculateButton = JButton("Calculate selected").apply {
            addActionListener {
                val row = table.selectedRow
                if (row < 0 || row >= accounts.size) {
                    JOptionPane.showMessageDialog(dialog, "Select an account first.",
                        MainWindow.TITLE, JOptionPane.WARNING_MESSAGE)
                    return@addActionListener
                }
                val account = accounts[row]
                if (account.credential.oathType == com.yubico.yubikit.oath.OathType.HOTP &&
                    JOptionPane.showConfirmDialog(dialog,
                        "Calculating an HOTP code advances the counter on the key. Continue?",
                        "HOTP calculation", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                    return@addActionListener
                }
                runJob("Contacting security key", { token ->
                    manager.calculateOath(pinChars(), deviceId, account, token)
                }) { refreshed ->
                    accounts[row] = refreshed
                    renderAccounts(tableModel, accounts)
                }
            }
        }

        val copyCodeButton = JButton("Copy code").apply {
            addActionListener {
                val row = table.selectedRow
                if (row < 0 || row >= accounts.size) return@addActionListener
                val code = accounts[row].code
                if (code.isNullOrEmpty()) {
                    JOptionPane.showMessageDialog(dialog, "Calculate the code first.",
                        MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
                    return@addActionListener
                }
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(code), null)
            }
        }

        val copyEntryButton = JButton("Copy vault entry to key…").apply {
            addActionListener {
                val candidates = state.snapshot().filter { HardwareOathClient.canCopy(it) }
                if (candidates.isEmpty()) {
                    JOptionPane.showMessageDialog(dialog,
                        "No vault entry has parameters the key can store.",
                        MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
                    return@addActionListener
                }
                val names = candidates.map { displayName(it.getIssuer(), it.getLabel()) }.toTypedArray()
                val choice = JOptionPane.showOptionDialog(dialog, "Choose an entry to copy:",
                    "Copy to key", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, names, names[0])
                if (choice < 0) return@addActionListener
                val requireTouch = JOptionPane.showConfirmDialog(dialog,
                    "Require a physical touch to calculate this code on the key?",
                    "Require touch", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
                val selected = candidates[choice]
                runJob("Writing to security key", { token ->
                    manager.copyEntryToKey(pinChars(), selected, requireTouch, token)
                }) {
                    JOptionPane.showMessageDialog(dialog, "The entry was copied to the security key.",
                        MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
                }
            }
        }

        val enrollButton = JButton("Enroll hardware unlock…").apply {
            addActionListener {
                if (JOptionPane.showConfirmDialog(dialog,
                        "This seals the current vault key behind the key's FIDO2 PRF. The existing " +
                            "enrollment (if any) is replaced. Continue?",
                        "Hardware unlock", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return@addActionListener
                runJob("Enrolling security key", { token ->
                    manager.enroll(pinChars(), state.key, token)
                }) {
                    JOptionPane.showMessageDialog(dialog, "Hardware unlock enrolled.",
                        MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
                }
            }
        }

        val removeButton = JButton("Remove enrollment").apply {
            addActionListener {
                if (JOptionPane.showConfirmDialog(dialog,
                        "Remove the hardware-unlock envelope from this vault directory?",
                        "Remove enrollment", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return@addActionListener
                if (manager.removeEnrollment()) {
                    JOptionPane.showMessageDialog(dialog, "The enrollment was removed.",
                        MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
                } else {
                    JOptionPane.showMessageDialog(dialog, "No enrollment was present.",
                        MainWindow.TITLE, JOptionPane.INFORMATION_MESSAGE)
                }
            }
        }

        // Expire TOTP codes visually once their validity window passes, mirroring the Android list.
        val tick = Timer(1000, null)
        tick.addActionListener { renderAccounts(tableModel, accounts) }
        tick.start()

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(listButton); add(calculateButton); add(copyCodeButton)
            add(copyEntryButton); add(enrollButton); add(removeButton)
        }
        val form = JPanel(GridLayout(0, 2, 6, 6)).apply {
            add(JLabel("Key PIN (blank if none)")); add(pin)
        }
        dialog.contentPane = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(form, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.minimumSize = Dimension(640, 360)
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
        tick.stop()
        // The manager wipes each per-operation array; clear the field's own document as well.
        pin.text = ""
    }

    private fun renderAccounts(
        model: DefaultTableModel, accounts: List<HardwareOathClient.Account>
    ) {
        val now = System.currentTimeMillis()
        model.rowCount = 0
        for (account in accounts) {
            val credential = account.credential
            val name = displayName(credential.issuer.orEmpty(), credential.accountName)
            val type = credential.oathType.name
            val code = account.code?.takeIf { account.usableAt(now) } ?: "—"
            model.addRow(arrayOf(name, type, code))
        }
    }

    /** Account labels may come from a key or QR; strip control/format characters and bound length. */
    private fun displayName(issuer: String, account: String): String =
        listOf(issuer, account).filter(String::isNotBlank).joinToString(" / ")
            .map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
            .joinToString("")
            .trim()
            .take(256)
            .ifEmpty { "Unnamed" }

    /**
     * Hardware-unlock entry point used by the main window before any vault is open. Returns the
     * recovered key through [onReady] on the event thread; every failure is surfaced as a message
     * and produces no callback.
     */
    fun requestUnlock(
        parent: JFrame,
        manager: HardwareKeyManager,
        onReady: (javax.crypto.SecretKey) -> Unit
    ) {
        val pin = PasswordDialog.prompt(parent, "Key PIN (blank if none)")?.toCharArray()
        runHardwareJob(parent, "Unlocking with security key", { token ->
            manager.unlockWithKey(pin, token)
        }) { result ->
            when (result) {
                is HardwareKeyManager.Result.Ok -> onReady(result.value)
                else -> JOptionPane.showMessageDialog(parent,
                    "Hardware unlock did not succeed: ${result.javaClass.simpleName}",
                    MainWindow.TITLE, JOptionPane.WARNING_MESSAGE)
            }
        }
    }

    /**
     * Runs [block] off the event thread with a modal progress dialog whose Cancel button trips the
     * [HardwareKeyManager.CancelToken]; [onDone] always runs back on the event thread.
     */
    internal fun <T> runHardwareJob(
        parent: JFrame,
        title: String,
        block: (HardwareKeyManager.CancelToken) -> HardwareKeyManager.Result<T>,
        onDone: (HardwareKeyManager.Result<T>) -> Unit
    ) {
        val token = HardwareKeyManager.CancelToken()
        val progress = JDialog(parent, title, true).apply {
            contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(JLabel("Communicating with the security key…"), BorderLayout.CENTER)
                add(JButton("Cancel").apply {
                    addActionListener { token.cancel() }
                }, BorderLayout.SOUTH)
            }
            pack()
            setLocationRelativeTo(parent)
        }
        val worker = object : SwingWorker<HardwareKeyManager.Result<T>, Unit>() {
            override fun doInBackground(): HardwareKeyManager.Result<T> = block(token)
            override fun done() {
                progress.dispose()
                if (!isCancelled) {
                    runCatching { onDone(get()) }
                }
            }
        }
        worker.execute()
        progress.isVisible = true
    }
}
