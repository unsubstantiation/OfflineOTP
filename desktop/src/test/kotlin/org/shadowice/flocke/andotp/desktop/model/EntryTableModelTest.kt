/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the Swing table-model bridge. AbstractTableModel creates no heavyweight
 * peer, so these run under -Djava.awt.headless=true without a display; MainWindow (the JFrame shell)
 * is compile-only in the sandbox and is not exercised here.
 */
package org.shadowice.flocke.andotp.desktop.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.View.EntryListPresentation

import java.util.ArrayList
import javax.swing.event.TableModelEvent

class EntryTableModelTest {

    private fun entry(
        label: String,
        issuer: String,
        type: Entry.OTPType = Entry.OTPType.TOTP
    ): Entry = Entry(
        type, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
        TokenCalculator.HashAlgorithm.SHA1, ArrayList()
    )

    private fun presentationWith(vararg entries: Entry): EntryListPresentation {
        val list = EntryList()
        entries.forEach { list.addEntry(it) }
        return EntryListPresentation(list).apply { showReorderedEntries() }
    }

    @Test
    fun emptyProjectionExposesTheColumnContractAndNoRows() {
        val model = EntryTableModel(presentationWith())

        assertEquals(0, model.rowCount)
        assertEquals(4, model.columnCount)
        assertEquals("Issuer", model.getColumnName(0))
        assertEquals("Label", model.getColumnName(1))
        assertEquals("Type", model.getColumnName(2))
        assertEquals("Token", model.getColumnName(3))
    }

    @Test
    fun rowsMapIssuerLabelAndTypeFromTheBorrowedEntries() {
        val totp = entry("github", "GitHub")
        val hotp = entry("vpn", "Work", Entry.OTPType.HOTP)
        val model = EntryTableModel(presentationWith(totp, hotp))

        assertEquals(2, model.rowCount)
        assertEquals("GitHub", model.getValueAt(0, 0))
        assertEquals("github", model.getValueAt(0, 1))
        assertEquals("TOTP", model.getValueAt(0, 2))
        assertEquals("HOTP", model.getValueAt(1, 2))
        // No token calculated yet: the cell is blank, never a stale value from another entry.
        assertEquals("", model.getValueAt(0, 3))
    }

    @Test
    fun entryAtResolvesTheBackingDomainObjectInsteadOfARowIndex() {
        val totp = entry("github", "GitHub")
        val model = EntryTableModel(presentationWith(totp))

        // Referential identity: the row hands back the same borrowed instance, not a copy.
        assertTrue(totp === model.entryAt(0))
    }

    @Test
    fun tokenCellShowsTheCalculatedOtpAfterRefreshWithoutGeneratingItItself() {
        val totp = entry("github", "GitHub")
        val presentation = presentationWith(totp)
        val model = EntryTableModel(presentation)

        assertTrue(totp.updateOTPAt(59L, true))
        model.refresh()

        val token = model.getValueAt(0, 3) as String
        assertEquals("a TOTP token at step 1 is six digits", 6, token.length)
        assertTrue("token must be numeric", token.all { it.isDigit() })
        assertTrue(totp === model.entryAt(0))
    }

    @Test
    fun refreshNotifiesListenersExactlyOnceThatTheDataChanged() {
        val model = EntryTableModel(presentationWith(entry("a", "A")))
        var events = 0
        model.addTableModelListener { _: TableModelEvent -> events++ }

        model.refresh()
        model.refresh()

        assertEquals(2, events)
    }
}
