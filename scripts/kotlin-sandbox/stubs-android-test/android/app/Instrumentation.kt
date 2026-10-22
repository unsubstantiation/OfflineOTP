// Sandbox-only stand-in for the androidTest compile check, see ../../../README.md (and
// scripts/kotlin-sandbox/README.md). These model the androidx.test surface the instrumentation
// sources use so compile-android-tests.sh can type-check them without an Android SDK. They are
// deliberately NOT part of run.sh's stub set: the sandbox check models the app's dependencies,
// not the test runner's. Every member throws: nothing here is executable.
package android.app

import android.content.Context

/** The instrumentation handle the platform hands to tests. */
open class Instrumentation {
    val context: Context
        get() = throw UnsupportedOperationException("android test compile stub")

    val targetContext: Context
        get() = throw UnsupportedOperationException("android test compile stub")

    fun waitForIdleSync(): Unit = throw UnsupportedOperationException("android test compile stub")

    fun runOnMainSync(runnable: Runnable): Unit =
        throw UnsupportedOperationException("android test compile stub")
}
