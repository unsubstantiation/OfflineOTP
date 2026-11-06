package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController

/**
 * Drives the one-second token refresh of an unlocked vault through the shared
 * [ScreenTimerController]. The scheduler port is provided by the UI layer (a Swing timer on the
 * dispatch thread, or a fake in tests); this class only recomputes cached OTP values and forwards
 * the tick so the bound table model can repaint.
 */
class TokenTicker(
    scheduler: ScreenTimerController.Scheduler,
    private val state: DesktopVaultState,
    private val onInactivity: () -> Unit,
    private val onTick: () -> Unit
) : AutoCloseable {

    constructor(
        scheduler: ScreenTimerController.Scheduler,
        state: DesktopVaultState,
        onTick: () -> Unit
    ) : this(scheduler, state, {}, onTick)

    private val timer = ScreenTimerController(
        scheduler,
        Runnable {
            state.tickTokens()
            onTick()
        },
        Runnable { onInactivity() }
    )

    fun start() = timer.startTicks()

    fun stop() = timer.stopTicks()

    fun restartInactivity(delayMillis: Long) = timer.restartInactivity(delayMillis)

    fun cancelInactivity() = timer.cancelInactivity()

    override fun close() = timer.close()
}
