package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import com.fairuniversal.offlineotp.ui.portable.*
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopCardDisplayAtomicTest {
    private fun selection() = PortableCardDisplaySelection(PortableCardLayout.COMPACT, true, false, 2,
        false, PortableLabelDisplay.MULTILINE, false, 20, false, PortableTapMode.COPY,
        PortableTapMode.REVEAL, true, 60, 48, true, true)

    @Test fun completeSelectionReopensAndInvalidValuesDoNotPartiallyWrite() {
        val dir = Files.createTempDirectory("card-atomic-").toFile()
        try {
            val storage = DesktopStorage.open(dir); val controller = VaultController(storage)
            storage.preferences.putString("auth_salt", "synthetic-preserved")
            assertTrue(controller.trySaveCardDisplaySelection(selection()))
            val file = java.io.File(dir, DesktopStorage.SETTINGS_FILE)
            val before = file.readBytes()
            assertFalse(controller.trySaveCardDisplaySelection(selection().copy(labelSizeSp = 99)))
            assertFalse(controller.trySaveCardDisplaySelection(selection().copy(tokenGroupSize = 4)))
            assertTrue(before.contentEquals(file.readBytes()))
            val reopened = VaultController(DesktopStorage.open(dir))
            assertEquals(20, reopened.savedLabelSizeSp()); assertEquals(48, reopened.savedThumbnailSizeDp())
            assertEquals(PortableTapMode.COPY, reopened.savedTapSingle()); assertEquals(60, reopened.savedTapToRevealTimeoutSeconds())
            assertTrue(reopened.savedHideIssuer()); assertTrue(reopened.savedMinimizeOnCopy())
            assertEquals("synthetic-preserved", storage.preferences.getString("auth_salt", ""))
            assertTrue(controller.trySaveCardDisplaySelection(selection().copy(tokenGroupSize = null)))
            assertNull(controller.savedTokenGroupSize())
        } finally { dir.deleteRecursively() }
    }
    @Test fun diskFailureLeavesCachedSelectionUnchanged() {
        val dir = Files.createTempDirectory("card-atomic-failure-").toFile()
        try {
            val controller = VaultController(DesktopStorage.open(dir))
            assertTrue(controller.trySaveCardDisplaySelection(selection()))
            dir.deleteRecursively(); dir.writeText("block writes")
            assertFalse(controller.trySaveCardDisplaySelection(selection().copy(hideIssuer = false, labelSizeSp = 14)))
            assertTrue(controller.savedHideIssuer()); assertEquals(20, controller.savedLabelSizeSp())
        } finally { dir.deleteRecursively() }
    }
    @Test fun atomicCallbackNeverFallsBackToIndividualWritesAndChecksLifetimeAfterCommit() {
        val state = DesktopVaultState(javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES"))
        var fail = true; var batches = 0; var applied = 0
        val editor = DesktopLayoutSettings(state, { com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts.DEFAULT },
            { error("individual write") }, writeSelection = { batches++; !fail })
        try {
            editor.open(); val request = editor.pending!!
            assertFalse(editor.submit(request, selection(), { true }) { applied++ })
            assertTrue(request.failed); assertEquals(1, batches); assertEquals(0, applied)
            fail = false
            assertTrue(editor.submit(request, selection(), { true }) { applied++ })
            assertEquals(2, batches); assertEquals(1, applied)
            assertFalse(editor.submit(request, selection(), { true }) { applied++ })
            assertEquals(2, batches)
        } finally { editor.close(); state.lock() }
    }
    @Test fun autoLockBatchReopensTogetherAndKeepsOldPolicyOnFailedWrite() {
        val dir = Files.createTempDirectory("lock-atomic-failure-").toFile()
        try {
            val controller = VaultController(DesktopStorage.open(dir))
            assertTrue(controller.trySaveAutoLockSettings(45, true))
            val reopened = VaultController(DesktopStorage.open(dir))
            assertEquals(45, reopened.savedInactivityLockSeconds()); assertTrue(reopened.savedRelockOnBackground())
            assertFalse(controller.trySaveAutoLockSettings(-1, false))
            dir.deleteRecursively(); dir.writeText("block writes")
            assertFalse(controller.trySaveAutoLockSettings(0, false))
            assertEquals(45, controller.savedInactivityLockSeconds()); assertTrue(controller.savedRelockOnBackground())
        } finally { dir.deleteRecursively() }
    }

}
