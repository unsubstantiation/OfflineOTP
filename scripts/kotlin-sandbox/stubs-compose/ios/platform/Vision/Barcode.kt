// Compile-only Vision signatures. No images or observations are produced on the JVM.
package platform.Vision

import platform.UIKit.StubCGImage
import platform.UIKit.StubCIImage

const val VNBarcodeSymbologyQR = "QR"
class VNBarcodeObservation {
    val payloadStringValue: String? get() = error("Vision stub must not execute")
}
class VNDetectBarcodesRequest {
    var symbologies: List<String> = emptyList()
    val results: List<*>? get() = error("Vision stub must not execute")
}
class VNImageRequestHandler {
    constructor(image: StubCGImage, options: Map<Any?, *>)
    constructor(image: StubCIImage, options: Map<Any?, *>)
    fun performRequests(requests: List<VNDetectBarcodesRequest>, error: Any?): Boolean =
        kotlin.error("Vision stub must not execute")
}
