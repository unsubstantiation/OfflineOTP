// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
//
// The OpenPGP service connection and API surface the backup screen uses. The provider is never
// contacted; the stand-in only records that the API was called.
package org.openintents.openpgp.util

import android.content.Context
import android.content.Intent
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import java.io.InputStream
import java.io.OutputStream

/** The connection to the OpenPGP provider app. */
class OpenPgpServiceConnection(private val context: Context, private val provider: String?) {
    /** How often the connection was bound or unbound. */
    var binds = 0
        private set
    var unbinds = 0
        private set

    /** The service the connection is bound to; the provider app supplies it. */
    var boundService: Any? = null

    fun bindToService() {
        binds++
    }

    fun unbindFromService() {
        unbinds++
    }

    fun getService(): Any? = boundService
}

/** The OpenPGP API client. */
class OpenPgpApi(private val context: Context, private val service: Any?) {
    /** The intents the API was called with, in call order. */
    val calls = ArrayList<Intent>()

    /** The result the API returns; the checks set it. */
    var result: Intent = Intent()

    fun executeApi(intent: Intent, input: InputStream?, output: OutputStream?): Intent {
        calls.add(intent)
        return result
    }

    companion object {
        const val ACTION_SIGN_AND_ENCRYPT = "org.openintents.openpgp.action.SIGN_AND_ENCRYPT"
        const val ACTION_ENCRYPT = "org.openintents.openpgp.action.ENCRYPT"
        const val ACTION_DECRYPT_VERIFY = "org.openintents.openpgp.action.DECRYPT_VERIFY"
        const val EXTRA_SIGN_KEY_ID = "sign_key_id"
        const val EXTRA_USER_IDS = "user_ids"
        const val EXTRA_REQUEST_ASCII_ARMOR = "ascii_armor"
        const val RESULT_CODE = "result_code"
        const val RESULT_CODE_SUCCESS = 1
        const val RESULT_CODE_USER_INTERACTION_REQUIRED = 2
        const val RESULT_CODE_ERROR = 0
        const val RESULT_SIGNATURE = "signature"
        const val RESULT_ERROR = "error"
        const val RESULT_INTENT = "intent"
    }
}

/** The OpenPGP provider preference. */
open class OpenPgpAppPreference(context: android.content.Context, attrs: android.util.AttributeSet?) :
    android.preference.ListPreference() {
    init {
        this.context = context
    }
}

/** The OpenPGP signing key preference. */
open class OpenPgpKeyPreference(context: android.content.Context, attrs: android.util.AttributeSet?) :
    android.preference.ListPreference() {
    init {
        this.context = context
    }

    /** The provider the signing key is read from. */
    var openPgpProvider: String? = null
        private set

    fun setOpenPgpProvider(provider: String?) {
        openPgpProvider = provider
    }

    /** The activity results the preference handled. */
    val handledResults: MutableList<IntArray> = ArrayList()

    fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        handledResults.add(intArrayOf(requestCode, resultCode))
    }
}
