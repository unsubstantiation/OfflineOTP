package com.fairuniversal.offlineotp.openpgp

import com.fairuniversal.offlineotp.Utilities.OpenPgpPort

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.GeneralSecurityException

/**
 * [OpenPgpPort] backed by the unified Rust backend (`offlineotp-openpgp`) for Android.
 *
 * The Rust crate builds `libofflineotp_openpgp.so` per ABI via `cargo ndk`; CI
 * packages those libraries into `app/src/main/jniLibs/<abi>/`. The generated
 * Kotlin bindings (`uniffi.offlineotp_openpgp`) plus the byte-array facade
 * `NativeApi` are compiled into the app and load the library through JNA.
 *
 * The external OpenKeychain provider flow in `BackupActivity` remains complete
 * and supported for users who already configured it and for restoring files it
 * created. This adapter is the in-app rpgp path; when the native library is not
 * packaged it fails explicitly instead of pretending to encrypt or silently
 * falling back to a different engine.
 */
class RpgpOpenPgpPort : OpenPgpPort {

    /**
     * Whether the packaged native library can actually be loaded. The backup screen asks before
     * enabling its buttons, so a build without `jniLibs` reports that plainly instead of letting the
     * user pick a destination and then failing.
     */
    fun isBackendAvailable(): Boolean = tryLoadNative() != null

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
        val native = tryLoadNative() ?: throw GeneralSecurityException(
            "Unified OpenPGP backend not available: Rust library not loaded. " +
                "Use the OpenKeychain provider path or rebuild with the Rust toolchain."
        )
        return try {
            native.encrypt(plaintext, recipientKeyBlocks)
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray {
        val native = tryLoadNative() ?: throw GeneralSecurityException(
            "Unified OpenPGP backend not available: Rust library not loaded."
        )
        return try {
            val pass = passphrase?.concatToString()
            native.decrypt(message, secretKeyBlock, pass)
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    private fun mapException(e: Exception): Exception {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        val name = cause.javaClass.simpleName
        val msg = cause.message ?: cause.toString()
        return when {
            name.contains("InvalidRecipientKey") ||
                name.contains("InvalidSecretKey") ||
                name.contains("InvalidMessage") ||
                name.contains("DecryptionFailed") ||
                name.contains("EmptyOutput") ||
                name.contains("TooLarge") ->
                GeneralSecurityException(msg, cause)
            msg.contains("TooLarge", ignoreCase = true) ||
                msg.contains("InvalidRecipient", ignoreCase = true) ||
                msg.contains("InvalidMessage", ignoreCase = true) ||
                msg.contains("DecryptionFailed", ignoreCase = true) ->
                GeneralSecurityException(msg, cause)
            else -> IOException(msg, cause)
        }
    }

    private fun tryLoadNative(): NativeBridge? {
        return try {
            val clazz = Class.forName("uniffi.offlineotp_openpgp.NativeApi")
            val available = clazz.getMethod("isAvailable").invoke(null) as Boolean
            if (!available) return null
            val encryptMethod = clazz.getMethod(
                "encrypt",
                ByteArray::class.java,
                List::class.java
            )
            val decryptMethod = clazz.getMethod(
                "decrypt",
                ByteArray::class.java,
                ByteArray::class.java,
                String::class.java
            )
            object : NativeBridge {
                override fun encrypt(plaintext: ByteArray, recipients: List<ByteArray>): ByteArray {
                    return encryptMethod.invoke(null, plaintext, recipients) as ByteArray
                }
                override fun decrypt(message: ByteArray, secretKey: ByteArray, passphrase: String?): ByteArray {
                    return decryptMethod.invoke(null, message, secretKey, passphrase) as ByteArray
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private interface NativeBridge {
        fun encrypt(plaintext: ByteArray, recipients: List<ByteArray>): ByteArray
        fun decrypt(message: ByteArray, secretKey: ByteArray, passphrase: String?): ByteArray
    }
}
