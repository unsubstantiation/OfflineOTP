package org.shadowice.flocke.andotp.Utilities

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.oath.Credential
import com.yubico.yubikit.oath.CredentialData
import com.yubico.yubikit.oath.HashAlgorithm
import com.yubico.yubikit.oath.OathSession
import com.yubico.yubikit.oath.OathType
import org.shadowice.flocke.andotp.Database.Entry
import java.nio.charset.StandardCharsets

/** Accesses OATH-app-compatible smart cards without copying their stored secrets into the vault. */
internal object HardwareOathClient {
    internal class Account(val credential: Credential, val code: String?, val validUntil: Long,
                           val validFrom: Long = 0) {
        fun usableAt(time: Long): Boolean = code != null &&
            (credential.oathType == OathType.HOTP || time >= validFrom && time < validUntil)
        override fun toString(): String = "HardwareOathAccount(redacted)"
    }
    internal class Accounts(val deviceId: String, val items: List<Account>)

    fun list(device: YubiKeyDevice, password: CharArray?, permitted: () -> Boolean): Accounts =
        withSession(device, password, permitted) { session ->
        if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
        val codes = session.calculateCodes()
        if (codes.size > InputPolicy.MAX_ENTRIES) throw IllegalArgumentException("Too many hardware accounts")
        Accounts(session.deviceId, codes.entries.map {
            Account(it.key, it.value?.value, it.value?.validUntil ?: 0, it.value?.validFrom ?: 0)
        }
            .sortedWith(compareBy({ it.credential.issuer.orEmpty().lowercase() },
                { it.credential.accountName.lowercase() })))
    }

    /** HOTP calculation advances the hardware counter and must only follow explicit user action. */
    fun calculate(device: YubiKeyDevice, password: CharArray?, deviceId: String, credential: Credential,
                  permitted: () -> Boolean): Account = withSession(device, password, permitted) { session ->
            if (session.deviceId != deviceId) throw IllegalArgumentException("Different hardware key")
            // HOTP increments its counter on the key; do not issue it after cancellation.
            if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
            val code = session.calculateCode(credential)
            Account(credential, code.value, code.validUntil, code.validFrom)
        }

    /** True only when the account's OATH parameters and name can be represented without ambiguity. */
    fun canCopy(entry: Entry): Boolean {
        val type = entry.getType()
        return (type == Entry.OTPType.TOTP || type == Entry.OTPType.HOTP) &&
            entry.getDigits() in 6..8 && entry.getPeriod() in 1..InputPolicy.MAX_PERIOD_SECONDS &&
            (type != Entry.OTPType.HOTP || entry.getCounter() in 0..Int.MAX_VALUE.toLong()) && names(entry) != null
    }

    /** Copies an existing vault entry; never overwrites a credential with the same hardware ID. */
    fun copyToKey(device: YubiKeyDevice, password: CharArray?, entry: Entry,
                  requireTouch: Boolean, permitted: () -> Boolean) {
        entry.validate()
        if (!canCopy(entry)) throw IllegalArgumentException("Unsupported account parameters or name")
        val type = if (entry.getType() == Entry.OTPType.HOTP) OathType.HOTP else OathType.TOTP
        val (accountName, issuer) = checkNotNull(names(entry))
        val seed = entry.getSecret() ?: throw IllegalArgumentException("Missing secret")
        try {
            val counter = if (type == OathType.HOTP) entry.getCounter().toInt() else 0
            val data = CredentialData(accountName, type, HashAlgorithm.valueOf(entry.getAlgorithm().name),
                seed, entry.getDigits(), entry.getPeriod(), counter, issuer)
            try {
                withSession(device, password, permitted) { session ->
                    if (session.credentials.any { it.id.contentEquals(data.id) })
                        throw IllegalArgumentException("An account with this hardware ID already exists")
                    if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
                    session.putCredential(data, requireTouch)
                }
            } finally { data.destroy() }
        } finally { seed.fill(0) }
    }

    /** YKOATH IDs encode period/issuer/name in at most 64 UTF-8 bytes (YKOATH PUT). */
    private fun names(entry: Entry): Pair<String, String?>? {
        var account = entry.getLabel()
        var issuer: String? = entry.getIssuer().takeIf { it.isNotEmpty() }
        if (account.isEmpty() || issuer?.contains(':') == true || (issuer == null && ':' in account)) return null
        if ((account + issuer.orEmpty()).any {
                it.isISOControl() || it == '\u2028' || it == '\u2029' ||
                    Character.getType(it) == Character.FORMAT.toInt()
            }) return null

        // YubiKit omits the default 30-second prefix, but an ID beginning "60/" would then
        // be parsed as a 60-second credential. Explicitly encode 30 in that case.
        if (entry.getType() == Entry.OTPType.TOTP && entry.getPeriod() == 30 &&
            LEADING_PERIOD.containsMatchIn(issuer ?: account)) {
            if (issuer == null) account = "30/$account" else issuer = "30/$issuer"
        }
        val id = buildString {
            if (entry.getType() == Entry.OTPType.TOTP && entry.getPeriod() != 30)
                append(entry.getPeriod()).append('/')
            if (issuer != null) append(issuer).append(':')
            append(account)
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(id) ||
            id.toByteArray(StandardCharsets.UTF_8).size > 64) return null
        return account to issuer
    }

    private val LEADING_PERIOD = Regex("^[0-9]+/")

    private fun <T> withSession(device: YubiKeyDevice, password: CharArray?, permitted: () -> Boolean,
                                action: (OathSession) -> T): T {
        if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
        val connection = device.openConnection(SmartCardConnection::class.java)
        // If session construction fails, close the connection even before it has an owner.
        val session = try { OathSession(connection) } catch (error: Exception) {
            connection.close()
            throw error
        }
        session.use {
            if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
            if (it.isLocked) {
                if (password == null) throw IllegalArgumentException("Hardware OATH authentication required")
                if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
                if (!it.unlock(password)) throw IllegalArgumentException("Hardware OATH authentication required")
            }
            if (!permitted()) throw IllegalStateException("Hardware operation was canceled")
            return action(it)
        }
    }
}
