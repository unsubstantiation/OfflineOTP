/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the token ticker using a manually driven scheduler, so no Swing timer or
 * display is needed to verify tick re-arming, stopping and OTP refresh.
 */
package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.ScreenTimerController
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import javax.crypto.KeyGenerator
import java.util.ArrayList

class TokenTickerTest {

    private class FakeScheduler : ScreenTimerController.Scheduler {
        private val posted = HashMap<Runnable, Long>()
        var now = 10_000L

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            posted[runnable] = delayMillis
        }

        override fun remove(runnable: Runnable) {
            posted.remove(runnable)
        }

        override fun elapsedRealtime(): Long = now

        fun hasTickScheduled(): Boolean = posted.isNotEmpty()

        fun runOneTick(): Runnable? {
            val runnable = posted.keys.firstOrNull() ?: return null
            // The controller removes/repairs its own references; capture before running.
            runnable.run()
            return runnable
        }
    }

    private fun entry(): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "Issuer", "label",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun startReArmsTheTickAndEachTickRefreshesTokens() {
        val scheduler = FakeScheduler()
        val state = DesktopVaultState(KeyGenerator.getInstance("AES").generateKey())
        state.add(entry())
        var callbacks = 0
        TokenTicker(scheduler, state) { callbacks++ }.use { ticker ->
            ticker.start()
            assertTrue(scheduler.hasTickScheduled())

            scheduler.runOneTick()
            assertEquals(1, callbacks)
            assertNotNull(state.snapshot()[0].getCurrentOTP())

            // The shared controller re-arms its one-second loop after every run.
            scheduler.runOneTick()
            assertEquals(2, callbacks)

            ticker.stop()
            assertTrue(!scheduler.hasTickScheduled())
        }
    }
}
