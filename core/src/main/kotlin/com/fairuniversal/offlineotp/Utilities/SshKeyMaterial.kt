package com.fairuniversal.offlineotp.Utilities


/** Bounded, platform-independent storage for a pair of verbatim SSH key texts. */
object SshKeyMaterial {
    const val MAX_KEY_BYTES = 32 * 1024
    const val MAX_ENCODED_BYTES = 2 * MAX_KEY_BYTES + 8

    /** Not a data class: the generated toString/equals must never expose private key material. */
    class Keys(val publicKey: String, val privateKey: String) {
        override fun toString(): String = "SshKeyMaterial.Keys(redacted)"
    }

    @JvmStatic fun encode(publicKey: String, privateKey: String): ByteArray =
        requireNotNull(com.fairuniversal.offlineotp.domain.CredentialMaterialCodec().sshEncode(publicKey, privateKey)) { "Invalid SSH key text" }

    @JvmStatic fun decode(bytes: ByteArray): Keys {
        val fields = requireNotNull(com.fairuniversal.offlineotp.domain.CredentialMaterialCodec().sshDecode(bytes)) { "Invalid SSH key entry" }
        return Keys(fields.values[0], fields.values[1])
    }
}
