// Sandbox-only stand-in for the androidTest compile check; not part of run.sh's stub set.
package androidx.test.core.app

import android.app.Activity
import androidx.lifecycle.Lifecycle

/**
 * The scenario launcher the instrumentation sources use. Only the members the app's
 * instrumentation suite calls are modelled; launching moves to RESUMED like the real API,
 * so onActivity is reachable without a separate moveToState call.
 */
class ActivityScenario<T : Activity> private constructor() : AutoCloseable {
    /** The current scenario state; `getState()` returns an androidx.lifecycle state. */
    var state: Lifecycle.State = Lifecycle.State.RESUMED
        private set

    fun onActivity(action: (T) -> Unit): ActivityScenario<T> =
        throw UnsupportedOperationException("android test compile stub")

    fun recreate(): ActivityScenario<T> =
        throw UnsupportedOperationException("android test compile stub")

    fun moveToState(newState: Lifecycle.State): ActivityScenario<T> =
        throw UnsupportedOperationException("android test compile stub")

    override fun close() = throw UnsupportedOperationException("android test compile stub")

    companion object {
        @JvmStatic
        fun <T : Activity> launch(activityClass: Class<T>): ActivityScenario<T> =
            throw UnsupportedOperationException("android test compile stub")
    }
}
