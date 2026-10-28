package org.shadowice.flocke.andotp.Utilities

/**
 * Platform port that decodes the first QR code found in an encoded image byte array (PNG/JPEG/…).
 * The Android implementation reads a content URI through zxing's Android pipeline; the desktop
 * client uses zxing-core over a `javax.imageio` bitmap. Both return the decoded text or null when
 * no code is present, the image is unreadable, or it violates the shared image/document bounds.
 */
interface QrImageDecoder {
    fun decode(imageBytes: ByteArray): String?
}
