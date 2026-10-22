// Compile-only stand-in for ZXing's writer. Device tests use the real dependency; this class
// exists only so the no-SDK instrumentation compile check can type-check those sources.
package com.google.zxing.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix

class QRCodeWriter {
    fun encode(contents: String, format: BarcodeFormat, width: Int, height: Int): BitMatrix =
        BitMatrix(width, height)
}
