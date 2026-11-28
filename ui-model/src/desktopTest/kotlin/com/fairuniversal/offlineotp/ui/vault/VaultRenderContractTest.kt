package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.ui.portable.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRenderContractTest {
    private fun card() = VaultCardState(7, VaultEntryKind.OTP, "Brand", "account", "thumb", token = "123 456", previousToken = "654 321")
    @Test fun concealedJvmFramesContainNoRawCurrentPreviousOrProtectedDetailText() {
        val input = card().copy(revealed = false, detailLabel = "connection", revealedContent = null)
        val content = vaultCardContent(input, VaultStrings.English, VaultAppearance(), VaultActions())
        assertEquals("••• •••", content.token); assertFalse(content.tokenVisible); assertNull(content.previousToken)
        assertFalse(content.toString().contains("123")); assertEquals("VaultCardContent(redacted)", content.toString())
    }
    @Test fun commonRendererContractMasksMalformedConcealedAdapterValuesToo() {
        val content = VaultCardContent("title", token = "123 456", previousToken = "654 321", tokenVisible = false)
        assertEquals("••• •••", content.token); assertNull(content.previousToken)
    }
    @Test fun nativeCardAdapterPreservesPrivacyCopyAndOptionalCommands() {
        val input = card().copy(detailsVisible = false, canCopyAccount = true, canCopyPassword = true, pinRequired = true)
        val actions = VaultActions(onCopyAccount = {}, onCopyPassword = {}, onEnterPin = {})
        val content = vaultCardContent(input, VaultStrings.English, VaultAppearance(), actions)
        assertFalse(content.showCodeArea); assertFalse(content.showThumbnail); assertTrue(content.tags.isEmpty())
        assertEquals(listOf("reveal"), content.actions.map { it.key })
        val normal = vaultCardContent(input.copy(detailsVisible = true), VaultStrings.English, VaultAppearance(), actions)
        assertTrue(normal.actions.map { it.key }.containsAll(listOf("copyAccount", "copyPassword", "pin")))
    }
    @Test fun editCommandsRequireRevealAndStructuredPasswordSupport() {
        val actions = VaultActions(onEditEntry = {}, onViewEmailDetails = {})
        val input = card().copy(kind = VaultEntryKind.PASSWORDS, revealed = true)
        assertFalse(vaultCardContent(input, VaultStrings.English, VaultAppearance(), actions).actions.any { it.key == "edit" })
        assertTrue(vaultCardContent(input.copy(canEditPassword = true), VaultStrings.English, VaultAppearance(), actions).actions.any { it.key == "edit" })
        val email = input.copy(kind = VaultEntryKind.EMAILS)
        assertTrue(vaultCardContent(email, VaultStrings.English, VaultAppearance(), actions).actions.any { it.key == "emailDetails" })
        assertFalse(vaultCardContent(email.copy(revealed = false), VaultStrings.English, VaultAppearance(), actions).actions.any { it.key == "emailDetails" })
    }
    @Test fun counterLongPressAndCustomPeriodLabelsSurviveTheAdapter() {
        val input = card().copy(counter = 42, revealed = true, customPeriodSeconds = 60)
        val strings = VaultStrings.English.copy(customPeriod = { "period:$it" })
        val content = vaultCardContent(input, strings, VaultAppearance(), VaultActions(onNextCounter = {}, onEditCounter = {}))
        assertEquals("counter", content.actions.single { it.key == "step" }.longPress)
        assertEquals("period:60", content.customPeriodLabel)
        assertEquals("123 456", content.token); assertEquals("654 321", content.previousToken)
    }
    @Test fun portableAndJvmFramesUseTheSameRenderedCodeAndCommandContract() {
        val legacy = PortableCard(0, "Brand", "account", "Concealed", "123 456", "654 321", "TOTP", 15,
            true, false, true, "thumb", emptyList())
        val portable = portableCardContent(legacy, PortableVaultState.EMPTY, PortableCardLayout.DEFAULT)
        val jvm = vaultCardContent(card().copy(revealed = true, copyable = true), VaultStrings.English, VaultAppearance(), VaultActions())
        assertEquals(jvm.token, portable.token); assertEquals(jvm.previousToken, portable.previousToken)
        assertEquals(jvm.actions.map { it.key }, portable.actions.filter { it.key != "edit" }.map { it.key }.reversed())
    }
    @Test fun portableConcealmentStripsPreviousCodesAndProtectedBodiesAtTheRenderBoundary() {
        val input = PortableCard(0, "Secret Brand", "", "Concealed", "123456", "654321", "TOTP", 15,
            false, false, true, "thumb", listOf("work"), detailLabel = "private", revealedContent = "body")
        val content = portableCardContent(input, PortableVaultState.EMPTY.copy(hiddenTitleEnabled = true), PortableCardLayout.FULL)
        assertEquals("Concealed", content.title); assertEquals("", content.thumbnailName)
        assertNull(content.previousToken); assertNull(content.revealedContent); assertNull(content.detailLabel)
        assertFalse(content.token!!.contains("123")); assertTrue(content.tags.isEmpty())
    }
    @Test fun dragWaitsForExactlyOneHostAdoptionAndKeepsTheSameIdentity() {
        val drag = VaultDragSession()
        assertTrue(drag.start(7, listOf(7, 8, 9)))
        assertTrue(drag.request(1, listOf(7, 8, 9))); assertTrue(drag.waiting)
        assertFalse(drag.request(1, listOf(7, 8, 9)))
        assertTrue(drag.adopt(listOf(8, 7, 9))); assertFalse(drag.waiting); assertEquals(7L, drag.held)
        assertTrue(drag.request(1, listOf(8, 7, 9)))
        assertTrue(drag.adopt(listOf(8, 9, 7)))
        assertFalse(drag.request(1, listOf(8, 9, 7)))
    }
    @Test fun unexpectedOrdersDuplicatesDeletionAndCanceledGesturesCannotIssueMoves() {
        val drag = VaultDragSession()
        assertFalse(drag.start(7, listOf(7, 7)))
        assertTrue(drag.start(7, listOf(7, 8))); assertTrue(drag.request(1, listOf(7, 8)))
        assertFalse(drag.adopt(listOf(9, 7, 8))); assertNull(drag.held)
        drag.start(7, listOf(7, 8)); assertFalse(drag.adopt(listOf(8))); assertNull(drag.held)
        drag.start(7, listOf(7, 8)); drag.cancel(); assertFalse(drag.request(1, listOf(7, 8)))
    }
    @Test fun replacingAnIndexAddressedFrameRejectsStaleCallbacksAndCloseIsTerminal() {
        val delivery = VaultFrameDelivery()
        assertFalse(delivery.current(0))
        val first = delivery.publish(); assertTrue(delivery.current(first))
        val second = delivery.publish(); assertFalse(delivery.current(first)); assertTrue(delivery.current(second))
        delivery.invalidate(); assertFalse(delivery.current(second))
        val resumed = delivery.publish(); assertTrue(delivery.current(resumed))
        delivery.close(); assertFalse(delivery.current(resumed)); assertFalse(delivery.current(delivery.publish()))
    }
}
