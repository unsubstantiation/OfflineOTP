package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFrameHostTest {
    private val card = VaultCardState(1L, VaultEntryKind.OTP, "Bank", "alice", "Default",
        token = "123 456", copyable = true)
    private val frame = VaultUiState(cards = listOf(card), totalEntries = 1)

    private fun invokeAll(actions: VaultActions) {
        actions.onCopyToken(card)
        actions.onToggleReveal(card)
        actions.onNextCounter!!.invoke(card)
        actions.onEntryActions!!.invoke(card)
        actions.onEnterPin!!.invoke(card)
        actions.onDelete!!.invoke(card)
        actions.onQueryChange("alice")
        actions.onSelectCategory(EntryListPresentation.Category.OTP)
        actions.onToggleTag("work")
        actions.onToggleUntagged()
        actions.onClearFilters()
        actions.onSelectSort(Constants.SortMode.ISSUER)
        actions.onLock()
    }

    @Test
    fun callbacksRequireAPublishedFrameAndStopPermanentlyOnClose() {
        var calls = 0
        val host = VaultFrameHost(VaultActions(
            onCopyToken = { calls++ }, onToggleReveal = { calls++ },
            onNextCounter = { calls++ }, onEntryActions = { calls++ },
            onEnterPin = { calls++ }, onDelete = { calls++ },
            onQueryChange = { calls++ }, onSelectCategory = { calls++ },
            onToggleTag = { calls++ }, onToggleUntagged = { calls++ },
            onClearFilters = { calls++ }, onSelectSort = { calls++ }, onLock = { calls++ }
        ))
        val retained = host.actions
        invokeAll(retained)
        assertEquals(0, calls)
        assertTrue(host.render(frame))
        invokeAll(retained)
        assertEquals(13, calls)
        host.close()
        invokeAll(retained)
        assertEquals(13, calls)
        assertFalse(host.render(frame))
        assertTrue(host.frame.cards.isEmpty())
        assertEquals(0, host.frame.totalEntries)
    }

    @Test
    fun closeBeforeFirstRenderRejectsLateWorkAndIsIdempotent() {
        val host = VaultFrameHost(VaultActions())
        host.close()
        host.close()
        assertFalse(host.render(frame))
        assertTrue(host.frame.isEmpty)
    }

    @Test
    fun optionalCapabilitiesRemainAbsentThroughTheGuard() {
        val host = VaultFrameHost(VaultActions())
        assertNull(host.actions.onNextCounter)
        assertNull(host.actions.onEntryActions)
        assertNull(host.actions.onEnterPin)
        assertNull(host.actions.onDelete)
    }

    @Test
    fun newSessionDoesNotReactivateOldCallbacks() {
        var copies = 0
        val delegate = VaultActions(onCopyToken = { copies++ })
        val old = VaultFrameHost(delegate)
        old.render(frame)
        old.close()
        val fresh = VaultFrameHost(delegate)
        fresh.render(frame)
        old.actions.onCopyToken(card)
        assertEquals(0, copies)
        fresh.actions.onCopyToken(card)
        assertEquals(1, copies)
    }
}
