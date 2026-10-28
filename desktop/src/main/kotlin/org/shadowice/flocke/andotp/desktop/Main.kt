package org.shadowice.flocke.andotp.desktop

import org.shadowice.flocke.andotp.desktop.ui.MainWindow

import javax.swing.SwingUtilities

/**
 * Desktop entry point.
 *
 * Swing requires the UI to be built on the event dispatch thread, so the frame is created inside
 * [SwingUtilities.invokeLater]. A real vault is opened through the storage layer in a later slice;
 * this seed starts with an empty but fully bound window.
 */
fun main() {
    SwingUtilities.invokeLater {
        MainWindow.createFrame().isVisible = true
    }
}
