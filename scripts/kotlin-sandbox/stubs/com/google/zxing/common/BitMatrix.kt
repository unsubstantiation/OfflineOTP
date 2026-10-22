// Compile-only stand-in for ZXing's BitMatrix. It never encodes image pixels.
package com.google.zxing.common

class BitMatrix(private val width: Int, private val height: Int) {
    fun getWidth(): Int = width
    fun getHeight(): Int = height
    fun get(x: Int, y: Int): Boolean = false
}
