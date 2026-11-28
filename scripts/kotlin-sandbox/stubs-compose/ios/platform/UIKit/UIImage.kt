// Compile-only UIKit image signatures. No decoding is simulated.
package platform.UIKit

class StubCGImage
class StubCIImage {
    val extent: Unit get() = error("CoreImage stub must not execute")
}
class UIImage {
    val CGImage: StubCGImage? get() = error("UIKit stub must not execute")
    val CIImage: StubCIImage? get() = error("UIKit stub must not execute")
}
