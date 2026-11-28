// Compile-only approximation, not Apple ABI validation.
package platform.CoreImage

import platform.UIKit.StubCGImage
import platform.UIKit.StubCIImage

class CIContext

// Objective-C category binding: callers must import this extension explicitly.
fun CIContext.createCGImage(image: StubCIImage, fromRect: Unit): StubCGImage? =
    error("CoreImage stub must not execute")
