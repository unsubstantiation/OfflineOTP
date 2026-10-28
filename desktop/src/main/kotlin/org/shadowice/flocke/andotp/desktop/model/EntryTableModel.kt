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
class EntryTableModel(private val presentation: EntryListPresentation) : AbstractTableModel() {

    enum class Column(val title: String) {
        ISSUER("Issuer"),
        LABEL("Label"),
        TYPE("Type"),
        TOKEN("Token"),
    }

    override fun getRowCount(): Int = presentation.size()

    override fun getColumnCount(): Int = Column.entries.size

    override fun getColumnName(column: Int): String = columnAt(column).title

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val entry = presentation.get(rowIndex)
        return when (columnAt(columnIndex)) {
            Column.ISSUER -> entry.getIssuer()
            Column.LABEL -> entry.getLabel()
            Column.TYPE -> entry.getType().name
            // Cached token only; a null value means no token has been generated for this step yet.
            Column.TOKEN -> entry.getCurrentOTP().orEmpty()
        }
    }

    /** Resolves the backing domain object for a row, so actions never carry a stale row index. */
    fun entryAt(rowIndex: Int): Entry = presentation.get(rowIndex)

    /** Re-reads the projection after a sort/search/refresh and notifies any bound [javax.swing.JTable]. */
    fun refresh() {
        fireTableDataChanged()
    }

    private fun columnAt(column: Int): Column = Column.entries[column]
}
