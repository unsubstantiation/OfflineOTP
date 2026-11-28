// Compile-only ownership signature, not Apple runtime validation.
package platform.CoreGraphics

import platform.UIKit.StubCGImage

fun CGImageRelease(image: StubCGImage) { error("CoreGraphics stub must not execute") }
