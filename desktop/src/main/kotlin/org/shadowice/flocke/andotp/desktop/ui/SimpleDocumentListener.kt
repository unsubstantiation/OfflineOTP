package org.shadowice.flocke.andotp.desktop.ui

import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Swing [DocumentListener] collapsed to one [update] callback for any document change. */
interface SimpleDocumentListener : DocumentListener {
    fun update()

    override fun insertUpdate(event: DocumentEvent) = update()
    override fun removeUpdate(event: DocumentEvent) = update()
    override fun changedUpdate(event: DocumentEvent) = update()
}
