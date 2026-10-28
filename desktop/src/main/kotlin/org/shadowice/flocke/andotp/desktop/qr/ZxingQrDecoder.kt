package org.shadowice.flocke.andotp.desktop.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.QrImageDecoder

import java.io.ByteArrayInputStream
import java.util.EnumMap
import java.util.Vector
import javax.imageio.ImageIO

/**
 * [QrImageDecoder] backed by zxing-core over a JDK `ImageIO` bitmap. It reproduces the Android
 * `ScanQRCodeFromFile` policy: reject oversize documents before decoding, validate pixel
 * dimensions with the shared [InputPolicy.validImage], and try a normal QR decode followed by a
 * TRY_HARDER pass, returning null when no code is found. No camera or screen capture is involved;
 * the input is always an image file the user selected.
 *
 * The class is compile-verified in the sandbox against narrow zxing stubs; real decoding runs
 * under the Gradle build that declares zxing-core. The pre-reader rejection branches are covered
 * headlessly because they return before any zxing call.
 */
class ZxingQrDecoder : QrImageDecoder {

    override fun decode(imageBytes: ByteArray): String? {
        if (imageBytes.isEmpty() || imageBytes.size > BoundedInputReader.MAX_DOCUMENT_BYTES) return null

        val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return null
        if (!InputPolicy.validImage(image.width, image.height)) return null

        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)

        val source = RGBLuminanceSource(width, height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = QRCodeReader()

        val formats = Vector<BarcodeFormat>().apply { add(BarcodeFormat.QR_CODE) }
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, formats)
        }

        // First pass with the standard hints, then a TRY_HARDER retry, matching the Android helper.
        runCatching { reader.decode(bitmap, hints)?.getText() }
            .getOrNull()
            ?.let { return it }

        val harder = EnumMap<DecodeHintType, Any>(hints).apply {
            put(DecodeHintType.TRY_HARDER, true)
        }
        return try {
            reader.decode(bitmap, harder).getText()
        } catch (error: ReaderException) {
            null
        }
    }
}
