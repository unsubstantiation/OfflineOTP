// Sandbox-only stand-in, see ../../../android/android_content.kt.
//
// It exists so the converted QR scanner activity can be type-checked and its window handling
// executed on a desktop JVM. It is NOT the scanner library: there is no camera, no preview, no
// decoder and no barcode view, only the shape the converted activity overrides.
package com.journeyapps.barcodescanner

import android.app.Activity

/**
 * Stand-in for the barcode scanner activity.
 *
 * The platform's `initializeContent` builds the decorated scanner view; here it hands out an
 * inert stand-in, so the checks can drive the window handling around it.
 */
open class CaptureActivity : Activity() {
    /** Builds the decorated scanner view; the platform inflates the scanner layout here. */
    protected open fun initializeContent(): DecoratedBarcodeView = DecoratedBarcodeView()
}

/** Stand-in for the decorated scanner view; it carries no decoder and no preview. */
class DecoratedBarcodeView
