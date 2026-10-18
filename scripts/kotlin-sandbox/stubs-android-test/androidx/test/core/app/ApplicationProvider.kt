// Sandbox-only stand-in for the androidTest compile check; not part of run.sh's stub set.
package androidx.test.core.app

import android.content.Context

object ApplicationProvider {
    @JvmStatic
    fun <T : Context> getApplicationContext(): T =
        throw UnsupportedOperationException("android test compile stub")
}
