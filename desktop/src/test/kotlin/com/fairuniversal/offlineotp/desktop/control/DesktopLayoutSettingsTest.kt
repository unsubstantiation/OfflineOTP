package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLayoutSettingsTest {
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
    @Test fun saveAppliesOnlyAfterPersistenceAndCancelDoesNotWrite() {
        val state = state(); var writes = 0; var stored = CardLayouts.DEFAULT; var live = stored
        val settings = DesktopLayoutSettings(state, { stored }, { stored = it; writes++; true })
        try {
            settings.open(); settings.dismiss(settings.pending!!); assertEquals(0, writes)
            settings.open(); val request = settings.pending!!
            assertTrue(settings.submit(request, CardLayouts.FULL, { true }) { assertEquals(it, stored); live = it })
            assertEquals(CardLayouts.FULL, live); assertEquals(1, writes)
            assertFalse(settings.submit(request, CardLayouts.COMPACT, { true }) { live = it })
        } finally { settings.close(); state.lock() }
    }
    @Test fun failedExpiredStaleAndLockedRequestsDoNotApply() {
        val state = state(); var active = true; var apply = 0; var fail = true
        val settings = DesktopLayoutSettings(state, { CardLayouts.DEFAULT }, {
            if (fail) false else { active = false; true }
        })
        try {
            settings.open(); val old = settings.pending!!; settings.open(); val request = settings.pending!!
            assertFalse(settings.submit(old, CardLayouts.FULL, { true }) { apply++ })
            assertFalse(settings.submit(request, CardLayouts.FULL, { active }) { apply++ })
            assertTrue(request.failed)
            fail = false
            assertFalse(settings.submit(request, CardLayouts.FULL, { active }) { apply++ })
            assertEquals(0, apply)
            state.lock()
            assertFalse(settings.submit(request, CardLayouts.FULL, { true }) { apply++ })
            settings.close(); settings.open(); assertTrue(settings.pending == null)
        } finally { settings.close(); state.lock() }
    }
    @Test fun controllerReopensSavedLayoutDefaultsInvalidAndRollsBackFailedWrite() {
        val dir = Files.createTempDirectory("compose-layout-").toFile()
        val storage = DesktopStorage.open(dir)
        val controller = VaultController(storage)
        try {
            assertEquals(CardLayouts.DEFAULT, controller.savedCardLayout())
            assertTrue(controller.trySaveCardLayout(CardLayouts.COMPACT))
            assertEquals(CardLayouts.COMPACT, VaultController(DesktopStorage.open(dir)).savedCardLayout())
            storage.preferences.putString(PreferencesExport.UI_CARD_LAYOUT, "unknown")
            assertEquals(CardLayouts.DEFAULT, controller.savedCardLayout())
            assertTrue(controller.trySaveCardLayout(CardLayouts.FULL))
            dir.deleteRecursively(); dir.writeText("not a directory")
            assertFalse(controller.trySaveCardLayout(CardLayouts.COMPACT))
            assertEquals(CardLayouts.FULL, controller.savedCardLayout())
        } finally { dir.deleteRecursively() }
    }

    @Test fun hideIssuerPersistsAndAppliesTogetherWithCardLayout() {
        val dir = Files.createTempDirectory("compose-hide-issuer-").toFile()
        val storage = DesktopStorage.open(dir)
        val controller = VaultController(storage)
        val state = state()
        try {
            assertFalse(controller.savedHideIssuer())
            val settings = DesktopLayoutSettings(
                state,
                controller::savedCardLayout,
                controller::trySaveCardLayout,
                controller::savedHideIssuer,
                controller::trySaveHideIssuer
            )
            settings.open()
            val req = settings.pending!!
            assertFalse(req.hideIssuer)
            var appliedLayout = CardLayouts.DEFAULT
            var appliedHide = false
            assertTrue(settings.submit(req, CardLayouts.COMPACT, true, { true }) { layout, hide ->
                appliedLayout = layout
                appliedHide = hide
            })
            assertEquals(CardLayouts.COMPACT, appliedLayout)
            assertTrue(appliedHide)
            val reopened = VaultController(DesktopStorage.open(dir))
            assertEquals(CardLayouts.COMPACT, reopened.savedCardLayout())
            assertTrue(reopened.savedHideIssuer())
            storage.preferences.putString(PreferencesExport.UI_HIDE_ISSUER, "invalid")
            assertFalse(controller.savedHideIssuer())
            settings.close()
        } finally {
            state.lock()
            dir.deleteRecursively()
        }
    }

    @Test fun displayAndSearchSettingsPersistAndRoundTrip() {
        val dir = Files.createTempDirectory("compose-display-search-").toFile()
        val storage = DesktopStorage.open(dir)
        val controller = VaultController(storage)
        val state = state()
        try {
            assertTrue(controller.savedShowPreviousToken())
            assertEquals(null, controller.savedTokenGroupSize())
            assertEquals(
                com.fairuniversal.offlineotp.Utilities.Constants.SearchIncludes.values().toList(),
                controller.savedSearchIncludes()
            )
            assertFalse(controller.trySaveSearchIncludes(emptyList()))
            assertTrue(
                controller.trySaveSearchIncludes(
                    listOf(
                        com.fairuniversal.offlineotp.Utilities.Constants.SearchIncludes.LABEL,
                        com.fairuniversal.offlineotp.Utilities.Constants.SearchIncludes.TAGS
                    )
                )
            )
            val settings = DesktopLayoutSettings(
                state,
                controller::savedCardLayout,
                controller::trySaveCardLayout,
                controller::savedHideIssuer,
                controller::trySaveHideIssuer,
                controller::savedShowPreviousToken,
                controller::trySaveShowPreviousToken,
                controller::savedTokenGroupSize,
                controller::trySaveTokenGroupSize,
                controller::savedShowIndividualTimeouts,
                controller::trySaveShowIndividualTimeouts,
                controller::savedLabelDisplay,
                controller::trySaveLabelDisplay,
                controller::savedShowThumbnails,
                controller::trySaveShowThumbnails,
                controller::savedLabelSizeSp,
                controller::trySaveLabelSizeSp,
                controller::savedHighlightToken,
                controller::trySaveHighlightToken,
                controller::savedTapSingle,
                controller::trySaveTapSingle,
                controller::savedTapDouble,
                controller::trySaveTapDouble
            )
            assertTrue(controller.savedShowIndividualTimeouts())
            assertEquals(com.fairuniversal.offlineotp.Utilities.Constants.LabelDisplay.TRUNCATE, controller.savedLabelDisplay())
            assertTrue(controller.savedShowThumbnails())
            assertEquals(0, controller.savedLabelSizeSp())
            assertTrue(controller.savedHighlightToken())
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL, controller.savedTapSingle())
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING, controller.savedTapDouble())
            assertFalse(controller.trySaveLabelSizeSp(99))
            settings.open()
            val req = settings.pending!!
            assertTrue(req.showIndividualTimeouts)
            assertEquals(com.fairuniversal.offlineotp.Utilities.Constants.LabelDisplay.TRUNCATE, req.labelDisplay)
            assertTrue(req.showThumbnails)
            assertEquals(0, req.labelSizeSp)
            assertTrue(req.highlightToken)
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL, req.tapSingle)
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING, req.tapDouble)
            var appliedPrev = true
            var appliedGroup: Int? = 99
            var appliedIndividual = true
            var appliedLabel = com.fairuniversal.offlineotp.Utilities.Constants.LabelDisplay.TRUNCATE
            var appliedThumbs = true
            var appliedSize = 0
            var appliedHighlight = true
            var appliedSingle = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL
            var appliedDouble = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING
            assertTrue(
                settings.submit(
                    req,
                    CardLayouts.FULL,
                    true,
                    false,
                    2,
                    false,
                    com.fairuniversal.offlineotp.Utilities.Constants.LabelDisplay.SCROLL,
                    false,
                    18,
                    false,
                    com.fairuniversal.offlineotp.ui.portable.PortableTapMode.COPY,
                    com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL,
                    { true }
                ) { _, _, showPrev, groupSize, showInd, labelMode, showThumbs, sizeSp, highlight, tapSingle, tapDouble ->
                    appliedPrev = showPrev
                    appliedGroup = groupSize
                    appliedIndividual = showInd
                    appliedLabel = labelMode
                    appliedThumbs = showThumbs
                    appliedSize = sizeSp
                    appliedHighlight = highlight
                    appliedSingle = tapSingle
                    appliedDouble = tapDouble
                }
            )
            assertFalse(appliedPrev)
            assertEquals(2, appliedGroup)
            assertFalse(appliedIndividual)
            assertEquals(com.fairuniversal.offlineotp.Utilities.Constants.LabelDisplay.SCROLL, appliedLabel)
            assertFalse(appliedThumbs)
            assertEquals(18, appliedSize)
            assertFalse(appliedHighlight)
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.COPY, appliedSingle)
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL, appliedDouble)

            val reopened = VaultController(DesktopStorage.open(dir))
            assertEquals(CardLayouts.FULL, reopened.savedCardLayout())
            assertTrue(reopened.savedHideIssuer())
            assertFalse(reopened.savedShowPreviousToken())
            assertFalse(reopened.savedShowIndividualTimeouts())
            assertEquals(com.fairuniversal.offlineotp.Utilities.Constants.LabelDisplay.SCROLL, reopened.savedLabelDisplay())
            assertFalse(reopened.savedShowThumbnails())
            assertEquals(18, reopened.savedLabelSizeSp())
            assertFalse(reopened.savedHighlightToken())
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.COPY, reopened.savedTapSingle())
            assertEquals(com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL, reopened.savedTapDouble())
            assertEquals(2, reopened.savedTokenGroupSize())
            assertEquals(
                listOf(
                    com.fairuniversal.offlineotp.Utilities.Constants.SearchIncludes.LABEL,
                    com.fairuniversal.offlineotp.Utilities.Constants.SearchIncludes.TAGS
                ),
                reopened.savedSearchIncludes()
            )
            assertTrue(reopened.trySaveTokenGroupSize(null))
            assertEquals(null, reopened.savedTokenGroupSize())
            settings.close()
        } finally {
            state.lock()
            dir.deleteRecursively()
        }
    }
}
