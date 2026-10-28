package org.shadowice.flocke.andotp.desktop.storage

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.KeyWrapPort

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Software [KeyWrapPort], the desktop counterpart of the AndroidKeyStore-backed wrapper. The RSA
 * pair is generated once, written to the private data directory in standard encodings (PKCS#8
 * private key, X.509 public key, owner-only permissions) and reused thereafter; the wrap/unwrap
 * transformation is the same `RSA/ECB/PKCS1Padding` the Android [org.shadowice.flocke.andotp.
 * Utilities.SecretKeyWrapper] uses, so an `otp.key` blob stays interchangeable at the crypto layer.
 */
class RsaKeyWrapPort(directory: File, private val keySize: Int = DEFAULT_KEY_SIZE) : KeyWrapPort {

    private val privateKeyFile = File(directory, PRIVATE_KEY_FILE)
    private val publicKeyFile = File(directory, PUBLIC_KEY_FILE)

    private val keyPair: KeyPair by lazy { loadOrGenerateKeyPair() }

    @Throws(GeneralSecurityException::class)
    override fun wrap(key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC)
        cipher.init(Cipher.WRAP_MODE, keyPair.public)
        return cipher.wrap(key)
    }

    @Throws(GeneralSecurityException::class)
    override fun unwrap(wrapped: ByteArray): SecretKey {
        val cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC)
        cipher.init(Cipher.UNWRAP_MODE, keyPair.private)
        return cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            val factory = KeyFactory.getInstance("RSA")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKeyFile.readBytes()))
            val publicKey = factory.generatePublic(X509EncodedKeySpec(publicKeyFile.readBytes()))
            return KeyPair(publicKey, privateKey)
        }
        val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(keySize) }.generateKeyPair()
        writeOwnerOnly(privateKeyFile, generated.private.encoded)
        writeOwnerOnly(publicKeyFile, generated.public.encoded)
        return generated
    }

    private fun writeOwnerOnly(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), bytes)
        try {
            Files.setPosixFilePermissions(
                file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (error: UnsupportedOperationException) {
            // Non-POSIX file system: fall back to the JDK owner-only access flags.
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    companion object {
        const val DEFAULT_KEY_SIZE = 2048
        const val PRIVATE_KEY_FILE = "wrap-key.der"
        const val PUBLIC_KEY_FILE = "wrap-key.pub.der"

        /** True when a persisted wrapping pair already exists in [directory]. */
        @JvmStatic
        fun isProvisioned(directory: File): Boolean =
            File(directory, PRIVATE_KEY_FILE).exists() && File(directory, PUBLIC_KEY_FILE).exists()
    }
}
