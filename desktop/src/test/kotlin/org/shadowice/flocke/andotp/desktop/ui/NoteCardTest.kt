package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities

/** A revealed secure note must be readable in full on the card, however long it is. */
class NoteCardTest {
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait { block() }

    private fun note(text: String, shown: Boolean): Entry =
        Entry(Entry.OTPType.NOTE, text, "Notes issuer", "Recovery", arrayListOf()).apply {
            setDetailsVisible(shown)
        }

    private fun table(vararg entries: Entry): JTable {
        val list = EntryList().apply { entries.forEach { addEntry(it) } }
        val model = EntryTableModel(EntryListPresentation(list).apply { showReorderedEntries() }) { entry, shown ->
            entry.setDetailsVisible(shown)
        }
        return JTable(model).also {
            EntryCards.configure(it)
            // A real window gives the label column the viewport width; mirror that here.
            it.columnModel.getColumn(0).width = 640
        }
    }

    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap { components(it) } else emptyList()

    private fun notes(component: Component): List<NoteView> =
        components(component).filterIsInstance<NoteView>()

    private fun card(table: JTable, row: Int): Component = table.getCellRenderer(row, 0)
        .getTableCellRendererComponent(table, table.getValueAt(row, 0), false, false, row, 0)

    private fun labels(component: Component): List<JLabel> = components(component).filterIsInstance<JLabel>()

    private fun noteWidth(table: JTable): Int =
        table.columnModel.getColumn(0).width - CardMetrics.TEXT_INSET

    // 186 characters: a long note that still respects the vault's 200-character limit.
    private val longNote = (1..28).joinToString(" ") { "word$it" }

    @Test
    fun wrappingKeepsEveryWordOfTheNote() {
        val table = table(note("placeholder", true))
        val metrics = table.getFontMetrics(CardFonts.NOTE)
        val lines = NoteText.wrap(longNote, 120, metrics)
        assertTrue("the text wraps at a narrow width", lines.size > 3)
        assertEquals(longNote, lines.joinToString(" "))
        for (paragraph in listOf("first line\nsecond line", "one\ntwo\nthree")) {
            val wrapped = NoteText.wrap(paragraph, 400, metrics)
            assertEquals(paragraph, wrapped.joinToString("\n"))
        }
        // A single word wider than the column is split rather than dropped.
        val split = NoteText.wrap("supercalifragilisticexpialidocious", 40, metrics)
        assertEquals("supercalifragilisticexpialidocious", split.joinToString(""))
        assertTrue(split.size > 1)
        assertEquals(listOf(""), NoteText.wrap("", 120, metrics))
    }

    @Test
    fun aRevealedNoteGetsARowTallEnoughForEveryLine() = onEdt {
        ForestTheme.install()
        val entry = note(longNote, true)
        val table = table(entry)
        val metrics = table.getFontMetrics(CardFonts.NOTE)
        val needed = NoteText.height(longNote, noteWidth(table), metrics)
        assertTrue("a long note must wrap", NoteText.wrap(longNote, noteWidth(table), metrics).size > 2)
        assertTrue("row grew for the note", table.getRowHeight(0) > CardMetrics.MIN_HEIGHT)
        assertTrue("row fits every wrapped line",
            table.getRowHeight(0) >= needed + CardMetrics.VERTICAL_PADDING)
        assertTrue(labels(card(table, 0)).none { it.text == "Hidden" })
    }

    @Test
    fun theRendererPaintsTheWholeNoteInsideTheRow() = onEdt {
        ForestTheme.install()
        val entry = note(longNote, true)
        val table = table(entry)
        val card = card(table, 0)
        val view = notes(card).single()
        assertEquals(longNote, view.text)

        val metrics = table.getFontMetrics(CardFonts.NOTE)
        val lines = NoteText.wrap(longNote, noteWidth(table), metrics)
        view.setSize(noteWidth(table), lines.size * metrics.height)
        val image = BufferedImage(maxOf(view.width, 1), maxOf(view.height, 1), BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { view.paint(graphics) } finally { graphics.dispose() }
        fun paintedIn(top: Int, bottom: Int): Boolean =
            (top until bottom).any { y -> (0 until image.width).any { x -> (image.getRGB(x, y) ushr 24) != 0 } }
        assertTrue("first line painted", paintedIn(0, metrics.height))
        assertTrue("last line painted, not clipped",
            paintedIn((lines.size - 1) * metrics.height, lines.size * metrics.height))
    }

    @Test
    fun hidingTheNoteCollapsesTheRowAndRemovesTheContent() = onEdt {
        ForestTheme.install()
        val entry = note(longNote, true)
        val table = table(entry)
        val model = table.model as EntryTableModel
        assertTrue(table.getRowHeight(0) > CardMetrics.MIN_HEIGHT)

        model.setValueAt(false, 0, EntryTableModel.Column.SHOW.ordinal)
        assertEquals(CardMetrics.MIN_HEIGHT, table.getRowHeight(0))
        val hiddenCard = card(table, 0)
        assertTrue(labels(hiddenCard).any { it.text == "Hidden" })
        assertEquals("", notes(hiddenCard).single().text)
        assertFalse(hiddenCard.accessibleContext.accessibleName.contains("word1"))

        model.setValueAt(true, 0, EntryTableModel.Column.SHOW.ordinal)
        assertTrue(table.getRowHeight(0) > CardMetrics.MIN_HEIGHT)
        assertEquals(longNote, notes(card(table, 0)).single().text)
        assertTrue(card(table, 0).accessibleContext.accessibleName.contains(longNote))
    }

    @Test
    fun otherKindsKeepTheirPlaceholdersAndTheStandardRow() = onEdt {
        ForestTheme.install()
        val key = Entry.sshKey("public key", "private key", "Servers", "Host", arrayListOf()).apply {
            setDetailsVisible(true)
        }
        val table = table(key)
        assertEquals(CardMetrics.MIN_HEIGHT, table.getRowHeight(0))
        assertEquals("", notes(card(table, 0)).single().text)
        assertTrue(labels(card(table, 0)).any { it.text == "[SSH keys hidden]" })
        key.destroy()
    }
}
