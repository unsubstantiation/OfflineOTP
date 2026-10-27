package com.yubico.yubikit.fido.client

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.fido.client.clientdata.ClientDataProvider
import com.yubico.yubikit.fido.webauthn.PublicKeyCredential
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRequestOptions

/** Scripted API shape; it does not implement FIDO, authenticate a key, or calculate a PRF. */
object FidoSandbox {
    var enrollment: PublicKeyCredential? = null
    var assertion: PublicKeyCredential? = null
    var creationOptions: PublicKeyCredentialCreationOptions? = null
    var requestOptions: PublicKeyCredentialRequestOptions? = null
    var makeCalls = 0
    var assertionCalls = 0
    fun reset() { enrollment = null; assertion = null; creationOptions = null; requestOptions = null; makeCalls = 0; assertionCalls = 0 }
}
class WebAuthnClient : AutoCloseable {
    companion object { fun create(device: YubiKeyDevice, configuration: Any?): WebAuthnClient = WebAuthnClient() }
    fun makeCredential(data: ClientDataProvider, options: PublicKeyCredentialCreationOptions, rp: String,
                       pin: CharArray?, user: Any?, config: Any?): PublicKeyCredential {
        FidoSandbox.makeCalls++
        FidoSandbox.creationOptions = options
        return FidoSandbox.enrollment ?: throw UnsupportedOperationException("No hardware in sandbox")
    }
    fun getAssertion(data: ClientDataProvider, options: PublicKeyCredentialRequestOptions, rp: String,
                     pin: CharArray?, config: Any?): PublicKeyCredential {
        FidoSandbox.assertionCalls++
        FidoSandbox.requestOptions = options
        return FidoSandbox.assertion ?: throw UnsupportedOperationException("No hardware in sandbox")
    }
    override fun close() {}
}
