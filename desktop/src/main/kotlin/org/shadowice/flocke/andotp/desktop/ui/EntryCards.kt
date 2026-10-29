package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.TableCellRenderer

/**
 * Presents the existing privacy-aware table model as Android-inspired cards. The real Show cell
 * remains a Swing checkbox/editor rather than a painted hit target, so mouse, keyboard and assistive
 * technology can operate it. Other columns remain in the model for actions and accessibility; the
 * card renderer reads only their already-redacted display values, never secrets from Entry itself.
 */
internal object EntryCards {
    fun configure(table: JTable) {
        require(table.model is EntryTableModel)
        val columns = table.columnModel
        val label = columns.getColumn(EntryTableModel.Column.LABEL.ordinal)
        val show = columns.getColumn(EntryTableModel.Column.SHOW.ordinal)
        while (columns.columnCount > 0) columns.removeColumn(columns.getColumn(0))
        columns.addColumn(label)
        columns.addColumn(show)
        // Replacing the model when a vault opens must not recreate the five default view columns.
        table.autoCreateColumnsFromModel = false
        label.cellRenderer = CardRenderer()
        show.cellRenderer = ShowRenderer()
        show.cellEditor = ShowEditor()
        show.minWidth = 90
        show.preferredWidth = 100
        show.maxWidth = 110
        show.resizable = false
        table.tableHeader = null
        table.rowHeight = 104
        table.intercellSpacing = Dimension(0, 8)
        table.setShowGrid(false)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowSelectionAllowed = true
        table.columnSelectionAllowed = false
        table.fillsViewportHeight = true
        table.background = ForestTheme.BACKGROUND
        table.foreground = ForestTheme.TEXT_PRIMARY
        table.selectionBackground = ForestTheme.SURFACE_VARIANT
        table.selectionForeground = ForestTheme.TEXT_PRIMARY
        table.accessibleContext.accessibleName = "Vault entries"
        table.accessibleContext.accessibleDescription =
            "Select an entry. Press Space to show or hide its details, or double-click a shown entry to copy."
        table.toolTipText = "Select an entry; press Space to show or hide details"

        // A row can be selected with either visible column focused; Space always reaches Show.
        val action = "toggleEntryVisibility"
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), action)
        table.actionMap.put(action, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (table.isEditing) return
                val viewRow = table.selectedRow
                if (viewRow < 0) return
                val row = table.convertRowIndexToModel(viewRow)
                val model = table.model as EntryTableModel
                val column = EntryTableModel.Column.SHOW.ordinal
                model.setValueAt(model.getValueAt(row, column) != true, row, column)
            }
        })
    }
}

/** Left section of a card, reused by JTable's renderer for all rows. */
private class CardRenderer : JPanel(BorderLayout(14, 0)), TableCellRenderer {
    private val avatar = CardAvatar()
    private val value = JLabel()
    private val title = JLabel()
    private val detail = JLabel()
    private var selected = false

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(10, 12, 10, 4)
        avatar.preferredSize = Dimension(56, 56)
        add(avatar, BorderLayout.WEST)
        val labels = JPanel(GridLayout(3, 1, 0, 0)).apply {
            isOpaque = false
            add(value); add(title); add(detail)
        }
        add(labels, BorderLayout.CENTER)
        // Account labels are untrusted input; never let a leading <html> enable Swing HTML/URLs.
        listOf(value, title, detail).forEach { it.putClientProperty("html.disable", true) }
        value.font = Font(Font.SANS_SERIF, Font.BOLD, 21)
        title.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        detail.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

    override fun getTableCellRendererComponent(
        table: JTable, unused: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        selected = isSelected
        val model = table.model as EntryTableModel
        val modelRow = table.convertRowIndexToModel(row)
        fun text(col: EntryTableModel.Column): String = model.getValueAt(modelRow, col.ordinal) as String
        val shown = model.getValueAt(modelRow, EntryTableModel.Column.SHOW.ordinal) == true
        val issuer = text(EntryTableModel.Column.ISSUER)
        val label = text(EntryTableModel.Column.LABEL)
        val type = text(EntryTableModel.Column.TYPE)
        val token = text(EntryTableModel.Column.TOKEN)

        value.text = if (shown) token.ifBlank { "Waiting for code…" } else "Hidden"
        value.font = Font(Font.SANS_SERIF, Font.BOLD, if (shown && token.startsWith("[")) 15 else 21)
        value.foreground = if (shown) ForestTheme.TEXT_PRIMARY else ForestTheme.TEXT_HINT
        title.text = listOf(issuer, label).filter { it.isNotBlank() }.joinToString(" – ")
            .ifBlank { "Unnamed entry" }
        title.foreground = ForestTheme.TEXT_PRIMARY
        detail.text = if (shown) when (type) {
            "SSH_KEY" -> "SSH key"
            "NOTE" -> "Secure note"
            else -> type
        } else ""
        detail.foreground = ForestTheme.TEXT_SECONDARY
        avatar.initial = initial(if (shown) issuer.ifBlank { label } else label.ifBlank { issuer })
        getAccessibleContext().accessibleName = listOf(value.text, title.text, detail.text)
            .filter { it.isNotBlank() }.joinToString(", ")
        return this
    }

    override fun paintComponent(graphics: Graphics) {
        paintCardHalf(graphics, width, height, selected, left = true)
        super.paintComponent(graphics)
    }

    private fun initial(text: String): String = text.trim().takeIf { it.isNotEmpty() }
        ?.let { String(Character.toChars(it.codePointAt(0))).uppercase(Locale.ROOT) } ?: "•"
}

private class CardAvatar : JComponent() {
    var initial: String = "•"

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = minOf(width, height, 56)
            val x = (width - size) / 2
            val y = (height - size) / 2
            g.color = ForestTheme.THUMBNAIL
            g.fillRoundRect(x, y, size, size, 8, 8)
            g.color = ForestTheme.APP_BAR
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
            val metrics = g.fontMetrics
            g.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2,
                y + (size - metrics.height) / 2 + metrics.ascent)
        } finally { g.dispose() }
    }
}

/** Right card section is a genuine checkbox for both rendering and editing. */
private class CardCheckBox : JCheckBox("Show") {
    var rowSelected: Boolean = false

    init {
        isOpaque = false
        horizontalAlignment = SwingConstants.CENTER
        foreground = ForestTheme.TEXT_PRIMARY
        getAccessibleContext().accessibleName = "Show entry details"
    }

    override fun paintComponent(graphics: Graphics) {
        paintCardHalf(graphics, width, height, rowSelected, left = false)
        super.paintComponent(graphics)
    }
}

private class ShowRenderer : TableCellRenderer {
    private val checkBox = CardCheckBox().apply { isFocusPainted = false }
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component = checkBox.apply {
        this.isSelected = value == true
        rowSelected = isSelected
        isEnabled = table.isEnabled
    }
}

private class ShowEditor : DefaultCellEditor(CardCheckBox()) {
    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
    ): Component = (super.getTableCellEditorComponent(table, value, isSelected, row, column) as CardCheckBox)
        .apply { rowSelected = isSelected }
}

/** Draw each half with only its outer corners rounded, forming one uninterrupted card. */
private fun paintCardHalf(graphics: Graphics, width: Int, height: Int, selected: Boolean, left: Boolean) {
    val g = graphics.create() as Graphics2D
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (selected) ForestTheme.SURFACE_VARIANT else ForestTheme.SURFACE
        val radius = 8
        if (left) g.fillRoundRect(0, 0, width + radius, height, radius, radius)
        else g.fillRoundRect(-radius, 0, width + radius, height, radius, radius)
        if (selected && left) {
            g.color = ForestTheme.ACCENT
            g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(2, 12, 2, (height - 12).coerceAtLeast(12))
        }
    } finally { g.dispose() }
}
