package com.fairuniversal.offlineotp.desktop.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.fairuniversal.offlineotp.Database.Entry

import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Renders an entry as a QR code, the desktop counterpart of Android's `menu_popup_show_qr_code`.
 *
 * Android hides that action for notes, SSH keys, password entries (single and archive) and email
 * accounts because those have no `otpauth://` form; [payloadFor] applies the same rule here so the
 * desktop menu can hide the item for exactly the same entries. The image work itself is
 * compile-verified in the sandbox (the zxing stubs never encode pixels) and runs against zxing-core
 * in the packaged build, like [ZxingQrDecoder].
 */
object QrCodeImage {

    /** Modules per pixel; Android's BarcodeEncoder picks the largest scale that fits the screen. */
    const val DEFAULT_SCALE = 6

    private const val MAX_SCALE = 40

    /**
     * The `otpauth://` payload for [entry], or null when the entry cannot be encoded (notes, SSH
     * keys, passwords, password archives and email accounts), or when the URI itself is invalid.
     */
    fun payloadFor(entry: Entry): String? {
        // The kind rule is shared with Android's popup, so neither client can offer a code the
        // other refuses for the same entry.
        if (!entry.hasOtpUri()) return null
        return try {
            entry.toUriString().takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            // A secret that cannot form a URI is reported as "no QR code" rather than a crash.
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    /**
     * Draws [payload] as a black-on-white QR image, scaled by [scale] modules per pixel. The quiet
     * zone comes from zxing, as on Android, so the code stays scannable on a white dialog.
     */
    fun render(payload: String, scale: Int = DEFAULT_SCALE): BufferedImage {
        if (payload.isEmpty()) throw IllegalArgumentException("empty QR payload")
        val module = scale.coerceIn(1, MAX_SCALE)
        // Zero asks zxing for the natural size; the scale is applied here so the caller can pick it.
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)
        val width = matrix.getWidth() * module
        val height = matrix.getHeight() * module
        if (width <= 0 || height <= 0) throw IllegalStateException("empty QR matrix")

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            for (x in 0 until matrix.getWidth()) {
                for (y in 0 until matrix.getHeight()) {
                    if (matrix.get(x, y)) graphics.fillRect(x * module, y * module, module, module)
                }
            }
        } finally {
            graphics.dispose()
        }
        return image
    }
}
