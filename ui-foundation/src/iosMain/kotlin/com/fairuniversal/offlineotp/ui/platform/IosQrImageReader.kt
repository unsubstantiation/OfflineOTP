@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.platform

import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import platform.CoreImage.createCGImage
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
        // Vision's CIImage overload exposes an Objective-C forward declaration in
        // shared iOS metadata, not platform.CoreImage.CIImage. Render CI-backed
        // images to a CGImage instead of casting between incompatible bindings.
        val sourceCgImage = image.CGImage
        val renderedCgImage = if (sourceCgImage == null) {
            val ciImage = image.CIImage ?: return null
            CIContext().createCGImage(ciImage, fromRect = ciImage.extent) ?: return null
        } else null
        val handler = VNImageRequestHandler(
            sourceCgImage ?: renderedCgImage ?: return null,
            options = emptyMap<Any?, Any>()
        )
        return try {
            decode(handler, request)
        } finally {
            // createCGImage transfers ownership; UIImage's own CGImage is borrowed.
            if (renderedCgImage != null) CGImageRelease(renderedCgImage)
        }
    }

    private fun decode(handler: VNImageRequestHandler, request: VNDetectBarcodesRequest): String? {
        // A provider/decoder failure never becomes a payload or an alternate parser attempt.
        if (!handler.performRequests(listOf(request), error = null)) return null
        return request.results.orEmpty().asSequence()
            .filterIsInstance<VNBarcodeObservation>()
            .mapNotNull { it.payloadStringValue?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }
}
