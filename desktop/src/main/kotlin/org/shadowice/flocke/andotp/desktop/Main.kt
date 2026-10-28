package org.shadowice.flocke.andotp.desktop

import org.shadowice.flocke.andotp.desktop.control.HardwareKeyManager
import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.hardware.PcscHardwareDiscovery
import org.shadowice.flocke.andotp.desktop.storage.DesktopHardwareUnlockStore
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import org.shadowice.flocke.andotp.desktop.ui.MainWindow

import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Desktop entry point.
 *
 * Swing requires the UI to be built on the event dispatch thread, so the frame is created inside
 * [SwingUtilities.invokeLater]. Storage assembly happens off the UI call: [DesktopStorage] opens
 * the per-user vault directory and the headless [VaultController] drives the portable core engine.
 * The PC/SC security-key discovery and its envelope store are assembled once and shared by the
 * hardware menu actions. A storage failure is reported as a dialog (or stderr on a headless host)
 * instead of a raw stack trace, and the process exits non-zero so launchers notice it.
 */
fun main() {
    val directory = DesktopStorage.defaultDirectory()
    val controller = try {
        VaultController(DesktopStorage.open(directory))
    } catch (error: Exception) {
        val message = "OfflineOTP could not open its vault directory: ${error.message ?: error.javaClass.simpleName}"
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println(message)
        } else {
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(null, message, MainWindow.TITLE, JOptionPane.ERROR_MESSAGE)
            }
        }
        System.exit(1)
        return
    }
    val hardware = HardwareKeyManager(
        PcscHardwareDiscovery(),
        DesktopHardwareUnlockStore(directory))
    SwingUtilities.invokeLater {
        MainWindow.createFrame(controller, hardware).isVisible = true
    }
}
