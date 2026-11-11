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
}
