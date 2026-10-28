package org.shadowice.flocke.andotp.desktop.control

import org.shadowice.flocke.andotp.Utilities.ScreenTimerController

/**
 * Drives the one-second token refresh of an unlocked vault through the shared
 * [ScreenTimerController]. The scheduler port is provided by the UI layer (a Swing timer on the
 * dispatch thread, or a fake in tests); this class only recomputes cached OTP values and forwards
 * the tick so the bound table model can repaint.
 */
class TokenTicker(
    scheduler: ScreenTimerController.Scheduler,
    private val state: DesktopVaultState,
    private val onTick: () -> Unit
) : AutoCloseable {

    private val timer = ScreenTimerController(
        scheduler,
        Runnable {
            state.tickTokens()
            onTick()
        },
        Runnable { }
    )

    fun start() = timer.startTicks()

    fun stop() = timer.stopTicks()

    override fun close() = timer.close()
}
