package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** Default refresh interval: one frame per second of token life. */
const val TOKEN_TICK_MILLIS: Long = 1_000L

/**
 * A clock sample that re-emits once per [tickMillis], counted from [anchorMillis].
 *
 * The host supplies the anchor, because only platform code can read a wall clock: this module keeps
 * no `expect fun currentTimeMillis()` and therefore no per-platform actual to maintain. Elapsed time
 * then comes from [TimeSource.Monotonic] in the standard library, which cannot jump when the user
 * changes the system clock — the countdown keeps moving forward even while the codes themselves
 * follow the wall clock.
 *
 * A host keeps this at the top of the screen and rebuilds its [VaultUiState] from it, so only the
 * screen recomposes when a second passes and the projection stays a pure function of
 * (entries, filter, time). Sleeping until the next boundary rather than a fixed interval means the
 * countdown ring turns over with the code instead of drifting behind it.
 *
 * The loop is a [LaunchedEffect]: leaving the screen cancels it, so a locked or closed window runs
 * no timer at all.
 */
@Composable
fun rememberNowMillis(anchorMillis: Long, tickMillis: Long = TOKEN_TICK_MILLIS): Long {
    val start = remember { TimeSource.Monotonic.markNow() }
    var now by remember { mutableStateOf(anchorMillis) }
    LaunchedEffect(tickMillis) {
        while (true) {
            val sample = anchorMillis + start.elapsedNow().inWholeMilliseconds
            now = sample
            val remainder = sample.mod(tickMillis)
            val untilBoundary = if (remainder == 0L) tickMillis else tickMillis - remainder
            delay(untilBoundary)
        }
    }
    return now
}
