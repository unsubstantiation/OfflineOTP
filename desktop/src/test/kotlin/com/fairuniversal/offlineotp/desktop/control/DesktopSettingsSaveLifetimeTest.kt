package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSettingsSaveLifetimeTest {
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
        override fun remove(runnable: Runnable) {}
        override fun elapsedRealtime() = now
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))

    @Test fun failedAndThrowingTimeoutWritesLockImmediatelyWhenOldDeadlineExpires() {
        for (throwing in listOf(false, true)) {
            val state = state(); val clock = Clock()
            val settings = DesktopAutoLockSettings(state, { 30 }, {
                clock.now = 30_000
                if (throwing) error("write failure") else false
            })
            val lifetime = DesktopSessionLifetime(clock, 30) { settings.close(); state.lock() }
            try {
                settings.open(); val request = settings.pending!!
                assertFalse(settings.submit(request, 0, lifetime))
                assertTrue(state.isLocked); assertTrue(settings.pending == null)
                assertFalse(request.failed) // No stale failure rendering after lock.
                assertFalse(lifetime.checkActive())
            } finally { settings.close(); lifetime.close(); state.lock() }
        }
    }

    @Test fun failedAndThrowingLayoutWritesAlsoEnforceOldDeadline() {
        for (throwing in listOf(false, true)) {
            val state = state(); val clock = Clock(); var applies = 0
            val settings = DesktopLayoutSettings(state, { CardLayouts.DEFAULT }, {
                clock.now = 30_000
                if (throwing) error("write failure") else false
            })
            val lifetime = DesktopSessionLifetime(clock, 30) { settings.close(); state.lock() }
            try {
                settings.open(); val request = settings.pending!!
                assertFalse(settings.submit(request, CardLayouts.FULL, lifetime::checkActive) { applies++ })
                assertTrue(state.isLocked); assertTrue(settings.pending == null)
                assertFalse(request.failed); assertEquals(0, applies)
            } finally { settings.close(); lifetime.close(); state.lock() }
        }
    }

    @Test fun replacedTimeoutRequestCannotDisableTimerOrClearNewDialog() {
        for (saved in listOf(false, true)) {
            val state = state(); val clock = Clock()
            lateinit var settings: DesktopAutoLockSettings
            var replacement: DesktopAutoLockSettings.Request? = null
            settings = DesktopAutoLockSettings(state, { 30 }, {
                settings.open(); replacement = settings.pending; saved
            })
            val lifetime = DesktopSessionLifetime(clock, 30) { state.lock() }
            try {
                settings.open(); val old = settings.pending!!
                clock.now = 20_000
                assertFalse(settings.submit(old, 0, lifetime))
                assertTrue(settings.pending === replacement); assertFalse(old.failed)
                clock.now = 30_000
                assertFalse(lifetime.checkActive()) // Not extended/reset/disabled by stale completion.
            } finally { settings.close(); lifetime.close(); state.lock() }
        }
    }

    @Test fun replacedLayoutRequestCannotApplyOrClearNewDialog() {
        for (saved in listOf(false, true)) {
            val state = state(); var applies = 0
            lateinit var settings: DesktopLayoutSettings
            var replacement: DesktopLayoutSettings.Request? = null
            settings = DesktopLayoutSettings(state, { CardLayouts.DEFAULT }, {
                settings.open(); replacement = settings.pending; saved
            })
            try {
                settings.open(); val old = settings.pending!!
                assertFalse(settings.submit(old, CardLayouts.FULL, { true }) { applies++ })
                assertTrue(settings.pending === replacement); assertFalse(old.failed); assertEquals(0, applies)
            } finally { settings.close(); state.lock() }
        }
    }

    @Test fun reentrantLayoutGuardCannotAuthorizeDismissedRequestBeforeOrAfterWrite() {
        for (dismissAt in 1..2) {
            val state = state(); var writes = 0; var checks = 0; var applies = 0
            val settings = DesktopLayoutSettings(state, { CardLayouts.DEFAULT }, { writes++; true })
            try {
                settings.open(); val request = settings.pending!!
                assertFalse(settings.submit(request, CardLayouts.FULL, {
                    if (++checks == dismissAt) settings.dismiss(request)
                    true
                }) { applies++ })
                assertEquals(dismissAt - 1, writes); assertEquals(0, applies)
                assertTrue(settings.pending == null)
            } finally { settings.close(); state.lock() }
        }
    }

    @Test fun successfulTimeoutWriteThatClosesOwnerDoesNotChangeLiveDeadline() {
        val state = state(); val clock = Clock()
        lateinit var settings: DesktopAutoLockSettings
        settings = DesktopAutoLockSettings(state, { 30 }, { settings.close(); true })
        val lifetime = DesktopSessionLifetime(clock, 30) { state.lock() }
        try {
            settings.open(); val request = settings.pending!!
            assertFalse(settings.submit(request, 0, lifetime))
            clock.now = 30_000
            assertFalse(lifetime.checkActive()); assertTrue(state.isLocked)
        } finally { settings.close(); lifetime.close(); state.lock() }
    }
}
