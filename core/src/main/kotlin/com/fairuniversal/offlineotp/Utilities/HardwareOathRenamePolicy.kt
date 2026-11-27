package com.fairuniversal.offlineotp.Utilities

import java.nio.charset.StandardCharsets

/** YKOATH names only; never changes secret, counter, algorithm, digits, type or period. */
object HardwareOathRenamePolicy {
    class Names(val account: String, val issuer: String?, val identifier: String) {
        override fun toString() = "HardwareOathRenameNames(redacted)"
    }

    fun names(counterBased: Boolean, period: Int, issuer: String, account: String): Names? {
        if (account.isEmpty() || account.length > InputPolicy.MAX_TEXT_CHARS || issuer.length > InputPolicy.MAX_TEXT_CHARS ||
            (!counterBased && period !in 1..InputPolicy.MAX_PERIOD_SECONDS) || ':' in issuer ||
            (issuer.isEmpty() && ':' in account)) return null
        if ((account + issuer).any { it.isISOControl() || it == '\u2028' || it == '\u2029' ||
                Character.getType(it) == Character.FORMAT.toInt() }) return null
        var name = account
        var source = issuer.takeIf { it.isNotEmpty() }
        // A default-period name such as 60/alice must not become a 60-second credential.
        if (!counterBased && period == 30 && Regex("^[0-9]+/").containsMatchIn(source ?: name)) {
            if (source == null) name = "30/$name" else source = "30/$source"
        }
        val id = (if (!counterBased && period != 30) "$period/" else "") +
            (source?.let { "$it:" } ?: "") + name
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(id) || id.toByteArray(StandardCharsets.UTF_8).size > 64) return null
        return Names(name, source, id)
    }
}
