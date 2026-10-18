// Sandbox-only stand-in for the androidTest compile check; not part of run.sh's stub set.
package androidx.test.core.app

import android.app.Activity

/**
 * The scenario launcher the instrumentation sources use. Only the members the app's
 * instrumentation suite calls are modelled; launching moves to RESUMED like the real API,
 * so onActivity is reachable without a separate moveToState call.
 */
class ActivityScenario<T : Activity> private constructor() : AutoCloseable {
    fun onActivity(action: (T) -> Unit): ActivityScenario<T> =
        throw UnsupportedOperationException("android test compile stub")

    override fun close() = throw UnsupportedOperationException("android test compile stub")

    companion object {
        @JvmStatic
        fun <T : Activity> launch(activityClass: Class<T>): ActivityScenario<T> =
            throw UnsupportedOperationException("android test compile stub")
    }
}
