package com.fairuniversal.offlineotp.ui.vault

/** Presentation only: never rounds or changes a stored timeout. */
object AutoLockOptions {
    val seconds = listOf(0, 30, 60, 120, 300, 900)
    fun including(current: Int): List<Int> = (seconds + current.coerceAtLeast(0)).distinct()
    fun label(seconds: Int): String = when {
        seconds <= 0 -> "Disabled"
        seconds == 60 -> "1 minute"
        seconds % 60 == 0 -> "${seconds / 60} minutes"
        seconds == 1 -> "1 second"
        else -> "$seconds seconds"
    }
}
