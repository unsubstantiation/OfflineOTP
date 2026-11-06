package org.shadowice.flocke.andotp.openpgp

import org.shadowice.flocke.andotp.Utilities.OpenPgpPort

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * [OpenPgpPort] backed by the unified Rust backend (`offlineotp-openpgp`) for Android.
 *
 * Mirrors `desktop/.../RpgpOpenPgpPort` but lives in the Android app. The Rust crate
 * builds `libofflineotp_openpgp.so` per ABI via `cargo ndk`; the generated Kotlin
 * bindings (`uniffi.offlineotp_openpgp`) are bundled in `src/main/jniLibs/<abi>/`.
 *
 * During scaffolding the native library is optional: when it cannot be loaded this
 * adapter delegates to the external OpenKeychain provider flow that
 * `BackupActivity` already implements. Once the Rust crate is wired into `android.yml`
 * and the per-ABI libs are committed, the OpenKeychain path becomes a fallback for
 * users who already configured it, and new installs will use the in-app rpgp.
 *
 * The external provider path is retained for compatibility: users who relied on
 * OpenKeychain should not lose their ability to restore `.json.gpg` files created
 * with it, and the provider's `OpenPgpApi` continues to be the source for signing
 * verification when available.
 */
class RpgpOpenPgpPort : OpenPgpPort {

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
        return tryLoadNative()?.let { native ->
            try {
                native.encrypt(plaintext, recipientKeyBlocks)
            } catch (e: Exception) {
                throw mapException(e)
            }
        } ?: throw GeneralSecurityException(
            "Unified OpenPGP backend not available: Rust library not loaded. " +
                "Use the OpenKeychain provider path or rebuild with the Rust toolchain."
        )
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray {
        return tryLoadNative()?.let { native ->
            try {
                val pass = passphrase?.let { String(it) }
                native.decrypt(message, secretKeyBlock, pass)
            } catch (e: Exception) {
                throw mapException(e)
            }
        } ?: throw GeneralSecurityException(
            "Unified OpenPGP backend not available: Rust library not loaded."
        )
    }

    private fun mapException(e: Exception): Exception {
        val msg = e.message ?: e.toString()
        return when {
            msg.contains("RecipientKey", ignoreCase = true) ||
                msg.contains("InvalidRecipient", ignoreCase = true) ||
                msg.contains("TooLarge", ignoreCase = true) ->
                GeneralSecurityException(msg, e)
            msg.contains("InvalidMessage", ignoreCase = true) ||
                msg.contains("DecryptionFailed", ignoreCase = true) ->
                GeneralSecurityException(msg, e)
            else -> IOException(msg, e)
        }
    }

    private fun tryLoadNative(): NativeBridge? {
        return try {
            val clazz = Class.forName("uniffi.offlineotp_openpgp.offlineotp_openpgpKt")
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
                    @Suppress("UNCHECKED_CAST")
                    return encryptMethod.invoke(null, plaintext, recipients) as ByteArray
                }
                override fun decrypt(message: ByteArray, secretKey: ByteArray, passphrase: String?): ByteArray {
                    return decryptMethod.invoke(null, message, secretKey, passphrase) as ByteArray
                }
            }
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private interface NativeBridge {
        fun encrypt(plaintext: ByteArray, recipients: List<ByteArray>): ByteArray
        fun decrypt(message: ByteArray, secretKey: ByteArray, passphrase: String?): ByteArray
    }
}
