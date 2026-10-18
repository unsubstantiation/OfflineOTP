// Sandbox-only stand-in for the androidTest compile check; not part of run.sh's stub set.
package androidx.test.platform.app

import android.app.Instrumentation

/** The registry the instrumentation sources reach the app context through. */
object InstrumentationRegistry {
    @JvmStatic
    fun getInstrumentation(): Instrumentation =
        throw UnsupportedOperationException("android test compile stub")
}
