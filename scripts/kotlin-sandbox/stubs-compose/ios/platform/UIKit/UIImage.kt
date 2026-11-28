// Compile-only UIKit image signatures. No decoding is simulated.
package platform.UIKit

class StubCGImage
class StubCIImage
class UIImage {
    val CGImage: StubCGImage? get() = error("UIKit stub must not execute")
    val CIImage: StubCIImage? get() = error("UIKit stub must not execute")
}
