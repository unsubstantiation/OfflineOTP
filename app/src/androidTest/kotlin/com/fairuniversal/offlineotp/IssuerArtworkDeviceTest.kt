package com.fairuniversal.offlineotp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fairuniversal.offlineotp.View.AndroidIssuerArtwork
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled-resource decoding; sandbox compilation does not execute this pixel assertion. */
@RunWith(AndroidJUnit4::class)
class IssuerArtworkDeviceTest {
    @Test
    fun bundledBrandsDecodeAndUnknownNamesUseTheSharedFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cache = AndroidIssuerArtwork(context)
            assertNull(cache.load("Default"))
            assertNull(cache.load("not-a-bundled-brand"))
            val fingerprints = HashSet<Int>()
            for (name in listOf("GitHub", "Google", "Amazon")) {
                val image = requireNotNull(cache.load(name))
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0)
                assertTrue(cache.load(name) === image)
                var visible = false
                var fingerprint = 31 * image.getWidth() + image.getHeight()
                for (x in 0 until image.getWidth()) for (y in 0 until image.getHeight()) {
                    val pixel = image.getPixel(x, y)
                    if ((pixel ushr 24) > 0) visible = true
                    fingerprint = 31 * fingerprint + pixel
                }
                assertTrue("Bundled artwork must contain visible pixels: $name", visible)
                fingerprints.add(fingerprint)
            }
            assertTrue("Brands must not all resolve to the same fallback icon", fingerprints.size == 3)
        }
    }
}
