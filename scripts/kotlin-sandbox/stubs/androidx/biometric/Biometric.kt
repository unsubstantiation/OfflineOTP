// Sandbox-only stand-ins for androidx.biometric, see ../../android/android_content.kt.
package androidx.biometric

import android.content.Context
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor
import javax.crypto.Cipher

class BiometricManager private constructor() {
    interface Authenticators {
        companion object {
            const val BIOMETRIC_STRONG: Int = 15
        }
    }

    fun canAuthenticate(authenticators: Int): Int = BIOMETRIC_ERROR_HW_UNAVAILABLE

    companion object {
        const val BIOMETRIC_SUCCESS: Int = 0
        const val BIOMETRIC_ERROR_HW_UNAVAILABLE: Int = 1
        const val BIOMETRIC_ERROR_NONE_ENROLLED: Int = 11
        const val BIOMETRIC_ERROR_HW_NOT_PRESENT: Int = 12

        @JvmStatic
        fun from(context: Context): BiometricManager = BiometricManager()
    }
}

class BiometricPrompt(
    private val activity: FragmentActivity,
    private val executor: Executor,
    private val callback: AuthenticationCallback
) {
    class CryptoObject(val cipher: Cipher)

    class AuthenticationResult(val cryptoObject: CryptoObject?, val authenticationType: Int = 0)

    abstract class AuthenticationCallback {
        open fun onAuthenticationError(errorCode: Int, errString: CharSequence) {}
        open fun onAuthenticationSucceeded(result: AuthenticationResult) {}
        open fun onAuthenticationFailed() {}
    }

    class PromptInfo private constructor() {
        class Builder {
            fun setTitle(title: CharSequence): Builder = this
            fun setSubtitle(subtitle: CharSequence?): Builder = this
            fun setNegativeButtonText(text: CharSequence): Builder = this
            fun setAllowedAuthenticators(authenticators: Int): Builder = this
            fun build(): PromptInfo = PromptInfo()
        }
    }

    fun authenticate(info: PromptInfo, crypto: CryptoObject) {}

    companion object {
        const val ERROR_HW_UNAVAILABLE: Int = 1
        const val ERROR_CANCELED: Int = 5
        const val ERROR_LOCKOUT: Int = 7
        const val ERROR_LOCKOUT_PERMANENT: Int = 9
        const val ERROR_USER_CANCELED: Int = 10
        const val ERROR_NO_BIOMETRICS: Int = 11
        const val ERROR_HW_NOT_PRESENT: Int = 12
        const val ERROR_NEGATIVE_BUTTON: Int = 13
        const val ERROR_NO_DEVICE_CREDENTIAL: Int = 14
    }
}
