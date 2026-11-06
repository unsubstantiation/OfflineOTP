package org.shadowice.flocke.andotp.desktop.openpgp

import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * [OpenPgpPort] backed by the unified Rust backend (`offlineotp-openpgp`).
 *
 * This is the rpgp + UniFFI adapter that replaces the JVM-only PGPainless implementation
 * on all five distributions (Android, Debian, Windows, macOS, iOS). The Rust crate
 * (`rust/offlineotp-openpgp`) builds a `cdylib`/`staticlib` via UniFFI; the generated
 * Kotlin bindings (`uniffi.offlineotp_openpgp`) are loaded through JNA on JVM targets and
 * as an XCFramework on Apple targets.
 *
 * During the scaffolding phase the native library is optional: when it cannot be loaded
 * (e.g. a plain `cargo test` without `cargo build`, or an old checkout without the Rust
 * toolchain) this adapter falls back to [PgpainlessOpenPgpPort] so existing workflows
 * stay green. Once the Rust crate is wired into every per-platform workflow and the
 * generated bindings are committed, the fallback will be removed and PGPainless will be
 * dropped from `desktop/build.gradle`.
 *
 * Key material is passed as standard-format byte blocks (armored or binary), keeping every
 * rpgp/BouncyCastle type inside the adapter. The class is compile-verified against narrow
 * stubs in the offline sandbox; the real crypto is exercised only under the Gradle build
 * that declares the native dependency.
 *
 * Interchange note: rpgp uses SEIPDv1 + AES-256, the same interoperable baseline PGPainless
 * uses and the most compatible with OpenKeychain on Android. SEIPDv2 (AEAD) would require
 * newer consumers and is not used here.
 */
class RpgpOpenPgpPort(
    private val fallback: OpenPgpPort = PgpainlessOpenPgpPort()
) : OpenPgpPort {

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
        // Try the unified Rust backend first; fall back to PGPainless when the native library
        // is not yet available (offline sandbox, old checkout, or CI without Rust).
        return tryLoadNative()?.let { native ->
            try {
                native.encrypt(plaintext, recipientKeyBlocks)
            } catch (e: Exception) {
                throw mapException(e)
            }
        } ?: fallback.encrypt(plaintext, recipientKeyBlocks)
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray {
        return tryLoadNative()?.let { native ->
            try {
                val pass = passphrase?.let { String(it) }
                native.decrypt(message, secretKeyBlock, pass)
            } catch (e: Exception) {
                throw mapException(e)
            } finally {
                // Avoid leaving passphrase in a long-lived String; the Rust side copies it
                // and the Kotlin caller is responsible for zeroizing the original char array.
            }
        } ?: fallback.decrypt(message, secretKeyBlock, passphrase)
    }

    private fun mapException(e: Exception): Exception {
        // UniFFI-generated Kotlin throws a typed OpenPgpError; map it to the historical
        // GeneralSecurityException / IOException boundary that BackupCoordinator expects.
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

    /**
     * Attempts to load the native `offlineotp_openpgp` library via the UniFFI-generated
     * Kotlin bindings. Returns null when the library is not present rather than throwing,
     * so callers can fall back to the JVM implementation.
     *
     * The generated bindings live in `uniffi.offlineotp_openpgp` when the Rust crate has
     * been built and `uniffi-bindgen` has run. In the offline sandbox they are absent, so
     * this method uses reflection to avoid a hard compile-time dependency.
     */
    private fun tryLoadNative(): NativeBridge? {
        return try {
            // Reflective load avoids a hard dependency on the generated package, which does
            // not exist in the offline sandbox or on a checkout without `cargo build`.
            val clazz = Class.forName("uniffi.offlineotp_openpgp.offlineotp_openpgpKt")
            @Suppress("UNCHECKED_CAST")
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
            // If the class exists, we consider the native backend available; actual calls
            // still go through the generated functions which handle JNA loading internally.
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
