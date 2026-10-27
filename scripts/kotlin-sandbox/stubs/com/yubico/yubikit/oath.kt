package com.yubico.yubikit.oath

import com.yubico.yubikit.core.smartcard.SmartCardConnection

enum class OathType { TOTP, HOTP }
enum class HashAlgorithm { SHA1, SHA256, SHA512 }
class Credential(val id: ByteArray = byteArrayOf(), val oathType: OathType = OathType.TOTP,
                 val issuer: String? = null, val accountName: String = "")
class CredentialData(accountName: String, type: OathType, algorithm: HashAlgorithm, secret: ByteArray,
                     digits: Int, period: Int, counter: Int, issuer: String?) {
    // Model YubiKit's ID formatting; this is not an OATH transport.
    val id: ByteArray = buildString {
        if (type == OathType.TOTP && period != 30) append(period).append('/')
        if (issuer != null) append(issuer).append(':')
        append(accountName)
    }.toByteArray(Charsets.UTF_8)
    private val seed = secret.clone()
    var destroyed = false
        private set
    init { OathSandbox.lastData = this }
    fun destroy() { seed.fill(0); destroyed = true }
}
class Code(val value: String, val validFrom: Long, val validUntil: Long)

/** Scripted API-shape fake, not a USB/NFC transport or an OATH implementation. */
object OathSandbox {
    var locked = false
    var unlockSucceeds = false
    var unlockCalls = 0
    var credentials: List<Credential> = emptyList()
    var codes: Map<Credential, Code?> = emptyMap()
    var calculatedCode = Code("123456", 0L, Long.MAX_VALUE)
    var writes = 0
    var calculations = 0
    var lastTouch = false
    var lastData: CredentialData? = null
    fun reset() {
        locked = false; unlockSucceeds = false; unlockCalls = 0; credentials = emptyList(); codes = emptyMap()
        calculatedCode = Code("123456", 0L, Long.MAX_VALUE)
        writes = 0; calculations = 0; lastTouch = false; lastData = null
    }
}
class OathSession(private val connection: SmartCardConnection) : AutoCloseable {
    val deviceId: String get() = "fake-device"
    val isLocked: Boolean get() = OathSandbox.locked
    val credentials: List<Credential> get() = OathSandbox.credentials
    fun unlock(password: CharArray): Boolean { OathSandbox.unlockCalls++; return OathSandbox.unlockSucceeds }
    fun calculateCodes(): Map<Credential, Code?> = OathSandbox.codes
    fun calculateCode(credential: Credential): Code {
        OathSandbox.calculations++
        return OathSandbox.calculatedCode
    }
    fun putCredential(data: CredentialData, touch: Boolean): Credential {
        OathSandbox.writes++
        OathSandbox.lastTouch = touch
        return Credential(data.id)
    }
    override fun close() { connection.close() }
}
