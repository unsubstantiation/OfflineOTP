package org.shadowice.flocke.andotp.desktop.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.AbstractBorder
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.ComponentUI
import javax.swing.plaf.FontUIResource
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.BasicGraphicsUtils
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.plaf.metal.DefaultMetalTheme
import javax.swing.plaf.metal.MetalLookAndFeel

/**
 * The Android Dark Forest color tokens, applied to the shared Windows/Debian Swing client.
 * Install before creating any Swing components, on the event dispatch thread. The JDK's
 * cross-platform look and feel is intentional: dialogs, menus and file choosers must not fall
 * back to a bright OS-specific theme. No third-party UI library is needed.
 */
object ForestTheme {
    val BACKGROUND = ColorUIResource(0x10, 0x1A, 0x14)
    val BACKGROUND_DEEP = ColorUIResource(0x0A, 0x11, 0x0D)
    val SURFACE = ColorUIResource(0x1A, 0x2C, 0x21)
    val SURFACE_ELEVATED = ColorUIResource(0x21, 0x37, 0x2A)
    val SURFACE_VARIANT = ColorUIResource(0x26, 0x3F, 0x30)
    val APP_BAR = ColorUIResource(0x1B, 0x4D, 0x2E)
    val PRIMARY = ColorUIResource(0x4C, 0xAF, 0x50)
    val ACCENT = ColorUIResource(0x81, 0xC7, 0x84)
    val TEXT_PRIMARY = ColorUIResource(0xE6, 0xF0, 0xE8)
    val TEXT_SECONDARY = ColorUIResource(0xA3, 0xBC, 0xA9)
    val TEXT_HINT = ColorUIResource(0x92, 0xAC, 0x97)
    val ON_PRIMARY = ColorUIResource(0x0D, 0x17, 0x11)
    val DIVIDER = ColorUIResource(0x2B, 0x44, 0x34)
    val WARNING = ColorUIResource(0xFF, 0x8A, 0x80)
    val THUMBNAIL = ColorUIResource(0xC8, 0xD8, 0xCC)

    private const val PRIMARY_BUTTON = "offlineotp.primaryButton"
    private var installed = false

    fun install() {
        if (installed && UIManager.getLookAndFeel() is MetalLookAndFeel &&
            UIManager.getColor("Panel.background") == SURFACE_ELEVATED) return

        MetalLookAndFeel.setCurrentTheme(ForestMetalTheme())
        UIManager.setLookAndFeel(MetalLookAndFeel())
        val font = FontUIResource(Font.SANS_SERIF, Font.PLAIN, 13)
        val bold = FontUIResource(Font.SANS_SERIF, Font.BOLD, 13)
        val inputBorder = BorderFactory.createCompoundBorder(
            ForestFocusBorder(), BorderFactory.createEmptyBorder(7, 9, 7, 9))
        val buttonBorder = BorderFactory.createEmptyBorder(8, 13, 8, 13)

        val defaults = mapOf<String, Any>(
            // Metal's system colors also feed controls that do not expose individual defaults.
            "control" to SURFACE_ELEVATED, "controlText" to TEXT_PRIMARY,
            "controlShadow" to DIVIDER, "controlDkShadow" to BACKGROUND_DEEP,
            "controlHighlight" to SURFACE_VARIANT, "controlLtHighlight" to SURFACE_VARIANT,
            "controlDisabled" to TEXT_HINT, "text" to SURFACE_ELEVATED,
            "textText" to TEXT_PRIMARY, "textInactiveText" to TEXT_HINT,
            "textHighlight" to ACCENT, "textHighlightText" to ON_PRIMARY,
            "window" to SURFACE_ELEVATED, "windowText" to TEXT_PRIMARY,
            "menu" to SURFACE_ELEVATED, "menuText" to TEXT_PRIMARY,
            "info" to SURFACE_VARIANT, "infoText" to TEXT_PRIMARY,
            "Panel.background" to SURFACE_ELEVATED, "Panel.foreground" to TEXT_PRIMARY,
            "Label.foreground" to TEXT_PRIMARY, "Label.disabledForeground" to TEXT_HINT,
            "Label.font" to font,
            "ButtonUI" to ForestButtonUI::class.java.name,
            "Button.background" to SURFACE_VARIANT, "Button.foreground" to TEXT_PRIMARY,
            "Button.disabledText" to TEXT_HINT, "Button.select" to SURFACE,
            "Button.focus" to ACCENT, "Button.border" to buttonBorder, "Button.font" to bold,
            "ToggleButton.background" to SURFACE_VARIANT, "ToggleButton.foreground" to TEXT_PRIMARY,
            "ToggleButton.select" to APP_BAR, "ToggleButton.font" to font,
            "CheckBox.background" to SURFACE_ELEVATED, "CheckBox.foreground" to TEXT_PRIMARY,
            "CheckBox.disabledText" to TEXT_HINT, "CheckBox.focus" to ACCENT,
            "CheckBox.icon" to ForestChoiceIcon(false), "CheckBox.font" to font,
            "RadioButton.background" to SURFACE_ELEVATED, "RadioButton.foreground" to TEXT_PRIMARY,
            "RadioButton.disabledText" to TEXT_HINT, "RadioButton.focus" to ACCENT,
            "RadioButton.icon" to ForestChoiceIcon(true), "RadioButton.font" to font,
            "TextField.background" to SURFACE, "TextField.foreground" to TEXT_PRIMARY,
            "TextField.inactiveForeground" to TEXT_HINT, "TextField.disabledForeground" to TEXT_HINT,
            "TextField.caretForeground" to TEXT_PRIMARY, "TextField.selectionBackground" to ACCENT,
            "TextField.selectionForeground" to ON_PRIMARY, "TextField.border" to inputBorder,
            "TextField.font" to font,
            "PasswordField.background" to SURFACE, "PasswordField.foreground" to TEXT_PRIMARY,
            "PasswordField.inactiveForeground" to TEXT_HINT, "PasswordField.disabledForeground" to TEXT_HINT,
            "PasswordField.caretForeground" to TEXT_PRIMARY, "PasswordField.selectionBackground" to ACCENT,
            "PasswordField.selectionForeground" to ON_PRIMARY, "PasswordField.border" to inputBorder,
            "PasswordField.font" to font,
            "TextArea.background" to SURFACE, "TextArea.foreground" to TEXT_PRIMARY,
            "TextArea.inactiveForeground" to TEXT_HINT, "TextArea.disabledForeground" to TEXT_HINT,
            "TextArea.caretForeground" to TEXT_PRIMARY, "TextArea.selectionBackground" to ACCENT,
            "TextArea.selectionForeground" to ON_PRIMARY,
            "TextArea.border" to BorderFactory.createEmptyBorder(7, 9, 7, 9), "TextArea.font" to font,
            "TextPane.background" to SURFACE, "TextPane.foreground" to TEXT_PRIMARY,
            "TextPane.caretForeground" to TEXT_PRIMARY, "TextPane.font" to font,
            "EditorPane.background" to SURFACE, "EditorPane.foreground" to TEXT_PRIMARY,
            "EditorPane.caretForeground" to TEXT_PRIMARY, "EditorPane.font" to font,
            "ComboBoxUI" to ForestComboBoxUI::class.java.name,
            "ComboBox.background" to SURFACE, "ComboBox.foreground" to TEXT_PRIMARY,
            "ComboBox.disabledBackground" to SURFACE_ELEVATED, "ComboBox.disabledForeground" to TEXT_HINT,
            "ComboBox.selectionBackground" to SURFACE_VARIANT, "ComboBox.selectionForeground" to TEXT_PRIMARY,
            "ComboBox.border" to ForestFocusBorder(), "ComboBox.font" to font,
            "List.background" to SURFACE_ELEVATED, "List.foreground" to TEXT_PRIMARY,
            "List.selectionBackground" to SURFACE_VARIANT, "List.selectionForeground" to TEXT_PRIMARY,
            "List.font" to font,
            "Table.background" to SURFACE, "Table.foreground" to TEXT_PRIMARY,
            "Table.selectionBackground" to SURFACE_VARIANT, "Table.selectionForeground" to TEXT_PRIMARY,
            "Table.gridColor" to DIVIDER, "Table.font" to font,
            "Table.focusCellHighlightBorder" to BorderFactory.createLineBorder(ACCENT),
            "TableHeader.background" to SURFACE_VARIANT, "TableHeader.foreground" to TEXT_PRIMARY,
            "TableHeader.cellBorder" to BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIVIDER), BorderFactory.createEmptyBorder(4, 8, 4, 8)),
            "TableHeader.font" to bold,
            "ScrollPane.background" to SURFACE_ELEVATED,
            "ScrollPane.border" to BorderFactory.createLineBorder(DIVIDER),
            "Viewport.background" to SURFACE,
            "ScrollBarUI" to ForestScrollBarUI::class.java.name,
            "ScrollBar.background" to SURFACE, "ScrollBar.track" to SURFACE,
            "ScrollBar.thumb" to DIVIDER, "ScrollBar.width" to 12,
            "MenuBar.background" to APP_BAR, "MenuBar.foreground" to TEXT_PRIMARY,
            "MenuBar.border" to BorderFactory.createEmptyBorder(2, 12, 2, 12),
            "Menu.background" to APP_BAR, "Menu.foreground" to TEXT_PRIMARY,
            "Menu.selectionBackground" to SURFACE_VARIANT, "Menu.selectionForeground" to TEXT_PRIMARY,
            "Menu.disabledForeground" to TEXT_HINT, "Menu.font" to font,
            "MenuItem.background" to SURFACE_ELEVATED, "MenuItem.foreground" to TEXT_PRIMARY,
            "MenuItem.selectionBackground" to SURFACE_VARIANT, "MenuItem.selectionForeground" to TEXT_PRIMARY,
            "MenuItem.disabledForeground" to TEXT_HINT, "MenuItem.font" to font,
            "CheckBoxMenuItem.background" to SURFACE_ELEVATED, "CheckBoxMenuItem.foreground" to TEXT_PRIMARY,
            "CheckBoxMenuItem.selectionBackground" to SURFACE_VARIANT,
            "CheckBoxMenuItem.selectionForeground" to TEXT_PRIMARY,
            "RadioButtonMenuItem.background" to SURFACE_ELEVATED, "RadioButtonMenuItem.foreground" to TEXT_PRIMARY,
            "RadioButtonMenuItem.selectionBackground" to SURFACE_VARIANT,
            "RadioButtonMenuItem.selectionForeground" to TEXT_PRIMARY,
            "PopupMenu.background" to SURFACE_ELEVATED, "PopupMenu.foreground" to TEXT_PRIMARY,
            "PopupMenu.border" to BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIVIDER), BorderFactory.createEmptyBorder(4, 4, 4, 4)),
            "Separator.background" to DIVIDER, "Separator.foreground" to DIVIDER,
            "PopupMenuSeparator.background" to DIVIDER, "PopupMenuSeparator.foreground" to DIVIDER,
            "ToolBar.background" to BACKGROUND, "ToolBar.foreground" to TEXT_PRIMARY,
            "OptionPane.background" to SURFACE_ELEVATED, "OptionPane.foreground" to TEXT_PRIMARY,
            "OptionPane.messageForeground" to TEXT_PRIMARY, "OptionPane.messageFont" to font,
            "OptionPane.buttonFont" to bold,
            "OptionPane.errorIcon" to ForestMessageIcon(WARNING, "!"),
            "OptionPane.warningIcon" to ForestMessageIcon(WARNING, "!"),
            "OptionPane.informationIcon" to ForestMessageIcon(ACCENT, "i"),
            "OptionPane.questionIcon" to ForestMessageIcon(ACCENT, "?"),
            "OptionPane.messageAreaBorder" to BorderFactory.createEmptyBorder(10, 10, 10, 10),
            "OptionPane.buttonAreaBorder" to BorderFactory.createEmptyBorder(0, 10, 10, 10),
            "FileChooser.background" to SURFACE_ELEVATED, "FileChooser.foreground" to TEXT_PRIMARY,
            "FileChooser.upFolderIcon" to ForestFileIcon("up"),
            "FileChooser.homeFolderIcon" to ForestFileIcon("home"),
            "FileChooser.newFolderIcon" to ForestFileIcon("new"),
            "FileChooser.listViewIcon" to ForestFileIcon("list"),
            "FileChooser.detailsViewIcon" to ForestFileIcon("details"),
            "FileView.directoryIcon" to ForestFileIcon("folder"),
            "FileView.fileIcon" to ForestFileIcon("file"),
            "FileView.computerIcon" to ForestFileIcon("file"),
            "FileView.hardDriveIcon" to ForestFileIcon("file"),
            "FileView.floppyDriveIcon" to ForestFileIcon("file"),
            "ToolTip.background" to SURFACE_VARIANT, "ToolTip.foreground" to TEXT_PRIMARY,
            "ToolTip.border" to BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIVIDER), BorderFactory.createEmptyBorder(5, 7, 5, 7)),
            "ProgressBar.background" to SURFACE, "ProgressBar.foreground" to ACCENT,
            "ProgressBar.selectionBackground" to TEXT_PRIMARY, "ProgressBar.selectionForeground" to ON_PRIMARY,
            "TabbedPane.background" to SURFACE_ELEVATED, "TabbedPane.foreground" to TEXT_PRIMARY,
            "TabbedPane.selected" to SURFACE_VARIANT, "TabbedPane.font" to font,
            "TitledBorder.titleColor" to TEXT_PRIMARY, "TitledBorder.font" to bold,
        )
        defaults.forEach { (key, value) -> UIManager.put(key, value) }
        installed = true
    }

    fun makePrimary(button: JButton) {
        button.putClientProperty(PRIMARY_BUTTON, true)
        button.foreground = ON_PRIMARY
    }

    internal fun isPrimary(button: AbstractButton): Boolean =
        button.getClientProperty(PRIMARY_BUTTON) == true
}

private class ForestFocusBorder : AbstractBorder() {
    override fun getBorderInsets(component: Component): Insets = Insets(1, 1, 1, 1)
    override fun getBorderInsets(component: Component, insets: Insets): Insets = insets.apply {
        set(1, 1, 1, 1)
    }
    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        graphics.color = if (component.isEnabled && component.hasFocus()) ForestTheme.ACCENT else ForestTheme.DIVIDER
        graphics.drawRect(x, y, width - 1, height - 1)
    }
}

private class ForestMetalTheme : DefaultMetalTheme() {
    override fun getName(): String = "Dark Forest"
    override fun getPrimary1(): ColorUIResource = ForestTheme.APP_BAR
    override fun getPrimary2(): ColorUIResource = ForestTheme.ACCENT
    override fun getPrimary3(): ColorUIResource = ForestTheme.SURFACE_VARIANT
    override fun getSecondary1(): ColorUIResource = ForestTheme.DIVIDER
    override fun getSecondary2(): ColorUIResource = ForestTheme.SURFACE_VARIANT
    override fun getSecondary3(): ColorUIResource = ForestTheme.SURFACE_ELEVATED
    override fun getControlTextColor(): ColorUIResource = ForestTheme.TEXT_PRIMARY
    override fun getSystemTextColor(): ColorUIResource = ForestTheme.TEXT_PRIMARY
    override fun getUserTextColor(): ColorUIResource = ForestTheme.TEXT_PRIMARY
    override fun getInactiveControlTextColor(): ColorUIResource = ForestTheme.TEXT_HINT
    override fun getWindowBackground(): ColorUIResource = ForestTheme.SURFACE_ELEVATED
    override fun getMenuBackground(): ColorUIResource = ForestTheme.SURFACE_ELEVATED
    override fun getMenuForeground(): ColorUIResource = ForestTheme.TEXT_PRIMARY
}

/** Rounded, focus-visible buttons used by ordinary controls and JOptionPane/FileChooser buttons. */
@Suppress("ACCIDENTAL_OVERRIDE") // Swing's Java UI delegates expose an inherited static createUI.
class ForestButtonUI : BasicButtonUI() {
    companion object {
        @JvmStatic fun createUI(component: JComponent): ComponentUI = ForestButtonUI()
    }

    override fun installDefaults(button: AbstractButton) {
        super.installDefaults(button)
        button.isOpaque = false
        button.isRolloverEnabled = true
    }

    override fun paint(graphics: Graphics, component: JComponent) {
        val button = component as AbstractButton
        if (button.isContentAreaFilled) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = when {
                    !button.isEnabled -> ForestTheme.SURFACE
                    ForestTheme.isPrimary(button) && button.model.isPressed -> ForestTheme.PRIMARY
                    ForestTheme.isPrimary(button) -> ForestTheme.ACCENT
                    button.model.isPressed -> ForestTheme.APP_BAR
                    button.model.isRollover -> ForestTheme.DIVIDER
                    else -> button.background
                }
                g.fillRoundRect(0, 0, component.width, component.height, 8, 8)
                if (button.hasFocus() && button.isFocusPainted) {
                    g.color = if (ForestTheme.isPrimary(button)) ForestTheme.ON_PRIMARY else ForestTheme.ACCENT
                    g.stroke = BasicStroke(1.5f)
                    g.drawRoundRect(2, 2, component.width - 5, component.height - 5, 7, 7)
                }
            } finally { g.dispose() }
        }
        super.paint(graphics, component)
    }

    override fun paintText(graphics: Graphics, component: JComponent, rect: Rectangle, text: String) {
        val button = component as AbstractButton
        graphics.font = button.font
        graphics.color = if (button.isEnabled) button.foreground else ForestTheme.TEXT_HINT
        val metrics = graphics.getFontMetrics(button.font)
        BasicGraphicsUtils.drawStringUnderlineCharAt(
            graphics, text, button.displayedMnemonicIndex, rect.x, rect.y + metrics.ascent)
    }
}

/** Small, high-contrast checkbox/radio artwork; the controls retain their standard Swing behavior. */
private class ForestChoiceIcon(private val radio: Boolean) : Icon {
    override fun getIconWidth(): Int = 18
    override fun getIconHeight(): Int = 18

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val button = component as? AbstractButton ?: return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val edge = if (button.isEnabled) ForestTheme.TEXT_SECONDARY else ForestTheme.TEXT_HINT
            val selected = button.isSelected
            g.color = if (selected && button.isEnabled) ForestTheme.ACCENT else ForestTheme.SURFACE
            if (radio) g.fillOval(x + 1, y + 1, 15, 15) else g.fillRoundRect(x + 1, y + 1, 15, 15, 4, 4)
            g.color = if (selected && button.isEnabled) ForestTheme.ACCENT else edge
            g.stroke = BasicStroke(1.5f)
            if (radio) g.drawOval(x + 1, y + 1, 15, 15) else g.drawRoundRect(x + 1, y + 1, 15, 15, 4, 4)
            if (selected) {
                g.color = if (button.isEnabled) ForestTheme.ON_PRIMARY else ForestTheme.TEXT_HINT
                if (radio) g.fillOval(x + 5, y + 5, 7, 7) else {
                    g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(x + 4, y + 8, x + 7, y + 11)
                    g.drawLine(x + 7, y + 11, x + 13, y + 5)
                }
            }
        } finally { g.dispose() }
    }
}

/** High-contrast dialog and file-chooser icons replace Metal's dark-on-dark artwork. */
private class ForestMessageIcon(private val color: Color, private val symbol: String) : Icon {
    override fun getIconWidth(): Int = 32
    override fun getIconHeight(): Int = 32
    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color
            g.fillOval(x + 2, y + 2, 28, 28)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
            g.color = ForestTheme.ON_PRIMARY
            val metrics = g.fontMetrics
            g.drawString(symbol, x + (32 - metrics.stringWidth(symbol)) / 2,
                y + (32 - metrics.height) / 2 + metrics.ascent)
        } finally { g.dispose() }
    }
}

private class ForestFileIcon(private val kind: String) : Icon {
    override fun getIconWidth(): Int = 18
    override fun getIconHeight(): Int = 18
    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = if (component.isEnabled) ForestTheme.TEXT_PRIMARY else ForestTheme.TEXT_HINT
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (kind) {
                "home" -> {
                    g.drawLine(2, 8, 9, 2); g.drawLine(9, 2, 16, 8)
                    g.drawLine(4, 7, 4, 15); g.drawLine(4, 15, 14, 15); g.drawLine(14, 15, 14, 7)
                    g.drawRect(7, 10, 4, 5)
                }
                "list", "details" -> {
                    for (row in 0..2) {
                        val yy = 4 + row * 5
                        g.fillRect(2, yy, 2, 2); g.drawLine(6, yy + 1, if (kind == "list") 15 else 11, yy + 1)
                        if (kind == "details") g.drawLine(14, yy + 1, 16, yy + 1)
                    }
                }
                "file" -> {
                    g.drawRect(4, 2, 10, 14); g.drawLine(6, 7, 12, 7)
                    g.drawLine(6, 10, 12, 10); g.drawLine(6, 13, 10, 13)
                }
                else -> {
                    g.drawLine(2, 5, 7, 5); g.drawLine(7, 5, 9, 7); g.drawLine(9, 7, 16, 7)
                    g.drawLine(16, 7, 16, 15); g.drawLine(16, 15, 2, 15); g.drawLine(2, 15, 2, 5)
                    if (kind == "up") {
                        g.color = ForestTheme.ACCENT
                        g.drawLine(9, 12, 9, 2); g.drawLine(9, 2, 6, 5); g.drawLine(9, 2, 12, 5)
                    } else if (kind == "new") {
                        g.color = ForestTheme.ACCENT
                        g.drawLine(9, 8, 9, 14); g.drawLine(6, 11, 12, 11)
                    }
                }
            }
        } finally { g.dispose() }
    }
}

/** Basic combo rendering avoids native white popup/list surfaces on either desktop platform. */
@Suppress("ACCIDENTAL_OVERRIDE")
class ForestComboBoxUI : BasicComboBoxUI() {
    companion object {
        @JvmStatic fun createUI(component: JComponent): ComponentUI = ForestComboBoxUI()
    }

    override fun createArrowButton(): JButton = JButton(object : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12
        override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = if (component.isEnabled) ForestTheme.TEXT_SECONDARY else ForestTheme.TEXT_HINT
                g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(x + 2, y + 4, x + 6, y + 8)
                g.drawLine(x + 6, y + 8, x + 10, y + 4)
            } finally { g.dispose() }
        }
    }).apply {
        isFocusable = false
        border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
        background = ForestTheme.SURFACE
    }
}

/** Thin dark scrollbars rather than the platform's light native troughs. */
@Suppress("ACCIDENTAL_OVERRIDE")
class ForestScrollBarUI : BasicScrollBarUI() {
    companion object {
        @JvmStatic fun createUI(component: JComponent): ComponentUI = ForestScrollBarUI()
    }

    override fun createDecreaseButton(orientation: Int): JButton = emptyButton()
    override fun createIncreaseButton(orientation: Int): JButton = emptyButton()
    private fun emptyButton(): JButton = JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }

    override fun paintTrack(graphics: Graphics, component: JComponent, bounds: Rectangle) {
        graphics.color = component.background
        graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override fun paintThumb(graphics: Graphics, component: JComponent, bounds: Rectangle) {
        if (bounds.isEmpty || !component.isEnabled) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = if (isThumbRollover) ForestTheme.TEXT_HINT else ForestTheme.DIVIDER
            val vertical = (component as JScrollBar).orientation == SwingConstants.VERTICAL
            val x = bounds.x + if (vertical) 3 else 2
            val y = bounds.y + if (vertical) 2 else 3
            val width = (bounds.width - if (vertical) 6 else 4).coerceAtLeast(1)
            val height = (bounds.height - if (vertical) 4 else 6).coerceAtLeast(1)
            g.fillRoundRect(x, y, width, height, 8, 8)
        } finally { g.dispose() }
    }
}
