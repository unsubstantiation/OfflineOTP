// Compile-only approximation, not Apple ABI validation.
package platform.CoreImage

import platform.UIKit.StubCGImage
import platform.UIKit.StubCIImage

class CIContext {
    fun createCGImage(image: StubCIImage, fromRect: Unit): StubCGImage? =
        error("CoreImage stub must not execute")
}
