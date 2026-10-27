package com.yubico.yubikit.fido.webauthn

enum class SerializationType { CBOR, JSON }
class PublicKeyCredentialCreationOptions(val value: Map<String, Any>) {
    companion object { fun fromMap(value: Map<String, Any>): PublicKeyCredentialCreationOptions = PublicKeyCredentialCreationOptions(value) }
}
class PublicKeyCredentialRequestOptions(val value: Map<String, Any>) {
    companion object { fun fromMap(value: Map<String, Any>): PublicKeyCredentialRequestOptions = PublicKeyCredentialRequestOptions(value) }
}
class Extensions(private val value: Map<String, Any> = emptyMap()) {
    var lastSerialization: SerializationType? = null
    fun toMap(type: SerializationType): Map<String, Any> { lastSerialization = type; return value }
}
class PublicKeyCredential(val rawId: ByteArray, val clientExtensionResults: Extensions?)
