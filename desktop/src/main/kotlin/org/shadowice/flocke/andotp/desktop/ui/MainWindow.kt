package org.shadowice.flocke.andotp.desktop.ui

import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.WindowConstants

/**
 * Builds the top-level Swing window.
 *
 * Heavyweight by construction: call only on the event dispatch thread with a display. The frame is
 * deliberately thin — domain state lives in `:core`, and the row/column mapping in [EntryTableModel]
 * is covered headlessly without ever loading this class. Vault loading, actions and timers arrive
 * in later slices; the seed opens an empty window whose table is already bound to the core
 * projection so a populated [EntryList] renders immediately.
 */
object MainWindow {
    const val TITLE = "OfflineOTP"

    fun createFrame(entries: EntryList = EntryList()): JFrame {
        val presentation = EntryListPresentation(entries).apply { showReorderedEntries() }
        val tableModel = EntryTableModel(presentation)
        val table = JTable(tableModel)
        val status = JLabel(" ")

        val content = JPanel(BorderLayout()).apply {
            add(JScrollPane(table), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }

        return JFrame(TITLE).apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            contentPane = content
            minimumSize = Dimension(640, 360)
            pack()
            setLocationRelativeTo(null)
        }
    }
}
