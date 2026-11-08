package com.fairuniversal.offlineotp.desktop.compose

import com.fairuniversal.offlineotp.desktop.control.ExpiringClipboard
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** Native clipboard operations; availability errors are handled by the session-owned lease. */
internal object AwtClipboard : ExpiringClipboard.Port {
    override fun read(): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        return if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
            clipboard.getData(DataFlavor.stringFlavor) as? String else null
    }
    override fun write(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
