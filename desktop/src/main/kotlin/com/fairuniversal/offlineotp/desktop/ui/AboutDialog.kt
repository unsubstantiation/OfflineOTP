package com.fairuniversal.offlineotp.desktop.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel

/**
 * Desktop About dialog — the counterpart of Android's AboutActivity/AboutFragment.
 *
 * Shows the same attribution the license requires on a user-visible screen:
 * Copyright (c) 2026 unsubstantiation — http://fairuniversal.com and
 * https://github.com/unsubstantiation/OfflineOTP/releases
 * Each link is clickable (Desktop.browse when available, clipboard fallback).
 */
internal object AboutDialog {
    const val COPYRIGHT = "Copyright (c) 2026 unsubstantiation \u2014 http://fairuniversal.com and https://github.com/unsubstantiation/OfflineOTP/releases"
    const val WEBSITE_URI = "http://fairuniversal.com"
    const val RELEASES_URI = "https://github.com/unsubstantiation/OfflineOTP/releases"
    const val SOURCE_URI = "https://github.com/unsubstantiation/OfflineOTP"

    fun show(parent: JFrame) {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
            add(JLabel("OfflineOTP").apply {
                font = font.deriveFont(16f).deriveFont(java.awt.Font.BOLD)
            })
            add(JLabel("Offline authenticator").apply {
                border = BorderFactory.createEmptyBorder(4, 0, 8, 0)
            })
            add(JLabel(COPYRIGHT).apply {
                border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            })
            add(linkRow("Website:", WEBSITE_URI, parent))
            add(linkRow("Releases:", RELEASES_URI, parent))
            add(linkRow("Source:", SOURCE_URI, parent))
        }
        JOptionPane.showMessageDialog(parent, panel, "About OfflineOTP", JOptionPane.INFORMATION_MESSAGE)
    }

    private fun linkRow(label: String, uri: String, parent: JFrame): JPanel {
        val link = JLabel("<html><a href=\"\">$uri</a></html>").apply {
            foreground = Color(0x1A73E8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = uri
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    openUri(uri, parent)
                }
            })
        }
        return JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
            add(JLabel(label).apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.WEST)
            add(link, BorderLayout.CENTER)
            // Copy button as fallback / explicit action
            add(JButton("Copy").apply {
                addActionListener { copyToClipboard(uri, parent) }
            }, BorderLayout.EAST)
        }
    }

    private fun openUri(uri: String, parent: JFrame) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(uri))
                return
            }
        } catch (_: Exception) {
            // fall through to clipboard
        }
        copyToClipboard(uri, parent)
    }

    private fun copyToClipboard(text: String, parent: JFrame) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            JOptionPane.showMessageDialog(parent, "Link copied to clipboard:\n$text", "About", JOptionPane.INFORMATION_MESSAGE)
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(parent, text, "About", JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
