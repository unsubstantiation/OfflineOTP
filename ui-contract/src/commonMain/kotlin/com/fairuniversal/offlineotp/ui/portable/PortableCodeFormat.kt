package com.fairuniversal.offlineotp.ui.portable

/** Clock-free display policy for validated ASCII OTP values. No native entry or date types. */
object PortableCodeFormat {
    fun grouped(code: String, groupSize: Int): String =
        if (groupSize <= 0 || code.length <= groupSize) code else code.chunked(groupSize).joinToString(" ")

    fun spoken(code: String): String = code.toCharArray().joinToString(" ")

    fun remainingDescription(seconds: Int?): String = when (seconds) {
        null -> ""
        1 -> "1 second left"
        else -> "$seconds seconds left"
    }

    fun stepSeconds(kind: String, period: Int): Int? = when (kind) {
        "TOTP", "STEAM" -> period.takeIf { it > 0 }
        "MOTP" -> 10
        else -> null
    }

    fun secondsRemaining(kind: String, period: Int, unixSeconds: Long): Int? {
        if (period <= 0) return null
        val step = stepSeconds(kind, period) ?: return null
        // Normalized remainder handles clock samples before the Unix epoch too.
        val elapsed = ((unixSeconds % step) + step) % step
        return step - elapsed.toInt()
    }

    fun fractionRemaining(kind: String, period: Int, unixSeconds: Long): Double {
        val remaining = secondsRemaining(kind, period, unixSeconds) ?: return 0.0
        val step = stepSeconds(kind, period) ?: return 0.0
        return (remaining.toDouble() / step).coerceIn(0.0, 1.0)
    }
}

/** Native pasteboards execute this bounded expiry policy; expired results never get copied. */
object PortableClipboardPolicy {
    const val expirySeconds: Double = 30.0

    /** NaN/infinite provider timestamps are refused rather than producing unbounded retention. */
    fun expiry(nowEpochSeconds: Double, requestedExpiryEpochSeconds: Double): Double? {
        if (!nowEpochSeconds.isFinite() || !requestedExpiryEpochSeconds.isFinite()) return null
        val expiry = minOf(nowEpochSeconds + expirySeconds, requestedExpiryEpochSeconds)
        return expiry.takeIf { it.isFinite() && it > nowEpochSeconds }
    }
}
