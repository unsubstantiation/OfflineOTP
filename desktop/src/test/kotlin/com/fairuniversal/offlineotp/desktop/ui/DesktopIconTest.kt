package com.fairuniversal.offlineotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopIconTest {
    @Test
    fun applicationIconIsBundledAndDecodableHeadlessly() {
        val image = DesktopIcon.load()
        assertEquals(256, image.width)
        assertEquals(256, image.height)
    }
}
