// Sandbox-only stand-ins, see android_content.kt.
package android.graphics.drawable

import android.graphics.Canvas

/** Bounds/draw-only stand-in for the vector thumbnail rendering the resolved Java sources do. */
abstract class Drawable {
    /** Bounds are recorded for compile-only drawable inspection, not rasterization. */
    val bounds: android.graphics.Rect = android.graphics.Rect()
    open fun draw(canvas: Canvas?) {}
    open fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        bounds.left = left
        bounds.top = top
        bounds.right = right
        bounds.bottom = bottom
    }
    open fun getMinimumWidth(): Int = 1
    open fun getMinimumHeight(): Int = 1
    open fun getIntrinsicWidth(): Int = 1
    open fun getIntrinsicHeight(): Int = 1
    open fun setAlpha(alpha: Int) {}
    open fun setColorFilter(filter: android.graphics.ColorFilter?) {}
    open fun mutate(): Drawable = this
}

/** The drawable of a bitmap; the entry popup shows the QR code in one. */
open class BitmapDrawable(resources: android.content.res.Resources?, private val bitmap: android.graphics.Bitmap?) :
    Drawable() {
    /** Whether the bitmap is filtered when it is scaled. The platform only has setFilterBitmap(boolean). */
    private var filterBitmapFlag: Boolean = true
    fun setFilterBitmap(filter: Boolean) {
        filterBitmapFlag = filter
    }

}
