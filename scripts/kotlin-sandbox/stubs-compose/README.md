# Compose/platform stubs — compile only

Handwritten declarations let `../compile-ui.sh` check shared Compose and host seams
without resolving the real artifacts. **They are not a runtime or an API guarantee.**
Nothing is rendered, measured, recomposed or attached to a real window.

The gate compiles portable contracts/foundation separately from JVM engine adapters,
runs headless presentation tests, and type-checks host seams. Paths follow the real
package names so production imports stay unchanged:

- Main directories: Compose signatures used by portable/JVM UI.
- `desktop/`: desktop window APIs.
- `android/`: Android Compose seam, alongside the app's Android substitutes.
- `ios/`: Compose UIKit bridge plus narrow Foundation/UIKit/CoreImage/CoreGraphics/Vision service signatures.

A stub compile can pass while the published SDK rejects a signature. Real Gradle,
Kotlin/Native and Xcode builds remain the build of record. Do not loosen a stub to
hide a real API mismatch or describe an inert stub as platform behavior.

## Known boundaries

The Android stub keeps `ComposeView` final and exposes `AbstractComposeView.Content()`
as the custom-view extension point, matching
[AndroidX source](https://github.com/androidx/androidx/blob/ca175c23e35c88a0a4517e7d1bc01764a60e3dbf/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/ComposeView.android.kt).

The iOS service stand-ins do not simulate OS expiry, local-only pasteboards,
Core Image rendering, CGImage ownership or QR decoding. Some service stubs throw
if executed; none validates Swift export names, native ownership or Apple ABI.

The date stub exposes `timeIntervalSince1970` as an extension property; Core Image
exposes `CIContext.createCGImage` as an extension function. Both require explicit
production imports, matching category bindings missed by earlier stubs. Vision has
no compatible Core Image overload: production renders CI-backed input to CGImage. These shapes guard the
[reported metadata errors](../../../docs/ios-kotlin-cutover.md#metadata-compilation-follow-up),
not the completeness of the Apple bindings.

See [sandbox limits](../README.md) and
[native validation](../../../docs/shared-ui-native-validation.md).
