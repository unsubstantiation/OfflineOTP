package org.shadowice.flocke.andotp.desktop.ui

import com.yubico.yubikit.core.YubiKeyDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.desktop.control.HardwareKeyManager
import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.hardware.HardwareDeviceSource
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel
import org.shadowice.flocke.andotp.desktop.storage.DesktopHardwareUnlockStore
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPasswordField
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/**
 * Opt-in display tests. Run with -Dofflineotp.uiTests=true and a real/virtual display; the normal
 * headless sandbox skips these. They exercise real JFrame/modal/popup behavior with fake hardware
 * and a disposable synthetic vault; they never touch the user's vault or attached keys.
 */
class MainWindowDisplayTest {
    private val password = "display-test-password"

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap { components(it) } else emptyList()

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (edt(predicate)) return
            Thread.sleep(40)
        }
        fail("Timed out waiting for $message")
    }

    private fun dialog(title: String): JDialog? = Window.getWindows().filterIsInstance<JDialog>()
        .firstOrNull { it.isVisible && it.title == title }

    private data class Fixture(
        val directory: File, val controller: VaultController,
        val hardware: HardwareKeyManager, val frame: JFrame
    )

    private fun fixture(): Fixture {
        assumeTrue("display tests are opt-in", System.getProperty("offlineotp.uiTests") == "true")
        assumeFalse("a display is required", GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("offlineotp-display-test-").toFile()
        val controller = VaultController(DesktopStorage.open(directory))
        val session = controller.createVault(password) as VaultController.Session.Loaded
        val entries = arrayListOf(
            Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Private issuer", "Hidden account",
                TokenCalculator.HashAlgorithm.SHA1, arrayListOf()),
            Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Example service", "Shown account",
                TokenCalculator.HashAlgorithm.SHA1, arrayListOf()).apply { setDetailsVisible(true) }
        )
        assertTrue(controller.save(session.key, entries))
        val hardware = HardwareKeyManager(object : HardwareDeviceSource {
            override fun scan(): List<YubiKeyDevice> = emptyList()
            override fun close() = Unit
        }, DesktopHardwareUnlockStore(directory))
        val frame = edt {
            MainWindow.createFrame(controller, hardware).apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                isVisible = true
            }
        }
        return Fixture(directory, controller, hardware, frame)
    }

    private fun close(fixture: Fixture) {
        edt {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            Window.getWindows().filterIsInstance<JDialog>().filter { it.isVisible }.forEach { it.dispose() }
            fixture.frame.dispatchEvent(WindowEvent(fixture.frame, WindowEvent.WINDOW_CLOSING))
            fixture.hardware.close()
        }
        fixture.directory.deleteRecursively()
    }

    private fun button(frame: JFrame, name: String): JButton =
        components(frame).filterIsInstance<JButton>().first { it.text == name }

    private fun table(frame: JFrame): JTable = components(frame).filterIsInstance<JTable>().first()

    private fun card(table: JTable, row: Int): Component = table.getCellRenderer(row, 0)
        .getTableCellRendererComponent(table, table.getValueAt(row, 0), false, false, row, 0)

    private fun open(fixture: Fixture) {
        val openButton = edt { button(fixture.frame, "Open vault…") }
        SwingUtilities.invokeLater { openButton.doClick() }
        await("password dialog") { dialog("Open vault") != null }
        edt {
            val modal = dialog("Open vault")!!
            assertEquals(ForestTheme.SURFACE_ELEVATED, modal.contentPane.background)
            val passwordField = components(modal).filterIsInstance<JPasswordField>().first()
            assertEquals(ForestTheme.SURFACE, passwordField.background)
            assertEquals(ForestTheme.TEXT_PRIMARY, passwordField.foreground)
            passwordField.text = password
            optionButton(modal, "ok", "OK").doClick()
        }
        await("unlocked entries") { table(fixture.frame).rowCount == 2 }
    }

    private fun openAddForm(fixture: Fixture, menuText: String, title: String): JDialog {
        edt { button(fixture.frame, "+  Add entry").doClick() }
        await("add popup") { MenuSelectionManager.defaultManager().selectedPath.any { it is JPopupMenu } }
        val item = edt {
            MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JPopupMenu>().last()
                .components.filterIsInstance<JMenuItem>().first { it.text == menuText }
        }
        SwingUtilities.invokeLater { item.doClick() }
        await(title) { dialog(title) != null }
        return edt { dialog(title)!! }
    }

    private fun labelled(modal: JDialog, name: String): Component =
        components(modal).filterIsInstance<JLabel>().first { it.text == name }.labelFor

    private fun optionButton(modal: JDialog, key: String, fallback: String): JButton {
        val text = UIManager.getString("OptionPane.${key}ButtonText", modal.locale) ?: fallback
        return components(modal).filterIsInstance<JButton>().first { it.text == text }
    }

    @Test
    fun frameCardsVisibilitySearchCopyAndTickerWorkOnADisplay() {
        val fixture = fixture()
        try {
            open(fixture)
            val robot = Robot().apply { autoDelay = 40 }
            edt {
                val table = table(fixture.frame)
                assertEquals(2, table.columnCount)
                assertEquals(EntryTableModel.Column.SHOW.ordinal, table.convertColumnIndexToModel(1))
                val rendered = card(table, 0)
                assertTrue(components(rendered).filterIsInstance<JLabel>()
                    .any { it.text == "Private issuer – Hidden account" })
                assertEquals("Hidden, Private issuer – Hidden account", rendered.accessibleContext.accessibleName)
                val model = table.model as EntryTableModel
                assertEquals("", model.getValueAt(0, EntryTableModel.Column.TYPE.ordinal))
                assertEquals("", model.getValueAt(0, EntryTableModel.Column.TOKEN.ordinal))
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection("unchanged"), null)
            }
            // A double-click on a hidden card must not copy a token.
            val hiddenPoint = edt {
                table(fixture.frame).let { t ->
                    val cell = t.getCellRect(0, 0, true)
                    val origin = t.locationOnScreen
                    java.awt.Point(origin.x + cell.x + 150, origin.y + cell.y + cell.height / 2)
                }
            }
            robot.mouseMove(hiddenPoint.x, hiddenPoint.y)
            repeat(2) { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            robot.waitForIdle()
            assertEquals("unchanged", Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor))

            // Click the actual Show editor; the saved choice and selection must survive a timer tick.
            val showPoint = edt {
                table(fixture.frame).let { t ->
                    val cell = t.getCellRect(0, 1, true)
                    val origin = t.locationOnScreen
                    java.awt.Point(origin.x + cell.x + cell.width / 2, origin.y + cell.y + cell.height / 2)
                }
            }
            robot.mouseMove(showPoint.x, showPoint.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            await("visibility toggle") { (table(fixture.frame).model as EntryTableModel).getValueAt(0, 4) == true }
            Thread.sleep(1300)
            edt {
                assertEquals(0, table(fixture.frame).selectedRow)
                assertTrue(button(fixture.frame, "Copy token / note / key").isEnabled)
            }
            val reopened = fixture.controller.openVault(password) as VaultController.Session.Loaded
            assertTrue(reopened.entries[0].areDetailsVisible())

            edt { table(fixture.frame).requestFocusInWindow() }
            await("table focus") { table(fixture.frame).isFocusOwner }
            robot.keyPress(KeyEvent.VK_SPACE); robot.keyRelease(KeyEvent.VK_SPACE)
            await("keyboard hide") { (table(fixture.frame).model as EntryTableModel).getValueAt(0, 4) == false }
            edt {
                val table = table(fixture.frame)
                assertEquals("Hidden, Private issuer – Hidden account", card(table, 0).accessibleContext.accessibleName)
                assertEquals("", (table.model as EntryTableModel).getValueAt(0, EntryTableModel.Column.TOKEN.ordinal))
                assertFalse(button(fixture.frame, "Copy token / note / key").isEnabled)
            }
            robot.keyPress(KeyEvent.VK_SPACE); robot.keyRelease(KeyEvent.VK_SPACE)
            await("keyboard reveal") { (table(fixture.frame).model as EntryTableModel).getValueAt(0, 4) == true }
            edt {
                assertTrue(card(table(fixture.frame), 0).accessibleContext.accessibleName
                    .contains("Private issuer – Hidden account"))
            }
            robot.keyPress(KeyEvent.VK_SHIFT); robot.keyPress(KeyEvent.VK_F10)
            robot.keyRelease(KeyEvent.VK_F10); robot.keyRelease(KeyEvent.VK_SHIFT)
            await("keyboard row menu") {
                MenuSelectionManager.defaultManager().selectedPath.any { it is JPopupMenu }
            }
            edt {
                val popup = MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JPopupMenu>().last()
                assertTrue(popup.components.filterIsInstance<JMenuItem>()
                    .any { it.text == "Copy token / note / key" && it.isEnabled })
                MenuSelectionManager.defaultManager().clearSelectedPath()
            }

            await("generated token") {
                ((table(fixture.frame).model as EntryTableModel).getValueAt(0, 3) as String).isNotBlank()
            }
            edt { button(fixture.frame, "Copy token / note / key").doClick() }
            val copied = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
            assertEquals(6, copied.length)
            assertTrue(copied.all { it.isDigit() })

            edt {
                val search = components(fixture.frame).filterIsInstance<JTextField>()
                    .first { it.accessibleContext.accessibleName == "Search entries" }
                search.text = "Shown account"
            }
            await("filtered card") { table(fixture.frame).rowCount == 1 }
            edt {
                val model = table(fixture.frame).model as EntryTableModel
                assertEquals("Shown account", model.getValueAt(0, EntryTableModel.Column.LABEL.ordinal))
            }
            edt {
                components(fixture.frame).filterIsInstance<JTextField>()
                    .first { it.accessibleContext.accessibleName == "Search entries" }.text = "no match"
            }
            await("empty search") {
                table(fixture.frame).rowCount == 0 &&
                    components(fixture.frame).filterIsInstance<JLabel>().any { it.text == "No matching entries" }
            }
        } finally { close(fixture) }
    }

    @Test
    fun addMenuOpensTheDirectNoteAndSshForms() {
        val fixture = fixture()
        try {
            open(fixture)
            for ((menuText, title, expected) in listOf(
                Triple("Add secure note…", "Add secure note", Entry.OTPType.NOTE),
                Triple("Add SSH key…", "Add SSH key", Entry.OTPType.SSH_KEY)
            )) {
                val modal = openAddForm(fixture, menuText, title)
                edt {
                    assertEquals(ForestTheme.SURFACE_ELEVATED, modal.contentPane.background)
                    val type = components(modal).filterIsInstance<JComboBox<*>>().first()
                    assertEquals(expected, type.selectedItem)
                    assertTrue(type.ui is ForestComboBoxUI)
                    val labels = components(modal).filterIsInstance<JLabel>()
                    assertFalse(labels.first { it.text == "Digits" }.isVisible)
                    assertEquals(expected == Entry.OTPType.SSH_KEY,
                        labels.first { it.text == "SSH public key (optional)" }.isVisible)
                    val areas = components(modal).filterIsInstance<JTextArea>()
                    assertTrue(areas.isNotEmpty())
                    assertEquals(ForestTheme.SURFACE, areas.first().background)
                    // Switching types reflows the form and restores the appropriate OTP controls.
                    type.selectedItem = Entry.OTPType.TOTP
                    assertTrue(labels.first { it.text == "Digits" }.isVisible)
                    type.selectedItem = expected
                    assertFalse(labels.first { it.text == "Digits" }.isVisible)
                    optionButton(modal, "cancel", "Cancel").doClick()
                }
                await("form dismissed") { dialog(title) == null }
            }
        } finally { close(fixture) }
    }

    @Test
    fun directNoteAndSshActionsSaveWhilePreservingAnActiveSearch() {
        val fixture = fixture()
        try {
            open(fixture)
            val search = edt {
                components(fixture.frame).filterIsInstance<JTextField>()
                    .first { it.accessibleContext.accessibleName == "Search entries" }
            }
            edt { search.text = "Recovery" }
            await("empty filtered list") { table(fixture.frame).rowCount == 0 }

            val note = openAddForm(fixture, "Add secure note…", "Add secure note")
            edt {
                (labelled(note, "Label") as JTextField).text = "Recovery"
                (labelled(note, "Note (up to 200 characters)") as JTextArea).text = "synthetic recovery note"
                optionButton(note, "ok", "OK").doClick()
            }
            await("saved filtered note") { table(fixture.frame).rowCount == 1 }
            edt {
                val model = table(fixture.frame).model as EntryTableModel
                assertTrue(model.entryAt(0).isNote())
                assertEquals(false, model.getValueAt(0, EntryTableModel.Column.SHOW.ordinal))
            }

            val key = openAddForm(fixture, "Add SSH key…", "Add SSH key")
            edt {
                (labelled(key, "Label") as JTextField).text = "Test server"
                (labelled(key, "SSH public key (optional)") as JTextArea).text = "synthetic public key"
                (labelled(key, "SSH private key (optional)") as JTextArea).text = "synthetic private key"
                optionButton(key, "ok", "OK").doClick()
            }
            await("key form dismissed") { dialog("Add SSH key") == null }
            edt { assertEquals(1, table(fixture.frame).rowCount) } // search still filters out the key
            val loaded = fixture.controller.openVault(password) as VaultController.Session.Loaded
            assertEquals(4, loaded.entries.size)
            val savedNote = loaded.entries.first { it.isNote() }
            val savedKey = loaded.entries.first { it.isSshKey() }
            assertEquals("synthetic recovery note", savedNote.getNote())
            assertEquals("synthetic public key", savedKey.getSshKeys().publicKey)
            assertEquals("synthetic private key", savedKey.getSshKeys().privateKey)
            assertFalse(savedNote.areDetailsVisible())
            assertFalse(savedKey.areDetailsVisible())
            edt { search.text = "" }
            await("full list restored") { table(fixture.frame).rowCount == 4 }
        } finally { close(fixture) }
    }

    @Test
    fun minimumWindowSizeKeepsMainControlsAndCardsReachable() {
        val fixture = fixture()
        try {
            open(fixture)
            edt {
                fixture.frame.size = Dimension(720, 520)
                fixture.frame.validate()
                val content = fixture.frame.contentPane
                val add = button(fixture.frame, "+  Add entry")
                val addBounds = SwingUtilities.convertRectangle(add.parent, add.bounds, content)
                assertTrue(addBounds.x >= 0 && addBounds.x + addBounds.width <= content.width)
                val search = components(fixture.frame).filterIsInstance<JTextField>()
                    .first { it.accessibleContext.accessibleName == "Search entries" }
                assertTrue(search.width >= 300)
                assertTrue(table(fixture.frame).visibleRect.height >= 100)
                assertTrue(button(fixture.frame, "Open vault…").isShowing)
            }
            val modal = openAddForm(fixture, "Add secure note…", "Add secure note")
            edt {
                assertTrue(modal.width <= fixture.frame.width)
                assertTrue(modal.height <= fixture.frame.height)
                optionButton(modal, "cancel", "Cancel").doClick()
            }
        } finally { close(fixture) }
    }

    @Test
    fun hotpCounterRemainsAvailableOnlyForARevealedCounterCard() {
        val fixture = fixture()
        try {
            open(fixture)
            val modal = openAddForm(fixture, "Enter account details…", "Add entry")
            edt {
                (labelled(modal, "Issuer") as JTextField).text = "Counter service"
                (labelled(modal, "Label") as JTextField).text = "Counter account"
                (labelled(modal, "Secret (Base32)") as JTextArea).text = "JBSWY3DPEHPK3PXP"
                val type = components(modal).filterIsInstance<JComboBox<*>>().first()
                type.selectedItem = Entry.OTPType.HOTP
                (labelled(modal, "HOTP counter") as JTextField).text = "0"
                optionButton(modal, "ok", "OK").doClick()
            }
            await("added HOTP card") { table(fixture.frame).rowCount == 3 }
            edt {
                val table = table(fixture.frame)
                table.setRowSelectionInterval(2, 2)
                (table.model as EntryTableModel).setValueAt(true, 2, EntryTableModel.Column.SHOW.ordinal)
                table.actionMap.get("showEntryMenu").actionPerformed(
                    ActionEvent(table, ActionEvent.ACTION_PERFORMED, "menu"))
                val popup = MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JPopupMenu>().last()
                val advance = popup.components.filterIsInstance<JMenuItem>().first { it.text == "Advance HOTP counter" }
                assertTrue(advance.isVisible)
                assertTrue(advance.isEnabled)
                advance.doClick()
            }
            await("advanced HOTP counter") {
                (table(fixture.frame).model as EntryTableModel).entryAt(2).getCounter() == 1L
            }
            val reopened = fixture.controller.openVault(password) as VaultController.Session.Loaded
            assertEquals(1L, reopened.entries.first { it.isCounterBased() }.getCounter())
            edt {
                val table = table(fixture.frame)
                (table.model as EntryTableModel).setValueAt(false, 2, EntryTableModel.Column.SHOW.ordinal)
                table.actionMap.get("showEntryMenu").actionPerformed(
                    ActionEvent(table, ActionEvent.ACTION_PERFORMED, "menu"))
                val popup = MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JPopupMenu>().last()
                val advance = popup.components.filterIsInstance<JMenuItem>().first { it.text == "Advance HOTP counter" }
                assertTrue(advance.isVisible)
                assertFalse(advance.isEnabled)
                MenuSelectionManager.defaultManager().clearSelectedPath()
            }
        } finally { close(fixture) }
    }

    @Test
    fun fileChooserAndHardwareDialogUseTheSameForestTheme() {
        val fixture = fixture()
        try {
            open(fixture)
            val tools = edt { fixture.frame.jMenuBar.getMenu(1) }
            val import = edt { tools.menuComponents.filterIsInstance<JMenuItem>().first { it.text == "Import QR image…" } }
            SwingUtilities.invokeLater { import.doClick() }
            await("QR file chooser") { dialog("Select a QR code image") != null }
            edt {
                val modal = dialog("Select a QR code image")!!
                val chooser = components(modal).filterIsInstance<JFileChooser>().first()
                assertTrue(components(chooser).filterIsInstance<JLabel>()
                    .filter { it.isVisible }.all { it.foreground == ForestTheme.TEXT_PRIMARY })
                assertTrue(components(chooser).filterIsInstance<JButton>().all { it.ui is ForestButtonUI })
                chooser.cancelSelection()
            }
            await("chooser dismissed") { dialog("Select a QR code image") == null }

            val hardware = edt { fixture.frame.jMenuBar.getMenu(2) }
            val security = edt { hardware.menuComponents.filterIsInstance<JMenuItem>().first { it.text == "Security key…" } }
            SwingUtilities.invokeLater { security.doClick() }
            await("hardware dialog") { dialog("Security key") != null }
            edt {
                val modal = dialog("Security key")!!
                assertEquals(ForestTheme.SURFACE_ELEVATED, modal.contentPane.background)
                val pin = components(modal).filterIsInstance<JPasswordField>().first()
                assertEquals(ForestTheme.SURFACE, pin.background)
                val accountTable = components(modal).filterIsInstance<JTable>().first()
                assertEquals(ForestTheme.SURFACE, accountTable.background)
                modal.dispose()
            }
            await("hardware dialog dismissed") { dialog("Security key") == null }
        } finally { close(fixture) }
    }
}
