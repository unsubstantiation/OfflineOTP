package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.JScrollBar
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel

/** Real Swing lightweight components and their accessibility/privacy behavior, even headlessly. */
class ForestThemeTest {
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait { block() }

    private fun otp(issuer: String, label: String, shown: Boolean): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf()).apply {
            setDetailsVisible(shown)
            updateOTPAt(59L, true)
        }

    private fun model(vararg entries: Entry): EntryTableModel {
        val list = EntryList().apply { entries.forEach { addEntry(it) } }
        return EntryTableModel(EntryListPresentation(list).apply { showReorderedEntries() }) { entry, shown ->
            entry.setDetailsVisible(shown)
        }
    }

    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap { components(it) } else emptyList()

    private fun labels(component: Component): List<JLabel> = components(component).filterIsInstance<JLabel>()

    private fun card(table: JTable, row: Int): Component = table.getCellRenderer(row, 0)
        .getTableCellRendererComponent(table, table.getValueAt(row, 0), false, false, row, 0)

    @Test
    fun sharedThemeCoversControlsMenusPopupsAndDialogs() = onEdt {
        ForestTheme.install()
        assertTrue(UIManager.getLookAndFeel() is MetalLookAndFeel)
        assertEquals(Color(0x10, 0x1A, 0x14), ForestTheme.BACKGROUND)
        assertEquals(Color(0x21, 0x37, 0x2A), ForestTheme.SURFACE_ELEVATED)
        assertEquals(Color(0x81, 0xC7, 0x84), ForestTheme.ACCENT)
        assertEquals(ForestTheme.TEXT_PRIMARY, JLabel("Label").foreground)
        assertEquals(ForestTheme.SURFACE, JTextField().background)
        assertEquals(ForestTheme.SURFACE, JTextArea().background)
        assertTrue(JButton("Button").ui is ForestButtonUI)
        assertTrue(JComboBox(arrayOf("One", "Two")).ui is ForestComboBoxUI)
        assertTrue(JScrollBar().ui is ForestScrollBarUI)
        assertEquals(ForestTheme.SURFACE_ELEVATED, JPopupMenu().background)
        assertEquals(ForestTheme.TEXT_PRIMARY, JMenuItem("Item").foreground)
        assertEquals(ForestTheme.SURFACE_ELEVATED, JOptionPane("Message").background)
        assertEquals(ForestTheme.TEXT_PRIMARY, UIManager.getColor("OptionPane.messageForeground"))
        assertEquals(ForestTheme.SURFACE_ELEVATED, UIManager.getColor("FileChooser.background"))
        assertTrue(components(JFileChooser()).filterIsInstance<javax.swing.JPanel>()
            .all { it.background == ForestTheme.SURFACE_ELEVATED })
        assertNotNull(UIManager.getIcon("CheckBox.icon"))
        assertNotNull(UIManager.getIcon("RadioButton.icon"))
    }

    @Test
    fun fileChooserAndAlertIconsAreVisibleOnDarkSurfaces() = onEdt {
        ForestTheme.install()
        for (key in listOf("FileChooser.upFolderIcon", "FileChooser.homeFolderIcon",
            "FileChooser.newFolderIcon", "FileChooser.listViewIcon", "FileChooser.detailsViewIcon",
            "FileView.directoryIcon", "OptionPane.warningIcon", "OptionPane.informationIcon")) {
            val icon = UIManager.getIcon(key)
            assertNotNull(key, icon)
            val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { icon.paintIcon(JButton(), graphics, 0, 0) } finally { graphics.dispose() }
            val visiblePixels = (0 until image.width).flatMap { x ->
                (0 until image.height).map { y -> Color(image.getRGB(x, y), true) }
            }
            assertTrue(key, visiblePixels.any { it.alpha > 128 && it.red > 100 && it.green > 100 })
        }
    }

    @Test
    fun textAndControlsRemainReadableOnTheForestSurfaces() {
        fun contrast(a: Color, b: Color): Double {
            fun luminance(color: Color): Double {
                fun channel(value: Int): Double {
                    val v = value / 255.0
                    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
            }
            val x = luminance(a); val y = luminance(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
        for (surface in listOf(ForestTheme.SURFACE, ForestTheme.SURFACE_ELEVATED, ForestTheme.SURFACE_VARIANT)) {
            assertTrue("primary text on $surface", contrast(ForestTheme.TEXT_PRIMARY, surface) >= 4.5)
            assertTrue("secondary text on $surface", contrast(ForestTheme.TEXT_SECONDARY, surface) >= 4.5)
            assertTrue("disabled/hint text on $surface", contrast(ForestTheme.TEXT_HINT, surface) >= 4.5)
            assertTrue("warning text on $surface", contrast(ForestTheme.WARNING, surface) >= 4.5)
        }
        assertTrue(contrast(ForestTheme.ON_PRIMARY, ForestTheme.ACCENT) >= 4.5)
        assertTrue(contrast(ForestTheme.TEXT_PRIMARY, ForestTheme.APP_BAR) >= 4.5)
    }

    @Test
    fun cardsExposeBothPartsOfTheIdentityButNotHiddenContents() = onEdt {
        ForestTheme.install()
        val hidden = otp("Hidden issuer", "Visible title", false)
        val visible = otp("Shown issuer", "account@example.com", true)
        val note = Entry(Entry.OTPType.NOTE, "private note content", "Notes issuer", "Recovery", arrayListOf())
        val key = Entry.sshKey("sensitive public key", "sensitive private key", "Server issuer", "Host", arrayListOf())
        val model = model(hidden, visible, note, key)
        val table = JTable(model)
        EntryCards.configure(table)

        val hiddenCard = card(table, 0)
        val hiddenText = labels(hiddenCard).map { it.text }
        assertTrue(hiddenText.contains("Hidden"))
        assertTrue(hiddenText.contains("Hidden issuer – Visible title"))
        assertFalse(hiddenText.any { it.contains(hidden.getCurrentOTP().orEmpty()) || it.contains("TOTP") })
        assertEquals("Hidden, Hidden issuer – Visible title", hiddenCard.accessibleContext.accessibleName)
        assertFalse(hiddenCard.accessibleContext.accessibleName.contains(hidden.getCurrentOTP().orEmpty()))
        val shownText = labels(card(table, 1)).map { it.text }
        assertTrue(shownText.contains(visible.getCurrentOTP()))
        assertTrue(shownText.contains("Shown issuer – account@example.com"))

        for ((row, identity) in listOf(2 to "Notes issuer – Recovery", 3 to "Server issuer – Host")) {
            val rendered = card(table, row)
            val text = labels(rendered).map { it.text }
            assertTrue(text.contains("Hidden"))
            assertTrue(text.contains(identity))
            assertEquals("Hidden, $identity", rendered.accessibleContext.accessibleName)
            assertFalse(text.any { it.contains("private") || it.contains("sensitive") ||
                it.contains("NOTE") || it.contains("SSH") })
        }
        model.setValueAt(true, 2, EntryTableModel.Column.SHOW.ordinal)
        val noteText = labels(card(table, 2)).map { it.text }
        assertTrue(noteText.contains("[Note hidden]"))
        assertFalse(noteText.any { it.contains("private note content") })
        model.setValueAt(true, 3, EntryTableModel.Column.SHOW.ordinal)
        val keyText = labels(card(table, 3)).map { it.text }
        assertTrue(keyText.contains("[SSH keys hidden]"))
        assertFalse(keyText.any { it.contains("sensitive") })
    }

    @Test
    fun entryCardsOnlyShowTagsForRevealedEntries() = onEdt {
        ForestTheme.install()
        val entry = otp("Issuer", "Account", false).apply { setTags(arrayListOf("private, tag")) }
        val table = JTable(model(entry))
        EntryCards.configure(table)
        assertFalse(labels(card(table, 0)).any { it.text.contains("private, tag") })
        (table.model as EntryTableModel).setValueAt(true, 0, EntryTableModel.Column.SHOW.ordinal)
        assertTrue(labels(card(table, 0)).any { it.text.contains("private, tag") })
    }

    @Test
    fun hiddenCardsWithMissingIdentityPartsDoNotDuplicateTheIssuerOrAddSeparators() = onEdt {
        ForestTheme.install()
        val table = JTable(model(otp("Issuer only", "", false), otp("", "Label only", false),
            otp("", "", false)))
        EntryCards.configure(table)
        for ((row, identity) in listOf(0 to "Issuer only", 1 to "Label only", 2 to "Unnamed entry")) {
            val rendered = card(table, row)
            assertEquals(1, labels(rendered).count { it.text == identity })
            assertEquals("Hidden, $identity", rendered.accessibleContext.accessibleName)
        }
    }

    @Test
    fun hiddenIssuersAndLabelsArePlainTextNotSwingHtml() = onEdt {
        ForestTheme.install()
        val payload = "<html><img src='https://example.invalid/leak'>"
        val table = JTable(model(otp(payload, "account", false), otp("", payload, false)))
        EntryCards.configure(table)
        for (row in 0..1) {
            val rendered = card(table, row)
            val text = labels(rendered)
            assertTrue(text.any { it.text.startsWith(payload) })
            assertTrue(rendered.accessibleContext.accessibleName.contains(payload))
            text.forEach {
                assertEquals(true, it.getClientProperty("html.disable"))
                assertEquals(null, it.getClientProperty("html"))
            }
        }
    }

    @Test
    fun checkboxAndSpaceToggleTheRealModelAndTimerRefreshKeepsSelection() = onEdt {
        ForestTheme.install()
        val entry = otp("Issuer", "Account", false)
        val model = model(entry)
        val table = JTable(model)
        EntryCards.configure(table)
        val screen = MainScreen(table, JButton("Copy"))
        screen.setUnlocked(true)
        table.setRowSelectionInterval(0, 0)
        assertFalse(screen.copyButton.isEnabled)
        assertFalse(screen.tagsButton.isEnabled)
        assertTrue(screen.deleteButton.isEnabled)
        assertEquals("Hidden, Issuer – Account", card(table, 0).accessibleContext.accessibleName)
        assertEquals("Hidden, Issuer – Account",
            table.accessibleContext.accessibleTable.getAccessibleAt(0, 0).accessibleContext.accessibleName)

        val toggle = table.actionMap.get("toggleEntryVisibility")
        assertNotNull(toggle)
        toggle.actionPerformed(ActionEvent(table, ActionEvent.ACTION_PERFORMED, "toggle"))
        assertTrue(entry.areDetailsVisible())
        assertTrue(screen.copyButton.isEnabled)
        assertTrue(screen.tagsButton.isEnabled)
        assertTrue(card(table, 0).accessibleContext.accessibleName.contains("Issuer – Account"))
        assertTrue(card(table, 0).accessibleContext.accessibleName.contains(entry.getCurrentOTP().orEmpty()))
        model.refreshValues()
        assertEquals(0, table.selectedRow)

        assertTrue(table.editCellAt(0, 1))
        val checkbox = table.editorComponent as JCheckBox
        assertEquals("Show entry details", checkbox.accessibleContext.accessibleName)
        checkbox.doClick()
        assertFalse(entry.areDetailsVisible())
        assertFalse(screen.copyButton.isEnabled)
        assertEquals("Hidden, Issuer – Account", card(table, 0).accessibleContext.accessibleName)
        assertEquals("Hidden, Issuer – Account",
            table.accessibleContext.accessibleTable.getAccessibleAt(0, 0).accessibleContext.accessibleName)
        assertFalse(card(table, 0).accessibleContext.accessibleName.contains(entry.getCurrentOTP().orEmpty()))
        assertEquals(0, table.selectedRow)
    }

    @Test
    fun replacingTheModelPreservesCardColumnsAndEmptySearchStates() = onEdt {
        ForestTheme.install()
        val table = JTable(model())
        EntryCards.configure(table)
        val screen = MainScreen(table, JButton("Copy"))
        assertFalse(screen.addButton.isEnabled)
        assertFalse(screen.search.isEnabled)
        assertTrue(labels(screen.panel).any { it.text == "Your vault is locked" })
        screen.setUnlocked(true)
        assertTrue(screen.addButton.isEnabled)
        assertTrue(labels(screen.panel).any { it.text == "No entries yet" })
        screen.search.text = "missing"
        assertTrue(labels(screen.panel).any { it.text == "No matching entries" })
        screen.search.text = " " // the underlying search treats whitespace as a real query
        assertTrue(labels(screen.panel).any { it.text == "No matching entries" })
        table.model = model(otp("Issuer", "Label", true))
        assertEquals(2, table.columnCount)
        assertEquals(EntryTableModel.Column.LABEL.ordinal, table.convertColumnIndexToModel(0))
        assertEquals(EntryTableModel.Column.SHOW.ordinal, table.convertColumnIndexToModel(1))
        assertEquals(1, table.rowCount)
        assertTrue(labels(card(table, 0)).any { it.text.contains("Issuer") })
    }
}
