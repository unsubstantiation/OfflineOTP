package com.fairuniversal.offlineotp.ui.vault

/** One wall-clock sample and the positive delay to its next refresh boundary. */
data class VaultClockSample(val nowMillis: Long, val delayMillis: Long)

/**
 * The host supplies wall time; the shared UI never extrapolates token time from an unlock anchor.
 * Sample again after every delay so clock corrections and suspend/resume affect the next frame.
 * One read per sample keeps the frame and its boundary calculation consistent. A coroutine delay
 * is only pacing, not the time authority for OTP generation.
 */
class VaultClock(
    private val readMillis: () -> Long,
    val tickMillis: Long = DEFAULT_TICK_MILLIS
) {
    init { require(tickMillis > 0L) { "Tick interval must be positive" } }

    fun sample(): VaultClockSample {
        val now = readMillis()
        // mod (unlike %) is non-negative for negative timestamps. Subtraction cannot overflow:
        // the remainder is in [0, tickMillis), and an exact boundary waits a full tick.
        return VaultClockSample(now, tickMillis - now.mod(tickMillis))
    }

    companion object {
        const val DEFAULT_TICK_MILLIS: Long = 1_000L
    }
}
