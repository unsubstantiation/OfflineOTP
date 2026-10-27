package org.shadowice.flocke.andotp.Utilities

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.fido.client.WebAuthnClient
import com.yubico.yubikit.fido.client.clientdata.ClientDataProvider
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRequestOptions
import com.yubico.yubikit.fido.webauthn.SerializationType
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Offline CTAP2/WebAuthn PRF operations; no device-specific secret or password is persisted. */
internal object HardwareFidoClient {
    // A stable local-only RP ID. It is never resolved or contacted over the network.
    private const val RP_ID = "offlineotp.invalid"
    private val random = SecureRandom()

    internal class Enrollment(val credentialId: ByteArray, val salt: ByteArray, val prf: ByteArray) : AutoCloseable {
        override fun close() { prf.fill(0) }
        override fun toString(): String = "HardwareEnrollment(redacted)"
    }

    fun enroll(device: YubiKeyDevice, pin: CharArray?, state: CommandState, permitted: () -> Boolean): Enrollment {
        WebAuthnClient.create(device, null).use { client ->
            val options = PublicKeyCredentialCreationOptions.fromMap(mapOf(
                "rp" to mapOf("id" to RP_ID, "name" to "OfflineOTP"),
                "user" to mapOf("id" to url(randomBytes(32)), "name" to "OfflineOTP", "displayName" to "OfflineOTP"),
                "challenge" to url(randomBytes(32)),
                "pubKeyCredParams" to listOf(mapOf("type" to "public-key", "alg" to -7)),
                "authenticatorSelection" to mapOf("residentKey" to "discouraged", "userVerification" to "required"),
                "attestation" to "none",
                "extensions" to mapOf("prf" to emptyMap<String, Any>())
            ))
            if (!permitted()) throw IllegalStateException("Canceled")
            val credential = client.makeCredential(ClientDataProvider.fromHash(randomBytes(32)), options,
                RP_ID, pin, null, state)
            val extensions = credential.clientExtensionResults?.toMap(SerializationType.CBOR)
            if ((extensions?.get("prf") as? Map<*, *>)?.get("enabled") != true)
                throw IllegalStateException("Hardware PRF is not supported")
            val id = credential.rawId
            if (id.isEmpty() || id.size > 1024) throw IllegalStateException("Invalid credential ID")
            val salt = randomBytes(HardwareUnlockEnvelope.SALT_BYTES)
            if (!permitted()) throw IllegalStateException("Canceled")
            return Enrollment(id, salt, calculate(client, id, salt, pin, state))
        }
    }

    fun calculate(device: YubiKeyDevice, request: HardwareUnlockEnvelope.Request, pin: CharArray?,
                  state: CommandState): ByteArray {
        WebAuthnClient.create(device, null).use { client ->
            return calculate(client, request.credentialId, request.salt, pin, state)
        }
    }

    private fun calculate(client: WebAuthnClient, id: ByteArray, salt: ByteArray, pin: CharArray?,
                          state: CommandState): ByteArray {
        if (id.isEmpty() || id.size > 1024 || salt.size != HardwareUnlockEnvelope.SALT_BYTES)
            throw IllegalArgumentException("Invalid hardware credential")
        val options = PublicKeyCredentialRequestOptions.fromMap(mapOf(
            "challenge" to url(randomBytes(32)), "rpId" to RP_ID,
            "allowCredentials" to listOf(mapOf("type" to "public-key", "id" to url(id))),
            "userVerification" to "required",
            "extensions" to mapOf("prf" to mapOf("eval" to mapOf("first" to url(salt))))
        ))
        val credential = client.getAssertion(ClientDataProvider.fromHash(randomBytes(32)), options, RP_ID, pin, state)
        // CBOR returns the PRF as mutable bytes. JSON would create an unerasable Base64 string.
        val extensions = credential.clientExtensionResults?.toMap(SerializationType.CBOR)
        val prf = extensions?.get("prf") as? Map<*, *>
        val output = (prf?.get("results") as? Map<*, *>)?.get("first") as? ByteArray
            ?: throw IllegalStateException("Hardware PRF output is unavailable")
        if (output.size != HardwareUnlockEnvelope.PRF_BYTES || !MessageDigest.isEqual(id, credential.rawId)) {
            output.fill(0)
            throw IllegalStateException("Invalid hardware credential or PRF output")
        }
        return output
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    private fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
