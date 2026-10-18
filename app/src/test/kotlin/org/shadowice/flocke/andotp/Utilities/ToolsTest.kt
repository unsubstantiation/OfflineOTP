/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * JVM vectors for token formatting.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ToolsTest {
    @Test
    fun formatTokenStringZeroPads() {
        assertEquals("059264", Tools.formatTokenString(59264, 6))
        assertEquals("000059264", Tools.formatTokenString(59264, 9))
        assertEquals("59264", Tools.formatTokenString(59264, 5))
        Locale.setDefault(Locale("tr"))
        try {
            assertEquals("059264", Tools.formatTokenString(59264, 6))  // always arabic numerals
        } finally {
            Locale.setDefault(Locale.ENGLISH)
        }
    }

    @Test
    fun formatTokenSplitsIntoGroups() {
        assertEquals("123 456", Tools.formatToken("123456", 3))
        assertEquals("12 34 56", Tools.formatToken("123456", 2))
        assertEquals("123456", Tools.formatToken("123456", 6))
        assertEquals("123456", Tools.formatToken("123456", 10))
    }

    @Test
    fun formatTokenToleratesHostileInput() {
        assertNull(Tools.formatToken(null, 3))
        assertEquals("123456", Tools.formatToken("123456", 0))
        assertEquals("123456", Tools.formatToken("123456", -2))
        assertEquals("", Tools.formatToken("", 3))
    }

    @Test
    fun groupingNeverProducesLeadingOrTrailingSpaces() {
        // The chunks are built from the right, so a partial group ends up first.
        val formatted = Tools.formatToken("12345678", 3)
        assertEquals(false, formatted?.startsWith(" ") == true)
        assertEquals(false, formatted?.endsWith(" ") == true)
        assertEquals("12 345 678", formatted)
    }
}
