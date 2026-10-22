// Sandbox-only stand-in for the androidTest compile check; not part of run.sh's stub set.
package androidx.test.runner.lifecycle

import android.app.Activity

/** The lifecycle stages the runner reports, mirroring androidx.test's Stage enum. */
enum class Stage { PRE_ON_CREATE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

/** The registry the instrumentation sources read the resumed activity from. */
class ActivityLifecycleMonitorRegistry private constructor() {
    fun getActivitiesInStage(stage: Stage): Collection<Activity> =
        throw UnsupportedOperationException("android test compile stub")

    companion object {
        @JvmStatic
        fun getInstance(): ActivityLifecycleMonitorRegistry =
            throw UnsupportedOperationException("android test compile stub")
    }
}
