package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** The subset of Android VectorDrawable that the bundled issuer marks actually use. */
class VectorArtworkTest {
    private fun vector(body: String, width: Int = 100, height: Int = 100) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:aapt="http://schemas.android.com/aapt"
                android:width="100dp" android:height="100dp"
                android:viewportWidth="$width" android:viewportHeight="$height">
            $body
        </vector>
    """.trimIndent().byteInputStream()

    private fun render(xml: java.io.InputStream, size: Int = 48): BufferedImage? =
        VectorArtwork.render(xml, size)

    private fun assertClose(expected: Double, actual: Double, what: String) {
        assertTrue("$what: expected $expected but was $actual", Math.abs(expected - actual) < 0.01)
    }

    private fun covered(image: BufferedImage): Int {
        var count = 0
        for (x in 0 until image.width) for (y in 0 until image.height) if (image.getRGB(x, y) ushr 24 != 0) count++
        return count
    }

    @Test
    fun solidFillIsPaintedInsideTheShapeOnly() {
        val image = checkNotNull(render(vector(
            """<path android:fillColor="#FF0000" android:pathData="M25,25 L75,25 L75,75 L25,75 Z"/>""")))!!
        assertEquals(Color(0xFF, 0x00, 0x00, 0xFF), Color(image.getRGB(24, 24), true))
        assertEquals(0, image.getRGB(2, 2) ushr 24)
    }

    @Test
    fun gradientFillAndGroupsAreHonoured() {
        val image = checkNotNull(render(vector("""
            <group android:translateX="10" android:translateY="10">
                <path android:pathData="M0,0 L80,0 L80,80 L0,80 Z">
                    <aapt:attr name="android:fillColor">
                        <gradient android:type="linear" android:startX="0" android:startY="0"
                                  android:endX="80" android:endY="0">
                            <item android:color="#FF000000" android:offset="0"/>
                            <item android:color="#FFFFFFFF" android:offset="1"/>
                        </gradient>
                    </aapt:attr>
                </path>
            </group>
        """)))!!
        val left = Color(image.getRGB(8, 24), true)
        val right = Color(image.getRGB(40, 24), true)
        assertTrue("left is darker than right: $left vs $right", left.red < right.red)
        assertEquals(0, image.getRGB(1, 1) ushr 24)
    }

    @Test
    fun pathDataAcceptsImplicitOriginArcsAndRelativeCommands() {
        // Shapes encountered in the bundled marks: data starting with "H", compact arc flags and
        // relative curve shorthands.
        val cases = listOf(
            "H10V10H0V0z",
            "M20,12a8,8 0 00-8,8v4h16v-4a8,8 0 00-8,-8z",
            "m10,10 l20,0 l0,20 l-20,0 z",
            "M10,10 Q20,0 30,10 T50,10z",
            "M10,10 C10,20 30,20 30,10 S50,0 50,10z"
        )
        for (data in cases) {
            val path = PathDataParser(data, java.awt.geom.Path2D.WIND_NON_ZERO).parse()
            assertNotNull(data, path)
            val bounds: Rectangle2D = path!!.bounds2D
            assertTrue("$data has a non-empty bounds $bounds", bounds.width > 0 && bounds.height > 0)
        }
    }

    @Test
    fun arcFlagsWrittenWithoutSeparatorsStayTwoFlags() {
        val path = PathDataParser("M10,10 a5,5 0 00-5,5", java.awt.geom.Path2D.WIND_NON_ZERO).parse()
        assertNotNull(path)
        // A quarter circle of radius 5 ends five points left and five below the start.
        assertClose(5.0, path!!.bounds2D.minX, "left edge")
        assertClose(10.0, path.bounds2D.minY, "top edge")
        assertClose(15.0, path.bounds2D.maxY, "bottom edge")
    }

    @Test
    fun strokedShapesAndClipPathsDraw() {
        val image = checkNotNull(render(vector("""
            <group>
                <clip-path android:pathData="M0,0 L50,0 L50,100 L0,100 Z"/>
                <path android:pathData="M0,50 L100,50" android:strokeColor="#FFFFFFFF"
                      android:strokeWidth="10" android:strokeLineCap="round"/>
            </group>
        """)))!!
        assertEquals(Color.WHITE.alpha, image.getRGB(2, 24) ushr 24)
        assertEquals("clipped right half stays transparent", 0, image.getRGB(45, 24) ushr 24)
    }

    @Test
    fun unknownOrUnsafeDocumentsAreRejectedWithoutThrowing() {
        assertNull(VectorArtwork.render("<bitmap/>".byteInputStream(), 32))
        assertNull(VectorArtwork.render("not xml at all".byteInputStream(), 32))
        val doctype = """
            <!DOCTYPE vector [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <vector android:viewportWidth="10" android:viewportHeight="10"
                xmlns:android="http://schemas.android.com/apk/res/android">
                <path android:fillColor="#FFFFFF" android:pathData="&leak;"/>
            </vector>
        """.trimIndent()
        assertNull(VectorArtwork.render(doctype.byteInputStream(), 32))
    }

    @Test
    fun aspectFitKeepsWideMarksInsideASquareBuffer() {
        // A 200x100 mark fills a 64x64 buffer to 64x32 and is centred, not stretched.
        val image = checkNotNull(render(vector(
            """<path android:fillColor="#FFFFFF" android:pathData="M0,0 L200,0 L200,100 L0,100 Z"/>""",
            width = 200, height = 100), size = 64))!!
        assertTrue(covered(image) > 0)
        assertEquals("letterboxing leaves the top band empty", 0, image.getRGB(32, 4) ushr 24)
        assertEquals(Color.WHITE.alpha, image.getRGB(32, 32) ushr 24)
        assertEquals("letterboxing leaves the bottom band empty", 0, image.getRGB(32, 60) ushr 24)
    }
}
