package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.CardLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.WeakHashMap
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
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.event.TableModelListener
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel

/**
 * Presents the existing privacy-aware table model as Android-inspired cards. The real Show cell
 * remains a Swing checkbox/editor rather than a painted hit target, so mouse, keyboard and assistive
 * technology can operate it. Other columns remain in the model for actions and accessibility; the
 * card renderer reads only redacted model values and, for revealed entries, their tags.
 *
 * The cards prefer the same bundled issuer artwork the Android app shows (see [IssuerIcons]) and
 * fall back to a generated letter tile, so Debian and Windows match the phone. Their face lifts on
 * rollover and a per-repaint ring counts down the current token step, which makes the one-second
 * token refresh visible without adding another timer.
 *
 * A revealed secure note is shown in full: the row grows to fit every wrapped line ([CardRowHeights])
 * instead of collapsing the content into a placeholder. Every other protected kind stays redacted on
 * the face and is reached through its action.
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

        CardHover.track(table)
        CardRowHeights.track(table)
    }
}

/** Which view row the pointer is over, so a card can lift without an animation timer. */
private object CardHover {
    private val rows = WeakHashMap<JTable, Int>()

    fun rowOf(table: JTable): Int = rows[table] ?: -1

    fun track(table: JTable) {
        fun update(row: Int) {
            val previous = rows[table] ?: -1
            if (previous == row) return
            rows[table] = row
            // Repaint only the two cards whose face changed, not the whole visible list.
            for (changed in intArrayOf(previous, row)) {
                if (changed in 0 until table.rowCount) table.repaint(table.getCellRect(changed, 0, true))
            }
        }
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) = update(table.rowAtPoint(event.point))
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) = update(-1)
        })
    }
}

/** Seconds before the end of a step at which Android (and these cards) warn about expiry. */
private const val EXPIRING_SECONDS = 8L

/**
 * Clock seam for the countdown ring and expiry color. Painting must never mutate the entries (the
 * Android adapter updates them from its tick instead), so the remaining seconds are derived from
 * the wall clock here; tests pin the time rather than waiting for a real boundary.
 */
internal object CardClock {
    var nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
}

/** Fonts shared by the renderer and the row-height pass, so card and measurement cannot diverge. */
internal object CardFonts {
    val TITLE = Font(Font.SANS_SERIF, Font.PLAIN, 14)
    val DETAIL = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    val HIDDEN = Font(Font.SANS_SERIF, Font.BOLD, 21)
    val PLACEHOLDER = Font(Font.SANS_SERIF, Font.BOLD, 15)
    val TOKEN = Font(Font.MONOSPACED, Font.BOLD, 22)
    val NOTE = Font(Font.SANS_SERIF, Font.PLAIN, 13)
}

/** Card geometry shared by the renderer, the avatar and the row-height pass. */
internal object CardMetrics {
    const val MIN_HEIGHT = 104
    /** Renderer top/bottom insets (10 + 10). */
    const val VERTICAL_PADDING = 20
    /** Left/right insets plus the avatar column and its gap: the space left for the text column. */
    const val TEXT_INSET = 86
    /** Breathing room between the note text and the identity lines below it. */
    const val STACK_GAP = 6
}

/**
 * Line breaking for a revealed note. The same function measures the row and paints the card, so a
 * note can never be clipped by a mismatch between the two. Explicit newlines are kept, words break
 * at spaces, and a single word longer than the column is split so nothing disappears.
 */
internal object NoteText {
    fun wrap(text: String, width: Int, metrics: FontMetrics): List<String> {
        val available = maxOf(width, 1)
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { lines.add(""); continue }
            var start = 0
            while (start < paragraph.length) {
                var end = start
                var lastBreak = -1
                while (end < paragraph.length &&
                    metrics.stringWidth(paragraph.substring(start, end + 1)) <= available) {
                    if (paragraph[end] == ' ') lastBreak = end
                    end++
                }
                if (end >= paragraph.length) { lines.add(paragraph.substring(start)); break }
                val cut = if (lastBreak > start) lastBreak else end.coerceAtLeast(start + 1)
                lines.add(paragraph.substring(start, cut))
                start = if (lastBreak > start) cut + 1 else cut
            }
        }
        return lines.ifEmpty { listOf("") }
    }

    /** The exact lines [text] occupies at [width]; painting and measuring both go through it. */
    fun lines(text: String, width: Int, metrics: FontMetrics): List<String> = wrap(text, width, metrics)

    /** Painted height of [text] at [width]; one line minimum, matching [lines]. */
    fun height(text: String, width: Int, metrics: FontMetrics): Int =
        lines(text, width, metrics).size * metrics.height
}

/** Paints a whole note as wrapped, amber-free body text; no HTML, no selection, no scrolling. */
internal class NoteView : JComponent() {
    var text: String = ""

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = CardFonts.NOTE
            g.color = ForestTheme.TEXT_PRIMARY
            val metrics = g.getFontMetrics(CardFonts.NOTE)
            for ((index, line) in NoteText.lines(text, width, metrics).withIndex()) {
                g.drawString(line, 0, index * metrics.height + metrics.ascent)
            }
        } finally {
            g.dispose()
        }
    }
}

/**
 * Per-row heights: a revealed note needs as many lines as its content wraps to, everything else
 * keeps the standard card height. Heights are recomputed when the model changes, a vault is
 * swapped in, the window resizes, or the note is hidden again.
 */
internal object CardRowHeights {
    fun track(table: JTable) {
        val listener = TableModelListener { refresh(table) }
        table.model.addTableModelListener(listener)
        table.addPropertyChangeListener("model") { event ->
            (event.oldValue as? TableModel)?.removeTableModelListener(listener)
            (event.newValue as? TableModel)?.addTableModelListener(listener)
            refresh(table)
        }
        table.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = refresh(table)
        })
        // Auto-resize moves the column edge when the window or the panel changes.
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnAdded(event: TableColumnModelEvent) = refresh(table)
            override fun columnRemoved(event: TableColumnModelEvent) = refresh(table)
            override fun columnMoved(event: TableColumnModelEvent) = refresh(table)
            override fun columnMarginChanged(event: ChangeEvent) = refresh(table)
            override fun columnSelectionChanged(event: ListSelectionEvent) = Unit
        })
        refresh(table)
    }

    fun refresh(table: JTable) {
        for (row in 0 until table.rowCount) {
            val height = heightFor(table, row)
            if (table.getRowHeight(row) != height) table.setRowHeight(row, height)
        }
    }

    /** Height for a view row: the standard card, or the note's wrapped text plus its identity. */
    fun heightFor(table: JTable, row: Int): Int {
        val model = table.model as? EntryTableModel ?: return CardMetrics.MIN_HEIGHT
        val modelRow = table.convertRowIndexToModel(row)
        if (modelRow !in 0 until model.rowCount) return CardMetrics.MIN_HEIGHT
        val entry = model.entryAt(modelRow)
        if (!entry.isNote() || !entry.areDetailsVisible()) return CardMetrics.MIN_HEIGHT
        val width = (table.columnModel.getColumn(0).width - CardMetrics.TEXT_INSET).coerceAtLeast(40)
        val noteHeight = NoteText.height(entry.getNote(), width, table.getFontMetrics(CardFonts.NOTE))
        val identity = 2 * maxOf(table.getFontMetrics(CardFonts.TITLE).height,
            table.getFontMetrics(CardFonts.DETAIL).height)
        return maxOf(CardMetrics.MIN_HEIGHT,
            CardMetrics.VERTICAL_PADDING + noteHeight + CardMetrics.STACK_GAP + identity)
    }
}

/** Left section of a card, reused by JTable's renderer for all rows. */
private class CardRenderer : JPanel(BorderLayout(14, 0)), TableCellRenderer {
    private val avatar = CardAvatar()
    private val value = JLabel()
    private val note = NoteView()
    private val title = JLabel()
    private val detail = JLabel()
    private val valueSlot = JPanel(CardLayout()).apply {
        isOpaque = false
        add(value, VALUE)
        add(note, NOTE)
    }
    private var selected = false
    private var hovered = false

    private companion object {
        const val VALUE = "value"
        const val NOTE = "note"
    }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(10, 12, 10, 4)
        avatar.preferredSize = Dimension(56, 56)
        add(avatar, BorderLayout.WEST)
        val identity = JPanel(GridLayout(2, 1, 0, 0)).apply {
            isOpaque = false
            add(title); add(detail)
        }
        // The identity keeps its natural height so a tall note gets exactly the room it measured.
        val identityHolder = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(identity, BorderLayout.NORTH)
        }
        val labels = JPanel(BorderLayout(0, CardMetrics.STACK_GAP)).apply {
            isOpaque = false
            add(valueSlot, BorderLayout.CENTER)
            add(identityHolder, BorderLayout.SOUTH)
        }
        add(labels, BorderLayout.CENTER)
        // Account labels are untrusted input; never let a leading <html> enable Swing HTML/URLs.
        // The note is painted line by line, so it has no markup path at all.
        listOf(value, title, detail).forEach { it.putClientProperty("html.disable", true) }
        title.font = CardFonts.TITLE
        detail.font = CardFonts.DETAIL
    }

    override fun getTableCellRendererComponent(
        table: JTable, unused: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        selected = isSelected
        hovered = CardHover.rowOf(table) == row
        val model = table.model as EntryTableModel
        val modelRow = table.convertRowIndexToModel(row)
        fun text(col: EntryTableModel.Column): String = model.getValueAt(modelRow, col.ordinal) as String
        val shown = model.getValueAt(modelRow, EntryTableModel.Column.SHOW.ordinal) == true
        val entry = model.entryAt(modelRow)
        val issuer = text(EntryTableModel.Column.ISSUER)
        val label = text(EntryTableModel.Column.LABEL)
        val type = text(EntryTableModel.Column.TYPE)
        val token = text(EntryTableModel.Column.TOKEN)
        val noteShown = shown && entry.isNote()
        val placeholder = shown && !noteShown && token.trimStart().startsWith("[")
        val secondsLeft = if (shown && !noteShown && !placeholder) secondsRemaining(entry) else null
        val expiring = secondsLeft != null && secondsLeft <= EXPIRING_SECONDS

        (valueSlot.layout as CardLayout).show(valueSlot, if (noteShown) NOTE else VALUE)
        note.text = if (noteShown) token else ""
        value.text = when {
            !shown -> "Hidden"
            noteShown -> ""
            else -> token.ifBlank { "Waiting for code…" }
        }
        value.font = when {
            // Digits use a monospaced face so a one-second refresh never reflows the row.
            shown && !placeholder -> CardFonts.TOKEN
            shown -> CardFonts.PLACEHOLDER
            else -> CardFonts.HIDDEN
        }
        value.foreground = when {
            !shown -> ForestTheme.TEXT_HINT
            placeholder -> ForestTheme.TEXT_SECONDARY
            expiring -> ForestTheme.WARNING
            else -> ForestTheme.OTP_AMBER
        }
        title.text = listOf(issuer, label).filter { it.isNotBlank() }.joinToString(" – ")
            .ifBlank { "Unnamed entry" }
        title.foreground = ForestTheme.TEXT_PRIMARY
        detail.text = if (shown) {
            val kind = when (type) {
                "PASSWORD" -> "Password"
                "PASSWORD_ARCHIVE" -> "Password file"
                "SSH_KEY" -> "SSH key"
                "NOTE" -> "Secure note"
                else -> type
            }
            val tags = entry.getTags()
            if (tags.isEmpty()) kind else "$kind · ${tags.joinToString(", ")}"
        } else ""
        detail.foreground = ForestTheme.TEXT_SECONDARY

        // The stored thumbnail name is what the Android app shows for this entry; unknown names
        // (and "Default") keep the generated letter tile.
        avatar.artwork = IssuerIcons.artwork(entry.getThumbnailName())
        avatar.initial = initial(if (shown) issuer.ifBlank { label } else label.ifBlank { issuer })
        avatar.remaining = secondsLeft?.let { (it.toFloat() / stepSeconds(entry)).coerceIn(0f, 1f) } ?: -1f
        avatar.expiring = expiring
        getAccessibleContext().accessibleName =
            listOf(if (noteShown) token else value.text, title.text, detail.text)
                .filter { it.isNotBlank() }.joinToString(", ")
        return this
    }

    override fun paintComponent(graphics: Graphics) {
        paintCardHalf(graphics, width, height, selected, hovered, left = true)
        super.paintComponent(graphics)
    }

    private fun initial(text: String): String = text.trim().takeIf { it.isNotEmpty() }
        ?.let { String(Character.toChars(it.codePointAt(0))).uppercase(Locale.ROOT) } ?: "•"

    private fun stepSeconds(entry: Entry): Float = entry.getTokenStepSeconds().coerceAtLeast(1).toFloat()

    /** Seconds left in the current step, or null for entries that do not expire on a clock. */
    private fun secondsRemaining(entry: Entry): Long? {
        if (!entry.isTimeBased()) return null
        val step = entry.getTokenStepSeconds().coerceAtLeast(1).toLong()
        return step - CardClock.nowSeconds() % step
    }
}

/**
 * Issuer mark: the bundled brand artwork on the light tile Android uses, or the generated letter
 * tile when no artwork exists. A thin ring shows how much of the current step is left and turns
 * amber-to-red inside the last seconds before the code changes.
 */
private class CardAvatar : JComponent() {
    var initial: String = "•"
    var artwork: BufferedImage? = null
    var remaining: Float = -1f
    var expiring: Boolean = false

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val outer = minOf(width, height, 56)
            val tile = outer - 8
            val x = (width - tile) / 2
            val y = (height - tile) / 2
            g.color = ForestTheme.THUMBNAIL
            g.fillRoundRect(x, y, tile, tile, 10, 10)
            val mark = artwork
            if (mark != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(mark, x + 4, y + 4, tile - 8, tile - 8, null)
            } else {
                g.color = ForestTheme.APP_BAR
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
                val metrics = g.fontMetrics
                g.drawString(initial, x + (tile - metrics.stringWidth(initial)) / 2,
                    y + (tile - metrics.height) / 2 + metrics.ascent)
            }
            if (remaining in 0f..1f) {
                val ring = tile + 6
                val ringX = x - 3
                val ringY = y - 3
                g.stroke = BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = ForestTheme.OTP_AMBER_MUTED
                g.drawArc(ringX, ringY, ring, ring, 90, -360)
                g.color = if (expiring) ForestTheme.WARNING else ForestTheme.OTP_AMBER
                g.drawArc(ringX, ringY, ring, ring, 90, -(360 * remaining).toInt().coerceAtMost(-1))
            }
        } finally {
            g.dispose()
        }
    }
}

/** Right card section is a genuine checkbox for both rendering and editing. */
private class CardCheckBox : JCheckBox("Show") {
    var rowSelected: Boolean = false
    var rowHovered: Boolean = false

    init {
        isOpaque = false
        horizontalAlignment = SwingConstants.CENTER
        foreground = ForestTheme.TEXT_PRIMARY
        getAccessibleContext().accessibleName = "Show entry details"
    }

    override fun paintComponent(graphics: Graphics) {
        paintCardHalf(graphics, width, height, rowSelected, rowHovered, left = false)
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
        rowHovered = CardHover.rowOf(table) == row
        isEnabled = table.isEnabled
        foreground = if (rowSelected || rowHovered) ForestTheme.OTP_AMBER else ForestTheme.TEXT_PRIMARY
    }
}

private class ShowEditor : DefaultCellEditor(CardCheckBox()) {
    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
    ): Component = (super.getTableCellEditorComponent(table, value, isSelected, row, column) as CardCheckBox)
        .apply { rowSelected = isSelected }
}

/**
 * Draw each half with only its outer corners rounded, forming one uninterrupted card: a soft
 * shadow, a top-lit gradient that lifts on hover, and an amber edge on the selected row.
 */
private fun paintCardHalf(
    graphics: Graphics, width: Int, height: Int, selected: Boolean, hovered: Boolean, left: Boolean
) {
    val g = graphics.create() as Graphics2D
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val radius = 12
        g.color = ForestTheme.CARD_SHADOW
        g.fill(halfShape(width, height - 4, radius, left, 4))
        g.paint = ForestTheme.cardPaint(height, hovered, selected)
        g.fill(halfShape(width, height, radius, left, 0))
        if (selected && left) {
            g.color = ForestTheme.OTP_AMBER
            g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(2, 14, 2, (height - 14).coerceAtLeast(14))
        }
    } finally {
        g.dispose()
    }
}

/** One half of the card body; the seam side runs past the component so no inner edge appears. */
private fun halfShape(width: Int, height: Int, radius: Int, left: Boolean, offsetY: Int): RoundRectangle2D.Float =
    if (left) RoundRectangle2D.Float(0f, offsetY.toFloat(), (width + radius).toFloat(), height.toFloat(),
        radius * 2f, radius * 2f)
    else RoundRectangle2D.Float(-radius.toFloat(), offsetY.toFloat(), (width + radius).toFloat(), height.toFloat(),
        radius * 2f, radius * 2f)
