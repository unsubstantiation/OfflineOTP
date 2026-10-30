package org.shadowice.flocke.andotp.desktop.ui

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** The same artwork used by the native Windows and Linux app images. */
internal object DesktopIcon {
    fun load(): BufferedImage = checkNotNull(
        ImageIO.read(checkNotNull(DesktopIcon::class.java.getResource("/offlineotp.png")) {
            "Missing desktop application icon"
        })
    ) { "Invalid desktop application icon" }
}
