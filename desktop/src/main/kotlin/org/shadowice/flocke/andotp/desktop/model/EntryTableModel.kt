package org.shadowice.flocke.andotp.desktop.model

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.View.EntryListPresentation

import javax.swing.table.AbstractTableModel

/**
 * Swing [javax.swing.table.TableModel] over the shared, Android-free [EntryListPresentation].
 *
 * All sorting, tag filtering and search stay in `:core`; this adapter only translates the current
 * display projection into Swing's row/column contract. It creates no heavyweight peer, so the
 * mapping is exercised by headless JUnit tests while the [javax.swing.JTable] that renders it lives
 * in the UI shell. The token column reads the cached OTP and renders blank until the entry's token
 * has been calculated; the model never starts timers or computes tokens itself.
 */
class EntryTableModel(
    private val presentation: EntryListPresentation,
    private val changeVisibility: (Entry, Boolean) -> Unit = { _, _ -> }
) : AbstractTableModel() {

    enum class Column(val title: String) {
        ISSUER("Issuer"),
        LABEL("Label"),
        TYPE("Type"),
        TOKEN("Token"),
        SHOW("Show"),
    }

    override fun getRowCount(): Int = presentation.size()

    override fun getColumnCount(): Int = Column.entries.size

    override fun getColumnName(column: Int): String = columnAt(column).title

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val entry = presentation.get(rowIndex)
        val column = columnAt(columnIndex)
        if (column == Column.SHOW) return entry.areDetailsVisible()
        // Issuer and label identify a hidden entry; type and token remain redacted.
        if (!entry.areDetailsVisible() && (column == Column.TYPE || column == Column.TOKEN)) return ""
        return when (column) {
            Column.ISSUER -> entry.getIssuer()
            Column.LABEL -> entry.getLabel()
            Column.TYPE -> entry.getType().name
            // Cached token only; a null value means no token has been generated for this step yet.
            Column.TOKEN -> when {
                entry.isNote() -> "[Note hidden]"
                entry.isSshKey() -> "[SSH keys hidden]"
                else -> entry.getCurrentOTP().orEmpty()
            }
            Column.SHOW -> entry.areDetailsVisible()
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnAt(columnIndex) == Column.SHOW) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnAt(columnIndex) == Column.SHOW

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnAt(columnIndex) == Column.SHOW && value is Boolean) {
            changeVisibility(presentation.get(rowIndex), value)
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
    }

    /** Resolves the backing domain object for a row, so actions never carry a stale row index. */
    fun entryAt(rowIndex: Int): Entry = presentation.get(rowIndex)

    /** Re-reads the projection after a sort/search/refresh and notifies any bound [javax.swing.JTable]. */
    fun refresh() {
        fireTableDataChanged()
    }

    /** A timer tick updates cached values only; do not clear the user's row selection every second. */
    fun refreshValues() {
        if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1)
    }

    private fun columnAt(column: Int): Column = Column.entries[column]
}
