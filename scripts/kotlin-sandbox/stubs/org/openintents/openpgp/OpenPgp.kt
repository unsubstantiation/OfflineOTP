// Sandbox-only stand-ins, see ../../../android/android_content.kt.
//
// The OpenPGP API surface the backup screen uses. Nothing is executed: the provider is a
// stand-in, so the checks can only see that the converted screen called the API.
package org.openintents.openpgp

/** Binder service marker exposed by an OpenPGP provider. */
interface IOpenPgpService2

/** The error the OpenPGP app reported. */
class OpenPgpError(private val message: String?) : android.os.Parcelable {
    fun getMessage(): String? = message
}

/** The signature verification result of a decrypted document. */
class OpenPgpSignatureResult(private val result: Int) : android.os.Parcelable {
    fun getResult(): Int = result

    companion object {
        const val RESULT_VALID = 0
        const val RESULT_VALID_KEY_CONFIRMED = 1
        const val RESULT_INVALID = 2
    }
}
