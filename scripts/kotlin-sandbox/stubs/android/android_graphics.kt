// Sandbox-only stand-ins, see android_content.kt.
package android.graphics

abstract class ColorFilter


class Canvas(private var targetBitmap: Bitmap? = null) {
    fun setBitmap(bitmap: Bitmap?) {
        targetBitmap = bitmap
    }
    fun drawColor(color: Int) {}
    fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
        targetBitmap?.lastDrawnText = String(text, index, count)
    }
}

class PorterDuffColorFilter(color: Int, mode: PorterDuff.Mode) : ColorFilter()

class PorterDuff private constructor() {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR, DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY }
}

/**
 * Bitmap stand-in: a size plus an optional pixel array, so the QR check can compare the pixels
 * handed to the decoder and observe recycling. No real pixel storage or drawing is modelled.
 */
class Bitmap(private val width: Int = 1, private val height: Int = 1, private val pixels: IntArray? = null) {
    enum class Config { ARGB_8888, RGB_565, ALPHA_8 }
    enum class CompressFormat { JPEG, PNG, WEBP }

    private var recycled = false
    var lastDrawnText: String? = null

    fun getWidth(): Int = width
    fun getHeight(): Int = height
    fun isRecycled(): Boolean = recycled
    fun recycle() {
        recycled = true
    }

    /** Copies row by row like the platform method; refuses a recycled bitmap. */
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        check(!recycled) { "Can't call getPixels() on a recycled bitmap" }
        val source = this.pixels ?: return
        for (row in 0 until height) {
            System.arraycopy(source, (y + row) * this.width + x, pixels, offset + row * stride, width)
        }
    }

    /** Compile-only instrumentation surface; the sandbox does not encode real images. */
    fun setPixels(colors: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}

    fun compress(format: CompressFormat, quality: Int, stream: java.io.OutputStream): Boolean = false

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config?): Bitmap = Bitmap()
    }
}

object BitmapFactory {
    /** The public option fields the QR helper sets and reads. */
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inPreferredConfig: Bitmap.Config? = Bitmap.Config.ARGB_8888
        @JvmField var outWidth: Int = 0
        @JvmField var outHeight: Int = 0
    }

    /**
     * Sandbox hook for the options overload: receives a snapshot of `inJustDecodeBounds` and the
     * options, fills the `out*` fields and returns the bitmap. Unset, nothing decodes (`null`).
     */
    @JvmField
    var decoder: ((Boolean, Options) -> Bitmap?)? = null

    @JvmStatic
    fun decodeResource(res: android.content.res.Resources?, id: Int): Bitmap? = null

    @JvmStatic
    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int): Bitmap? = null

    @JvmStatic
    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int, opts: Options?): Bitmap? {
        val options = opts ?: Options()
        val bitmap = decoder?.invoke(options.inJustDecodeBounds, options)
        return if (options.inJustDecodeBounds) null else bitmap
    }
}

open class Paint {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }

    var color: Int = 0
    var textSize: Float = 0f
    var isAntiAlias: Boolean = false

    fun getTextBounds(text: CharArray, index: Int, count: Int, bounds: Rect) {}
    fun getTextWidths(text: String?, widths: FloatArray): Int = 0
    fun setStyle(style: Style?) {}
    fun setTextAlign(align: Align?) {}
    fun measureText(text: String?): Float = 0f
    fun setTypeface(typeface: Typeface?) {}
}

class Typeface private constructor() {
    companion object {
        /** The style constants of the platform. */
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2

        @JvmStatic
        val DEFAULT: Typeface = Typeface()

        @JvmStatic
        fun create(family: String?, style: Int): Typeface = Typeface()

        @JvmStatic
        fun create(family: String?): Typeface = Typeface()

        @JvmStatic
        fun defaultFromStyle(style: Int): Typeface = Typeface()
    }
}

class Rect {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}

object Color {
    const val WHITE: Int = -1
    const val BLACK: Int = -16777216
    const val TRANSPARENT: Int = 0
}
