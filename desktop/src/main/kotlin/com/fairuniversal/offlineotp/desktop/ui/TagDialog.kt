package com.fairuniversal.offlineotp.desktop.ui

import com.fairuniversal.offlineotp.Database.EntryTagFilter
import com.fairuniversal.offlineotp.Utilities.Constants

import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.ListSelectionModel

/** Treat user-supplied tag names as plain text, never Swing HTML. */
internal class PlainTagRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int,
                                              selected: Boolean, focus: Boolean): Component {
        putClientProperty("html.disable", true)
        val label = super.getListCellRendererComponent(list, value, index, selected, focus) as JLabel
        label.text = value?.toString().orEmpty()
        return label
    }
}

/** Headless-testable editor: the original list is borrowed and is not changed until Save. */
internal class TagEditorPanel(current: List<String>, private val known: List<String>) : JPanel(BorderLayout(8, 8)) {
    private val model = DefaultListModel<String>()
    val list = JList(model).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = PlainTagRenderer()
        accessibleContext.accessibleName = "Assigned tags"
    }
    val input = JComboBox(EntryTagFilter.sortedNames(known.distinct()).toTypedArray()).apply {
        isEditable = true
        renderer = PlainTagRenderer()
        prototypeDisplayValue = "Example tag name"
        selectedItem = ""
        accessibleContext.accessibleName = "Tag to add"
        toolTipText = "Type a new tag or choose an existing tag"
    }
    val addButton = JButton("Add tag")
    val removeButton = JButton("Remove selected")

    init {
        current.forEach { model.addElement(it) }
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        val label = JLabel("Type or choose a tag:").apply { labelFor = input }
        val addRow = JPanel(BorderLayout(8, 0)).apply {
            add(label, BorderLayout.WEST)
            add(input, BorderLayout.CENTER)
            add(addButton, BorderLayout.EAST)
        }
        add(addRow, BorderLayout.NORTH)
        add(JScrollPane(list).apply { preferredSize = Dimension(460, 140) }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(removeButton) }, BorderLayout.SOUTH)
        addButton.addActionListener { addPendingOrReport() }
        removeButton.addActionListener { removeSelected() }
    }

    fun tags(): List<String> = (0 until model.size()).map { model.getElementAt(it) }

    /** Blank input means no pending addition; existing imported tag names are preserved verbatim. */
    fun addPending() {
        val raw = input.editor.item?.toString().orEmpty()
        val value = if (raw in known && raw.isNotBlank()) raw else raw.trim()
        if (value.isEmpty()) return
        require(value.length <= InputPolicy.MAX_TAG_CHARS) { "Tags may contain at most ${InputPolicy.MAX_TAG_CHARS} characters." }
        if (!tags().contains(value)) {
            require(model.size() < InputPolicy.MAX_TAGS) { "An entry may have at most ${InputPolicy.MAX_TAGS} tags." }
            model.addElement(value)
        }
        input.editor.item = ""
    }

    private fun addPendingOrReport() {
        try { addPending() } catch (error: IllegalArgumentException) {
            JOptionPane.showMessageDialog(this, error.message, "Invalid tag", JOptionPane.ERROR_MESSAGE)
        }
    }

    fun removeSelected() {
        list.selectedIndices.reversed().forEach { model.remove(it) }
    }
}

internal data class MultiTagFilterSelection(
    val selectedTags: List<String>,
    val includeUntagged: Boolean,
    val functionality: Constants.TagFunctionality
)

/**
 * Headless-testable multi-tag filter panel matching Android's tag drawer (`OR`, `AND`, `SINGLE`
 * plus `Untagged`).
 */
internal class MultiTagFilterPanel(
    counts: Map<String, Int>,
    untaggedCount: Int,
    initialTags: List<String>,
    initialIncludeUntagged: Boolean,
    initialFunctionality: Constants.TagFunctionality
) : JPanel(BorderLayout(8, 8)) {

    internal data class ModeOption(val value: Constants.TagFunctionality, val label: String) {
        override fun toString(): String = label
    }

    private val modeOptions = arrayOf(
        ModeOption(Constants.TagFunctionality.OR, "Match any selected tag (OR)"),
        ModeOption(Constants.TagFunctionality.AND, "Match every selected tag (AND)"),
        ModeOption(Constants.TagFunctionality.SINGLE, "Single tag at a time")
    )

    val modeBox = JComboBox(modeOptions).apply {
        selectedIndex = modeOptions.indexOfFirst { it.value == initialFunctionality }.coerceAtLeast(0)
        accessibleContext.accessibleName = "Tag match mode"
    }
    val untaggedBox = JCheckBox("Untagged ($untaggedCount)", initialIncludeUntagged).apply {
        putClientProperty("html.disable", true)
    }
    private val tagChecks = LinkedHashMap<String, JCheckBox>()

    init {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        val top = JPanel(BorderLayout(8, 6)).apply {
            add(JLabel("Match rule:").apply { labelFor = modeBox }, BorderLayout.WEST)
            add(modeBox, BorderLayout.CENTER)
            add(untaggedBox, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)

        val checkColumn = JPanel(GridLayout(0, 1, 0, 4))
        for (name in EntryTagFilter.sortedNames(counts.keys)) {
            val check = JCheckBox("$name (${counts.getValue(name)})", name in initialTags).apply {
                putClientProperty("html.disable", true)
            }
            check.addActionListener {
                if (check.isSelected && selectedMode() == Constants.TagFunctionality.SINGLE) {
                    untaggedBox.isSelected = false
                    tagChecks.values.filter { it !== check }.forEach { it.isSelected = false }
                }
            }
            tagChecks[name] = check
            checkColumn.add(check)
        }
        untaggedBox.addActionListener {
            if (untaggedBox.isSelected && selectedMode() == Constants.TagFunctionality.SINGLE) {
                tagChecks.values.forEach { it.isSelected = false }
            }
        }
        modeBox.addActionListener {
            if (selectedMode() == Constants.TagFunctionality.SINGLE) {
                if (untaggedBox.isSelected) {
                    tagChecks.values.forEach { it.isSelected = false }
                } else {
                    val checked = tagChecks.values.filter { it.isSelected }
                    checked.drop(1).forEach { it.isSelected = false }
                }
            }
        }
        add(JScrollPane(checkColumn).apply { preferredSize = Dimension(380, 180) }, BorderLayout.CENTER)
        val clearButton = JButton("Clear filter").apply {
            addActionListener {
                untaggedBox.isSelected = false
                tagChecks.values.forEach { it.isSelected = false }
            }
        }
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(clearButton) }, BorderLayout.SOUTH)
    }

    private fun selectedMode(): Constants.TagFunctionality =
        (modeBox.selectedItem as? ModeOption)?.value ?: Constants.TagFunctionality.OR

    fun selection(): MultiTagFilterSelection = MultiTagFilterSelection(
        selectedTags = tagChecks.entries.filter { it.value.isSelected }.map { it.key },
        includeUntagged = untaggedBox.isSelected,
        functionality = selectedMode()
    )
}

internal object TagDialog {
    /** Cancel leaves the caller's tags unchanged; pending typed text is added on Save. */
    fun prompt(parent: Component, current: List<String>, known: List<String>): List<String>? {
        val panel = TagEditorPanel(current, known)
        while (true) {
            if (JOptionPane.showConfirmDialog(parent, panel, "Edit tags",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null
            try {
                panel.addPending()
                return panel.tags()
            } catch (error: IllegalArgumentException) {
                JOptionPane.showMessageDialog(parent, error.message, "Invalid tag", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    /** Multi-tag filter dialog with OR / AND / SINGLE + Untagged parity. */
    fun promptFilter(
        parent: Component,
        counts: Map<String, Int>,
        untaggedCount: Int,
        currentTags: List<String>,
        includeUntagged: Boolean,
        functionality: Constants.TagFunctionality
    ): MultiTagFilterSelection? {
        val panel = MultiTagFilterPanel(counts, untaggedCount, currentTags, includeUntagged, functionality)
        if (JOptionPane.showConfirmDialog(parent, panel, "Filter by tags",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null
        return panel.selection()
    }
}
