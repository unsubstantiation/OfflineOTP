package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenFormatTest {
    @Test
    fun groupsAreAlignedToTheRight() {
        assertEquals("1 234 567", TokenFormat.groupFromRight("1234567", 3))
        assertEquals("12 34 56", TokenFormat.groupFromRight("123456", 2))
        assertEquals("12 3456", TokenFormat.groupFromRight("123456", 4))
    }

    @Test
    fun alphabeticAndLeadingZeroCodesKeepEveryCharacter() {
        assertEquals("00 0123", TokenFormat.groupFromRight("000123", 4))
        assertEquals("7B CDF", TokenFormat.groupFromRight("7BCDF", 3))
        assertEquals("a1 b2 c3", TokenFormat.groupFromRight("a1b2c3", 2))
    }

    @Test
    fun disabledGroupingAndOversizedGroupsAreSafe() {
        for (size in listOf(0, -1, Int.MIN_VALUE, 100, Int.MAX_VALUE)) {
            assertEquals("123456", TokenFormat.groupFromRight("123456", size))
        }
        assertEquals("", TokenFormat.groupFromRight("", 3))
        assertNull(TokenFormat.groupFromRight(null, 3))
    }
}
