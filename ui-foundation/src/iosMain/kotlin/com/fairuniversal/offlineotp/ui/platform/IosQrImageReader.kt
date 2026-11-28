@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.platform

import platform.UIKit.UIImage
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyQR
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler

/** Permission-free, QR-only image decoding. Payload validation belongs to the shared codec. */
object IosQrImageReader {
    fun payload(image: UIImage): String? {
        val request = VNDetectBarcodesRequest()
        request.symbologies = listOf(VNBarcodeSymbologyQR)
        val cgImage = image.CGImage
        val ciImage = image.CIImage
        val handler = when {
            cgImage != null -> VNImageRequestHandler(cgImage, options = emptyMap<Any?, Any>())
            ciImage != null -> VNImageRequestHandler(ciImage, options = emptyMap<Any?, Any>())
            else -> return null
        }
        // A provider/decoder failure never becomes a payload or an alternate parser attempt.
        if (!handler.performRequests(listOf(request), error = null)) return null
        return request.results.orEmpty().asSequence()
            .filterIsInstance<VNBarcodeObservation>()
            .mapNotNull { it.payloadStringValue?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }
}
