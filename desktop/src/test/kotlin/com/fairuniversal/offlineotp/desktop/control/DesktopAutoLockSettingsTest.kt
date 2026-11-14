package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAutoLockSettingsTest {
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        val pending = LinkedHashSet<Runnable>()
        override fun elapsedRealtime() = now
        override fun postDelayed(runnable: Runnable, delayMillis: Long) { pending.add(runnable) }
        override fun remove(runnable: Runnable) { pending.remove(runnable) }
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))

    @Test
    fun openingAndCancelingDoNotWriteAndSaveUpdatesTheSameLifetime() {
        val state = state(); val clock = Clock(); var locks = 0; var saved = 30; var writes = 0
        val lifetime = DesktopSessionLifetime(clock, 30) { locks++ }
        val settings = DesktopAutoLockSettings(state, { saved }, { saved = it; writes++; true })
        try {
            settings.open(); settings.dismiss(settings.pending!!)
            assertEquals(0, writes)
            settings.open()
            assertTrue(settings.submit(settings.pending!!, 60, lifetime))
            assertEquals(60, saved)
            clock.now = 30_000
            assertTrue(lifetime.checkActive())
            clock.now = 60_000
            assertFalse(lifetime.checkActive())
            assertEquals(1, locks)
            assertFalse(lifetime.changeTimeout(0))
        } finally { settings.close(); lifetime.close(); state.lock() }
    }

    @Test
    fun failedSaveDoesNotDisableOrExtendTheExistingTimeout() {
        val state = state(); val clock = Clock(); var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 30) { locks++ }
        val settings = DesktopAutoLockSettings(state, { 30 }, { false })
        try {
            settings.open(); val request = settings.pending!!
            clock.now = 20_000
            assertFalse(settings.submit(request, 0, lifetime))
            assertTrue(request.failed)
            clock.now = 30_000
            assertFalse(lifetime.checkActive())
            assertEquals(1, locks)
        } finally { settings.close(); lifetime.close(); state.lock() }
    }

    @Test
    fun staleInvalidLockedAndOverdueRequestsCannotWrite() {
        val state = state(); val clock = Clock(); var writes = 0
        val lifetime = DesktopSessionLifetime(clock, 30) {}
        val settings = DesktopAutoLockSettings(state, { 30 }, { writes++; true })
        try {
            settings.open(); val stale = settings.pending!!
            settings.open(); val current = settings.pending!!
            settings.dismiss(stale)
            assertFalse(settings.submit(stale, 0, lifetime))
            assertFalse(settings.submit(current, -1, lifetime))
            assertFalse(settings.submit(current, 123, lifetime))
            clock.now = 30_000
            assertFalse(settings.submit(current, 0, lifetime))
            state.lock()
            assertFalse(settings.submit(current, 60, lifetime))
            settings.close(); settings.open()
            assertTrue(settings.pending == null)
            assertEquals(0, writes)
        } finally { settings.close(); lifetime.close(); state.lock() }
    }

    @Test
    fun disabledAndImportedCustomTimeoutsArePreservedAndSlowWritesCannotReviveExpiry() {
        val state = state(); val clock = Clock(); var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 0) { locks++ }
        val settings = DesktopAutoLockSettings(state, { 37 }, { clock.now += 40_000; true })
        try {
            assertTrue(clock.pending.isEmpty())
            settings.open()
            assertTrue(settings.submit(settings.pending!!, 37, lifetime))
            val staleTimer = clock.pending.single()
            settings.open()
            assertFalse(settings.submit(settings.pending!!, 0, lifetime)) // The write crosses the 37s deadline.
            assertEquals(1, locks)
            staleTimer.run()
            assertEquals(1, locks)
            assertTrue(clock.pending.isEmpty())
        } finally { settings.close(); lifetime.close(); state.lock() }
    }

    @Test
    fun rememberedOwnerDoesNotScheduleBeforeItsEffectAndStartIsIdempotent() {
        val clock = Clock(); var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 30, startImmediately = false) { locks++ }
        assertTrue(clock.pending.isEmpty())
        assertFalse(lifetime.checkActive())
        lifetime.start(); lifetime.start()
        assertEquals(1, clock.pending.size)
        lifetime.close(); lifetime.start()
        assertTrue(clock.pending.isEmpty())
        assertEquals(1, locks)
    }

    @Test
    fun preferenceSurvivesReopenAndFailedWriteRollsBackTheSettingsCache() {
        val directory = Files.createTempDirectory("compose-timeout-").toFile()
        try {
            val controller = VaultController(DesktopStorage.open(directory))
            assertTrue(controller.trySaveInactivityLockSeconds(60))
            assertEquals(60, VaultController(DesktopStorage.open(directory)).savedInactivityLockSeconds())
            assertTrue(directory.setWritable(false, false))
            assertFalse(controller.trySaveInactivityLockSeconds(0))
            assertEquals(60, controller.savedInactivityLockSeconds())
            assertFalse(controller.trySaveInactivityLockSeconds(900))
            assertEquals(60, controller.savedInactivityLockSeconds())
            assertTrue(directory.setWritable(true, false))
            assertEquals(60, VaultController(DesktopStorage.open(directory)).savedInactivityLockSeconds())
            assertTrue(controller.trySaveInactivityLockSeconds(0))
            assertEquals(0, VaultController(DesktopStorage.open(directory)).savedInactivityLockSeconds())
        } finally { directory.setWritable(true, false); directory.deleteRecursively() }
    }
}
