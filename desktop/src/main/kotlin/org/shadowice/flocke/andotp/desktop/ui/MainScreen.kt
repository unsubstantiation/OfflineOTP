package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TableModelListener
import javax.swing.table.TableModel

/** A tag name remains separate from its label/count, so special names never collide with All. */
internal data class TagChoice(val kind: Kind, val tag: String = "", val count: Int = 0) {
    enum class Kind { ALL, UNTAGGED, TAG }
    override fun toString(): String = when (kind) {
        Kind.ALL -> "All tags"
        Kind.UNTAGGED -> "Untagged ($count)"
        Kind.TAG -> "$tag ($count)"
    }
    fun sameFilter(other: TagChoice): Boolean = kind == other.kind && tag == other.tag
}

/** Lightweight main-window content, separated from JFrame so its layout can be checked headlessly. */
internal class MainScreen(val table: JTable, val copyButton: JButton) {
    val search = JTextField(20)
    val newVaultButton = JButton("New vault…")
    val openVaultButton = JButton("Open vault…")
    val addButton = JButton("+  Add entry")
    val editButton = JButton("Edit…")
    val deleteButton = JButton("Delete")
    val tagsButton = JButton("Tags…")
    val tagFilter = JComboBox<TagChoice>().apply {
        renderer = PlainTagRenderer()
        prototypeDisplayValue = TagChoice(TagChoice.Kind.TAG, "Example tag", 9999)
        addItem(TagChoice(TagChoice.Kind.ALL))
        accessibleContext.accessibleName = "Filter by tag"
        toolTipText = "Filter the selected tab by a tag or show untagged entries"
    }
    val categories = JTabbedPane().apply {
        listOf("OTP", "Notes", "SSH Keys", "Passwords").forEach { addTab(it, JPanel()) }
        preferredSize = Dimension(400, 40)
    }
    val panel: JPanel

    private val count = JLabel()
    private val emptyTitle = JLabel("", SwingConstants.CENTER)
    private val emptyDescription = JLabel("", SwingConstants.CENTER)
    private val bodyLayout = CardLayout()
    private val body = JPanel(bodyLayout)
    private var unlocked = false
    private val modelListener = TableModelListener { refreshState() }

    init {
        ForestTheme.makePrimary(addButton)
        addButton.toolTipText = "Add an account, secure note, SSH key, password file or QR image"
        copyButton.toolTipText = "Copy the selected item (or double-click its row); choose an SSH key explicitly"
        search.toolTipText = "Search issuer, label or tags"
        search.accessibleContext.accessibleName = "Search entries"

        val brand = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            add(JLabel(LockIcon(38)), BorderLayout.WEST)
            add(JPanel(java.awt.GridLayout(2, 1, 0, 1)).apply {
                isOpaque = false
                add(JLabel("OfflineOTP").apply {
                    font = Font(Font.SANS_SERIF, Font.BOLD, 20)
                    foreground = ForestTheme.TEXT_PRIMARY
                })
                add(JLabel("Your offline authenticator").apply {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                    foreground = ForestTheme.TEXT_PRIMARY
                })
            }, BorderLayout.CENTER)
        }
        val vaultActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(newVaultButton); add(openVaultButton)
        }
        val header = JPanel(BorderLayout(16, 0)).apply {
            background = ForestTheme.APP_BAR
            border = BorderFactory.createEmptyBorder(14, 24, 16, 24)
            add(brand, BorderLayout.WEST)
            add(vaultActions, BorderLayout.EAST)
        }

        val heading = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(JLabel("Your entries").apply {
                    font = Font(Font.SANS_SERIF, Font.BOLD, 19)
                    foreground = ForestTheme.TEXT_PRIMARY
                })
                count.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                count.foreground = ForestTheme.TEXT_SECONDARY
                add(count)
            }, BorderLayout.WEST)
            add(addButton, BorderLayout.EAST)
        }
        val searchLabel = JLabel("Search").apply {
            labelFor = search
            foreground = ForestTheme.TEXT_SECONDARY
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        }
        val searchRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchLabel, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, 12, 0, 0)
                add(JLabel("Tag").apply { labelFor = tagFilter; foreground = ForestTheme.TEXT_SECONDARY }, BorderLayout.WEST)
                add(tagFilter, BorderLayout.CENTER)
            }, BorderLayout.EAST)
        }
        val selectionActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(copyButton); add(editButton); add(tagsButton); add(deleteButton)
        }
        val controls = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            fun addRow(component: Component, row: Int, bottom: Int) {
                add(component, GridBagConstraints().apply {
                    gridx = 0; gridy = row; weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(0, 0, bottom, 0)
                })
            }
            addRow(heading, 0, 16)
            addRow(categories, 1, 12)
            addRow(searchRow, 2, 12)
            addRow(selectionActions, 3, 14)
        }

        val scroll = JScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = ForestTheme.BACKGROUND
            verticalScrollBar.background = ForestTheme.BACKGROUND
            verticalScrollBar.unitIncrement = 24
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        val empty = JPanel(GridBagLayout()).apply {
            background = ForestTheme.BACKGROUND
            val c = GridBagConstraints().apply {
                gridx = 0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 8, 0)
            }
            add(JLabel(LockIcon(52), SwingConstants.CENTER), c.apply { gridy = 0; insets = Insets(0, 0, 16, 0) })
            emptyTitle.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
            emptyTitle.foreground = ForestTheme.TEXT_PRIMARY
            add(emptyTitle, c.apply { gridy = 1; insets = Insets(0, 0, 6, 0) })
            emptyDescription.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            emptyDescription.foreground = ForestTheme.TEXT_SECONDARY
            add(emptyDescription, c.apply { gridy = 2; insets = Insets(0, 0, 0, 0) })
        }
        body.background = ForestTheme.BACKGROUND
        body.add(scroll, "entries")
        body.add(empty, "empty")

        val content = JPanel(BorderLayout()).apply {
            background = ForestTheme.BACKGROUND
            border = BorderFactory.createEmptyBorder(22, 24, 14, 24)
            add(controls, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
        panel = JPanel(BorderLayout()).apply {
            background = ForestTheme.BACKGROUND
            preferredSize = Dimension(900, 680)
            add(header, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }

        table.model.addTableModelListener(modelListener)
        table.addPropertyChangeListener("model") { event ->
            (event.oldValue as? TableModel)?.removeTableModelListener(modelListener)
            (event.newValue as? TableModel)?.addTableModelListener(modelListener)
            refreshState()
        }
        table.selectionModel.addListSelectionListener { refreshActions() }
        categories.addChangeListener { refreshState() }
        tagFilter.addActionListener { refreshState() }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshState()
            override fun removeUpdate(event: DocumentEvent) = refreshState()
            override fun changedUpdate(event: DocumentEvent) = refreshState()
        })
        refreshState()
    }

    fun setUnlocked(value: Boolean) {
        unlocked = value
        refreshState()
    }

    private fun refreshState() {
        val rows = table.rowCount
        count.text = if (unlocked) "($rows)" else ""
        when {
            !unlocked -> {
                emptyTitle.text = "Your vault is locked"
                emptyDescription.text = "Open a vault or create a new one to get started."
            }
            search.text.isNotEmpty() || (tagFilter.selectedItem as? TagChoice)?.kind != TagChoice.Kind.ALL -> {
                emptyTitle.text = "No matching entries"
                emptyDescription.text = "Try another search."
            }
            else -> {
                emptyTitle.text = "No entries yet"
                emptyDescription.text = when (categories.selectedIndex) {
                    0 -> "Add an OTP account to get started."
                    1 -> "Add a secure note to get started."
                    2 -> "Add an SSH key to get started."
                    else -> "Import a browser password file to get started."
                }
            }
        }
        bodyLayout.show(body, if (unlocked && rows > 0) "entries" else "empty")
        addButton.isEnabled = unlocked
        search.isEnabled = unlocked
        tagFilter.isEnabled = unlocked
        refreshActions()
    }

    private fun refreshActions() {
        val viewRow = table.selectedRow
        val modelRow = if (viewRow in 0 until table.rowCount) table.convertRowIndexToModel(viewRow) else -1
        val model = table.model as? EntryTableModel
        val hasSelection = unlocked && model != null && modelRow in 0 until model.rowCount
        val visible = hasSelection && model!!.getValueAt(modelRow, EntryTableModel.Column.SHOW.ordinal) == true
        copyButton.isEnabled = visible && !model!!.entryAt(modelRow).isPasswordArchive() && !model.entryAt(modelRow).isEmail()
        editButton.isEnabled = visible && !model!!.entryAt(modelRow).isPassword() && !model.entryAt(modelRow).isPasswordArchive() && !model.entryAt(modelRow).isEmail()
        tagsButton.isEnabled = visible
        deleteButton.isEnabled = hasSelection
    }
}

/** Simple app/empty-state lock; not an issuer-logo pipeline or a bundled third-party asset. */
private class LockIcon(private val size: Int) : Icon {
    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = ForestTheme.SURFACE_VARIANT
            g.fillRoundRect(x, y, size, size, 10, 10)
            val scale = size / 40f
            g.translate(x, y)
            g.scale(scale.toDouble(), scale.toDouble())
            g.color = ForestTheme.ACCENT
            g.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawArc(12, 6, 16, 20, 0, 180)
            g.drawLine(12, 16, 12, 20)
            g.drawLine(28, 16, 28, 20)
            g.fillRoundRect(9, 19, 22, 15, 4, 4)
            g.color = ForestTheme.ON_PRIMARY
            g.fillOval(18, 23, 4, 4)
            g.drawLine(20, 26, 20, 29)
        } finally { g.dispose() }
    }
}
