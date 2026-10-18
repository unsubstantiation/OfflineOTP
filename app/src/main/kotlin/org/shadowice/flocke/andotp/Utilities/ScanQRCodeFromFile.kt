/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

import org.shadowice.flocke.andotp.R

import java.io.IOException
import java.util.EnumMap
import java.util.Vector

/** Decodes a QR code from a user-selected image URI using bounded reads and dimensions. */
object ScanQRCodeFromFile {
    private val HINTS: MutableMap<DecodeHintType, Any> =
        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            val barcodeFormats = Vector<BarcodeFormat>()
            barcodeFormats.add(BarcodeFormat.QR_CODE)
            put(DecodeHintType.POSSIBLE_FORMATS, barcodeFormats)
        }

    private val HINTS_HARDER: MutableMap<DecodeHintType, Any> =
        EnumMap<DecodeHintType, Any>(HINTS).apply {
            put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
        }

    /** Decodes the first QR code found in the given image file; null (after a toast) otherwise. */
    @JvmStatic
    fun scanQRImage(context: Context, uri: Uri?): String? {
        val imageInBytes: ByteArray
        try {
            imageInBytes = StorageAccessHelper.loadFile(context, uri)
        } catch (e: IOException) {
            Toast.makeText(context, R.string.toast_file_load_error, Toast.LENGTH_LONG).show()
            return null
        } catch (e: RuntimeException) {
            Toast.makeText(context, R.string.toast_file_load_error, Toast.LENGTH_LONG).show()
            return null
        }

        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(imageInBytes, 0, imageInBytes.size, options)
        if (!InputPolicy.validImage(options.outWidth, options.outHeight)) {
            Toast.makeText(context, R.string.toast_file_load_error, Toast.LENGTH_LONG).show()
            return null
        }
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bMap = BitmapFactory.decodeByteArray(imageInBytes, 0, imageInBytes.size, options)
        if (bMap == null || !InputPolicy.validImage(bMap.getWidth(), bMap.getHeight())) {
            bMap?.recycle()
            Toast.makeText(context, R.string.toast_file_load_error, Toast.LENGTH_LONG).show()
            return null
        }
        var contents: String? = null
        val width = bMap.getWidth()
        val height = bMap.getHeight()
        val intArray = IntArray(width * height)
        try {
            bMap.getPixels(intArray, 0, width, 0, 0, width, height)
        } finally {
            bMap.recycle()
        }
        val source: LuminanceSource = RGBLuminanceSource(width, height, intArray)
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        val reader = QRCodeReader()
        var savedException: ReaderException? = null

        try {
            contents = reader.decode(bitmap, HINTS).getText()
        } catch (re: ReaderException) {
            savedException = re
        }

        if (contents == null) {
            try {
                contents = reader.decode(bitmap, HINTS_HARDER).getText()
            } catch (re: ReaderException) {
                savedException = re
            }
        }

        if (contents == null) {
            val failure: ReaderException = savedException ?: NotFoundException.getNotFoundInstance()
            failure.printStackTrace()
            val message = when (failure) {
                is ChecksumException -> R.string.toast_qr_checksum_exception
                is FormatException -> R.string.toast_qr_format_error
                else -> R.string.toast_qr_error  // also covers NotFoundException
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        return contents
    }
}
