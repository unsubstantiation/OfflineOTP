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
    ).apply { setDetailsVisible(true) }

    private fun presentationWith(vararg entries: Entry): EntryListPresentation {
        val list = EntryList()
        entries.forEach { list.addEntry(it) }
        return EntryListPresentation(list).apply { showReorderedEntries() }
    }

    @Test
    fun emptyProjectionExposesTheColumnContractAndNoRows() {
        val model = EntryTableModel(presentationWith())

        assertEquals(0, model.rowCount)
        assertEquals(5, model.columnCount)
        assertEquals("Issuer", model.getColumnName(0))
        assertEquals("Label", model.getColumnName(1))
        assertEquals("Type", model.getColumnName(2))
        assertEquals("Token", model.getColumnName(3))
        assertEquals("Show", model.getColumnName(4))
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
    fun sshKeysAreNotShownInTheTable() {
        val key = Entry.sshKey("public text", "private text", "", "Server", arrayListOf())
        key.setDetailsVisible(true)
        val model = EntryTableModel(presentationWith(key))
        assertEquals("SSH_KEY", model.getValueAt(0, 2))
        assertEquals("[SSH keys hidden]", model.getValueAt(0, 3))
    }

    @Test
    fun noteContentsAreNotShownInTheTable() {
        val note = Entry(Entry.OTPType.NOTE, "a secret recovery key", "", "Recovery", arrayListOf())
        note.setDetailsVisible(true)
        val model = EntryTableModel(presentationWith(note))
        assertEquals("NOTE", model.getValueAt(0, 2))
        assertEquals("[Note hidden]", model.getValueAt(0, 3))
    }

    @Test
    fun hiddenRowsExposeOnlyTitleAndToggleForEveryKind() {
        val otp = entry("Account", "Issuer").apply { setDetailsVisible(false); updateOTPAt(59L, true) }
        val note = Entry(Entry.OTPType.NOTE, "secret", "", "Note", arrayListOf())
        val key = Entry.sshKey("public", "private", "", "Server", arrayListOf())
        for (item in listOf(otp, note, key)) {
            val model = EntryTableModel(presentationWith(item)) { e, visible -> e.setDetailsVisible(visible) }
            assertEquals("", model.getValueAt(0, 0))
            assertEquals(item.privacyTitle(), model.getValueAt(0, 1))
            assertEquals("", model.getValueAt(0, 2))
            assertEquals("", model.getValueAt(0, 3))
            assertEquals(false, model.getValueAt(0, 4))
            assertTrue(model.isCellEditable(0, 4))
            model.setValueAt(true, 0, 4)
            assertEquals(true, model.getValueAt(0, 4))
            model.setValueAt(false, 0, 4)
            assertEquals("", model.getValueAt(0, 3))
        }
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
