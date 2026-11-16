package com.fairuniversal.offlineotp.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryDragTargetTest {
    private val order = listOf(11L, 22L, 33L)
    private val visible = listOf(EntryDragBounds(11, 0f, 80f), EntryDragBounds(22, 88f, 160f), EntryDragBounds(33, 256f, 100f))
    @Test fun holdsUntilNeighborMidpointIsCrossed() {
        assertEquals(0, entryDragDirection(22, 40f, order, visible))
        assertEquals(-1, entryDragDirection(22, 39f, order, visible))
        assertEquals(0, entryDragDirection(22, 306f, order, visible))
        assertEquals(1, entryDragDirection(22, 307f, order, visible))
    }
    @Test fun boundariesMissingIdentityAndOffscreenNeighborsCannotMove() {
        assertEquals(0, entryDragDirection(11, -100f, order, visible))
        assertEquals(0, entryDragDirection(33, 900f, order, visible))
        assertEquals(0, entryDragDirection(99, 900f, order, visible))
        assertEquals(0, entryDragDirection(22, 900f, order, visible.take(2)))
    }
    @Test fun oneAdjacentStepEvenWhenPointerMovesAcrossMultipleRows() {
        assertEquals(1, entryDragDirection(11, 900f, order, visible))
        assertEquals(-1, entryDragDirection(33, -900f, order, visible))
    }
    @Test fun visibleOrderDoesNotReplaceAuthoritativeOrder() {
        assertEquals(1, entryDragDirection(22, 307f, order, visible.reversed()))
    }
}
