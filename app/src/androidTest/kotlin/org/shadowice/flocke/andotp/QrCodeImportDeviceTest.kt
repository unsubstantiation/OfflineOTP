/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Real ZXing/BitmapFactory regression coverage for andOTP issue #892. The exact CCP URI from the
 * report is encoded into a PNG, passed through the app's bounded image-import path, then parsed as
 * an Entry. Unlike the host QR stand-ins, this exercises Android's image decoder and ZXing.
 */
package org.shadowice.flocke.andotp

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.ScanQRCodeFromFile
import org.shadowice.flocke.andotp.Utilities.GoogleAuthenticatorMigration
import org.shadowice.flocke.andotp.Utilities.MigrationParseResult
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class QrCodeImportDeviceTest {
    private val reportedUri = "otpauth://totp/CCP:123456?secret=3KJPWL4V4MSKDPD6"

    @Test
    fun reportedCcpQrImageScansAndParses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = writeQrPng(context.getCacheDir(), reportedUri)
        try {
            val decoded = scan(context, Uri.fromFile(file))
            assertEquals(reportedUri, decoded)

            val entry = Entry(requireNotNull(decoded))
            assertEquals("CCP:123456", entry.getLabel())
            assertEquals("3KJPWL4V4MSKDPD6", entry.getSecretEncoded())
        } finally {
            file.delete()
        }
    }

    @Test
    fun googleAuthenticatorExportQrImageScansAndParses() {
        // Synthetic migration QR: one TOTP account named alice, with a public three-byte seed.
        val export = "otpauth-migration://offline?data=ChYKA%2BD7%2FxIFYWxpY2UaACAAKAAwADgAEAIYASAAKAA%3D"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = writeQrPng(context.getCacheDir(), export)
        try {
            val decoded = scan(context, Uri.fromFile(file))
            assertEquals(export, decoded)
            val parsed = GoogleAuthenticatorMigration.parse(decoded)
            assertTrue(parsed is MigrationParseResult.Success)
            val payload = (parsed as MigrationParseResult.Success).payload
            payload.use {
                val values = payload.takeAccounts()
                try {
                    assertEquals(1, values.size)
                    val entry = Entry(values[0])
                    try {
                        assertEquals("alice", entry.getLabel())
                        assertEquals(Entry.OTPType.TOTP, entry.getType())
                        assertEquals(6, entry.getDigits())
                    } finally { entry.destroy() }
                } finally { values.forEach { it.close() } }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedImageIsRejectedWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getCacheDir(), "issue-892-invalid-image.bin")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        try {
            assertNull(scan(context, Uri.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    /**
     * The import path reports failures with a Toast, and a Toast needs a Looper: run the scan on
     * the main thread like MainActivity does instead of on the instrumentation thread.
     */
    private fun scan(context: android.content.Context, uri: Uri): String? {
        var decoded: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            decoded = ScanQRCodeFromFile.scanQRImage(context, uri)
        }
        return decoded
    }

    private fun writeQrPng(directory: File, contents: String): File {
        val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 320, 320)
        val width = matrix.getWidth()
        val height = matrix.getHeight()
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File(directory, "issue-892-ccp.png")
        try {
            FileOutputStream(file).use { output ->
                assertTrue("PNG image could not be written", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
