package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout
import com.fairuniversal.offlineotp.ui.portable.PortableLabelDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAppearanceTest {
    @Test
    fun truncatedLabelsFollowTheSelectedLayout() {
        assertEquals(1, VaultAppearance().labelMaxLines)
        assertEquals(1, VaultAppearance(cardLayout = Constants.CardLayouts.COMPACT).labelMaxLines)
        assertEquals(2, VaultAppearance(cardLayout = Constants.CardLayouts.FULL).labelMaxLines)
    }

    @Test
    fun multilineAllowsTenLinesAndScrollUsesASingleMarqueeLine() {
        for (layout in Constants.CardLayouts.entries) {
            val multiline = VaultAppearance(cardLayout = layout, labelDisplay = Constants.LabelDisplay.MULTILINE)
            assertEquals(10, multiline.labelMaxLines)
            assertFalse(multiline.scrollLabels)
            val scroll = multiline.copy(labelDisplay = Constants.LabelDisplay.SCROLL)
            assertEquals(1, scroll.labelMaxLines)
            assertTrue(scroll.scrollLabels)
        }
    }

    @Test
    fun fullStacksTheCodeAndCompactReducesPadding() {
        assertFalse(VaultAppearance().codeBelowIdentity)
        assertTrue(VaultAppearance(cardLayout = Constants.CardLayouts.FULL).codeBelowIdentity)
        assertEquals(8, VaultAppearance(cardLayout = Constants.CardLayouts.COMPACT).cardVerticalPaddingDp)
        assertEquals(12, VaultAppearance().cardVerticalPaddingDp)
    }

    @Test
    fun appearanceSharesLayoutGeometryWithPortableCardLayout() {
        for (layout in Constants.CardLayouts.entries) {
            val portable = PortableCardLayout.valueOf(layout.name)
            for (mode in Constants.LabelDisplay.entries) {
                val portableMode = PortableLabelDisplay.valueOf(mode.name)
                val appearance = VaultAppearance(cardLayout = layout, labelDisplay = mode)
                assertEquals(portable.cardVerticalPaddingDp, appearance.cardVerticalPaddingDp)
                assertEquals(portable.codeBelowIdentity, appearance.codeBelowIdentity)
                assertEquals(portableMode.labelMaxLines(portable), appearance.labelMaxLines)
                assertEquals(portableMode.scrollLabels, appearance.scrollLabels)
            }
        }
    }
}
