/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Smoke checks for the instrumentation environment itself: the target context is the app
 * (never the test APK) and its private storage is usable. These run first in CI to fail fast
 * when the emulator/instrumentation wiring is broken.
 */
package org.shadowice.flocke.andotp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppContextTest {
    @Test
    fun targetContextIsTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val testApk = instrumentation.context
        assertTrue("target context must not be the instrumentation APK",
            testApk.getPackageName() != target.getPackageName())
        assertTrue(target.getPackageName().startsWith("org.shadowice.flocke.andotp"))
    }

    @Test
    fun privateStorageIsUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = File(context.getFilesDir(), "instrumentation-probe.txt")
        try {
            probe.writeText("probe")
            assertEquals("probe", probe.readText())
        } finally {
            probe.delete()
        }
        assertTrue(context.getFilesDir().isDirectory)
    }
}
