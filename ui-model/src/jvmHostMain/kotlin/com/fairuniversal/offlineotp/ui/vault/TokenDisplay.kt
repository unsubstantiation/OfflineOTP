package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.TokenFormat

/**
 * Presentation arithmetic for one-time codes, shared by every Compose host.
 *
 * Nothing here reads a clock, an entry or a setting: [remainingSeconds] and [progress] take the
 * sample time as an argument, so the layout and the countdown can be checked on a host JVM without
 * a device clock. The rules mirror what the Android card and the Swing table already do, which is
 * the point of moving them here instead of leaving one copy per client.
 */
object TokenDisplay {

    /** Longest code that is split into halves; longer secrets are shown as stored. */
    private const val MAX_GROUPED_DIGITS = 8

    /**
     * Splits a 6- to 8-digit numeric code into equal halves (`123456` -> `123 456`), the grouping
     * both clients show. Steam's five-character alphabet codes and mOTP hex digests are left alone:
     * an odd-length or non-numeric code has no natural half. An explicit [chunkSize] instead uses
     * Android's configured right-aligned grouping rule for every code type.
     */
    fun group(code: String, group: Boolean = true, chunkSize: Int? = null): String {
        if (!group) return code
        if (chunkSize != null) return TokenFormat.groupFromRight(code, chunkSize)!!
        if (code.length < 6 || code.length > MAX_GROUPED_DIGITS || code.length % 2 != 0) return code
        if (!code.all { it in '0'..'9' }) return code
        val half = code.length / 2
        return code.substring(0, half) + " " + code.substring(half)
    }

    /**
     * Whole seconds left in the current step, `1..stepSeconds`.
     *
     * The first second of a step still reports the full step (not zero), so a card never blinks
     * through an empty state, and a clock that moves backwards still lands inside the step.
     */
    fun remainingSeconds(stepSeconds: Int, nowMillis: Long): Int {
        if (stepSeconds <= 0) return 0
        val step = stepSeconds.toLong()
        val seconds = nowMillis / 1000L
        val elapsed = ((seconds % step) + step) % step
        return (step - elapsed).toInt()
    }

    /**
     * The obscured form of a code, keeping its grouping (`123 456` -> `••• •••`).
     *
     * A card starts obscured on every client: a code is only on screen because the user asked for
     * it, and a shoulder-surfer should not be able to read a live token off an untouched window.
     */
    fun mask(code: String): String = code.map { if (it == ' ') it else MASK_CHARACTER }.joinToString("")

    /** U+2022, which every monospaced font the app uses provides. */
    private const val MASK_CHARACTER = '\u2022'

    /** Remaining fraction of the step, in `(0, 1]`, for the countdown ring. */
    fun progress(stepSeconds: Int, nowMillis: Long): Float {
        if (stepSeconds <= 0) return 0f
        return remainingSeconds(stepSeconds, nowMillis).toFloat() / stepSeconds.toFloat()
    }
}
