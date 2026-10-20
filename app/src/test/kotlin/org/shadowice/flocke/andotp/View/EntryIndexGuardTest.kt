package org.shadowice.flocke.andotp.View

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryIndexGuardTest {
    @Test
    fun acceptsOnlyIndexesInsideTheCurrentList() {
        assertFalse(EntryIndexGuard.isValid(-1, 1))
        assertFalse(EntryIndexGuard.isValid(0, 0))
        assertTrue(EntryIndexGuard.isValid(0, 1))
        assertTrue(EntryIndexGuard.isValid(2, 3))
        assertFalse(EntryIndexGuard.isValid(3, 3))
    }
}
