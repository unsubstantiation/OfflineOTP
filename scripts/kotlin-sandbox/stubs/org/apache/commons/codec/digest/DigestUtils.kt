// Sandbox-only stand-in, see ../../../../../android/android_content.kt.
package org.apache.commons.codec.digest

/**
 * Method-surface stand-in for the legacy SHA-256 hash the authentication upgrade path computes.
 * The digest itself is inert; the RFC vectors for current OTP generation live in Checks.kt.
 */
object DigestUtils {
    @JvmStatic
    fun sha256(data: String?): ByteArray = ByteArray(32)

    @JvmStatic
    fun sha256(data: ByteArray?): ByteArray = ByteArray(32)
}
