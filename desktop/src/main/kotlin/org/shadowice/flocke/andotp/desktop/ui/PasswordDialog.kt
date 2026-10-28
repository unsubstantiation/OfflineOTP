package org.shadowice.flocke.andotp.desktop.ui

import java.awt.GridLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField

/** Modal password prompt; returns the typed password or null when cancelled. */
object PasswordDialog {
    fun prompt(parent: JFrame, title: String): String? {
        val field = JPasswordField(20)
        val panel = JPanel(GridLayout(0, 1, 0, 6)).apply {
            add(JLabel("Password:"))
            add(field)
        }
        val choice = JOptionPane.showConfirmDialog(
            parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        return if (choice == JOptionPane.OK_OPTION) String(field.password) else null
    }
}
