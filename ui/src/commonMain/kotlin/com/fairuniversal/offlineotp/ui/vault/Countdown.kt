package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Default refresh interval: one frame per second of token life. */
const val TOKEN_TICK_MILLIS: Long = VaultClock.DEFAULT_TICK_MILLIS

/**
 * Re-sample the host's wall clock on each tick, including after a clock correction or resume.
 * The delay aligns refreshes to the next boundary but never substitutes for real token time.
 * Hosts remember one [clock] per session; replacing it resets the state and restarts the effect.
 * Leaving the screen cancels the effect, so a locked or closed window runs no timer here.
 */
@Composable
fun rememberNowMillis(clock: VaultClock): Long {
    var now by remember(clock) { mutableStateOf(clock.sample().nowMillis) }
    LaunchedEffect(clock) {
        while (true) {
            val sample = clock.sample()
            now = sample.nowMillis
            delay(sample.delayMillis)
        }
    }
    return now
}
