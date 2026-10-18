// Sandbox-only stand-ins, see ../../../../../android/android_content.kt.
package com.google.zxing.integration.android

import android.app.Activity
import android.content.Intent

/** The scanner entry point of the main screen. */
class IntentIntegrator(private val activity: Activity) {
    /** The capture activity that was set. */
    var captureActivity: Class<*>? = null
        private set

    /** How often a scan was started. */
    var initiations = 0
        private set

    fun setOrientationLocked(locked: Boolean): IntentIntegrator = this

    fun setBeepEnabled(enabled: Boolean): IntentIntegrator = this

    fun setCaptureActivity(cls: Class<*>): IntentIntegrator {
        captureActivity = cls
        return this
    }

    fun initiateScan(): Intent {
        initiations++
        return Intent()
    }

    companion object {
        /** The result the checks configure; the platform parses the scan intent here. */
        @JvmStatic
        var lastResult: IntentResult? = null

        @JvmStatic
        fun parseActivityResult(requestCode: Int, resultCode: Int, intent: Intent?): IntentResult? = lastResult
    }
}

/** The parsed result of a scan. */
class IntentResult(private val contents: String?) {
    fun getContents(): String? = contents
}
