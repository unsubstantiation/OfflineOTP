package org.shadowice.flocke.andotp.desktop

import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import org.shadowice.flocke.andotp.desktop.ui.MainWindow

import javax.swing.SwingUtilities

/**
 * Desktop entry point.
 *
 * Swing requires the UI to be built on the event dispatch thread, so the frame is created inside
 * [SwingUtilities.invokeLater]. Storage assembly happens off the UI call: [DesktopStorage] opens
 * the per-user vault directory and the headless [VaultController] drives the portable core engine.
 */
fun main() {
    val controller = VaultController(DesktopStorage.open())
    SwingUtilities.invokeLater {
        MainWindow.createFrame(controller).isVisible = true
    }
}
