package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.ThumbnailAssets

import java.io.File
import java.io.InputStream
import java.awt.image.BufferedImage

/**
 * The desktop client shows the same bundled issuer marks as the Android app. These checks keep the
 * generated name -> drawable table honest and prove that every bundled mark actually rasterizes,
 * so a Debian/Windows card never silently falls back to a letter tile for a known brand.
 */
class IssuerIconsTest {
    private val drawableDir = File("app/src/main/res/drawable")

    private fun drawables(): List<File> {
        assertTrue("drawables are missing; run the tests from the repository root", drawableDir.isDirectory)
        return drawableDir.listFiles { file -> file.name.startsWith("thumb_") && file.name.endsWith(".xml") }!!
            .sortedBy { it.name }
    }

    /** Stands in for the packaged classpath while the no-Gradle sandbox compiles sources only. */
    private fun repoLoader(path: String): InputStream? {
        val file = File(drawableDir, path.removePrefix("/thumbnails/"))
        return if (file.isFile) file.inputStream() else null
    }

    private fun hasVisiblePixel(image: BufferedImage): Boolean {
        for (x in 0 until image.width) for (y in 0 until image.height) {
            if (image.getRGB(x, y) ushr 24 > 32) return true
        }
        return false
    }

    @Test
    fun generatedTableCoversEveryBundledDrawable() {
        val files = drawables().map { it.nameWithoutExtension }.toSet()
        assertTrue(files.size > 400)
        // A few persisted names intentionally share one mark (for example GovUK and HMRC).
        assertEquals("the table and the drawables agree", files, ThumbnailAssets.files())
        assertEquals(files.size, ThumbnailAssets.count())
        // The default has no artwork: cards draw a generated letter tile instead.
        assertNull(ThumbnailAssets.drawableFile("Default"))
    }

    @Test
    fun everyBundledIssuerMarkRendersVisiblePixels() {
        val failures = ArrayList<String>()
        for (file in drawables()) {
            val image = file.inputStream().use { VectorArtwork.render(it, 48) }
            if (image == null || !hasVisiblePixel(image)) failures.add(file.name)
        }
        assertEquals("marks that did not render", emptyList<String>(), failures)
    }

    @Test
    fun knownIssuersResolveToTheirArtworkAndUnknownOnesToTheLetterTile() {
        assertEquals("/thumbnails/thumb_github.xml", IssuerIcons.resourcePath("GitHub"))
        val github = IssuerIcons.artwork("GitHub", ::repoLoader)
        assertNotNull("GitHub", github)
        assertTrue(hasVisiblePixel(github!!))
        for (name in listOf("Default", "NoSuchBrand", "")) {
            assertNull("$name must fall back to the letter tile", IssuerIcons.artwork(name, ::repoLoader))
            assertNull(IssuerIcons.resourcePath(name))
        }
        assertNull("a missing resource is not an exception", IssuerIcons.artwork("GitHub") { null })
    }

    @Test
    fun marksAreCachedInsteadOfReparsedOnEveryRepaint() {
        val first = IssuerIcons.artwork("GitHub", ::repoLoader)
        assertNotNull(first)
        // Direct loads parse again; the production cache keyed by drawable file returns one image.
        assumeTrue(hasBundledResources())
        val cached = IssuerIcons.artwork("GitHub")
        assertNotNull(cached)
        assertTrue(cached === IssuerIcons.artwork("GitHub"))
    }

    @Test
    fun marksArePackagedOnTheDesktopClasspath() {
        // The Gradle :desktop build copies app/src/main/res/drawable/thumb_*.xml into /thumbnails/;
        // the no-Gradle sandbox compiles sources only, so it has no resources to check.
        assumeTrue("classpath artwork is provided by the Gradle build", hasBundledResources())
        val image = IssuerIcons.artwork("GitHub")
        assertNotNull(image)
        assertTrue(hasVisiblePixel(image!!))
        assertNull(IssuerIcons.artwork("NoSuchBrand"))
    }

    private fun hasBundledResources(): Boolean =
        IssuerIcons::class.java.getResourceAsStream("/thumbnails/thumb_github.xml") != null
}
