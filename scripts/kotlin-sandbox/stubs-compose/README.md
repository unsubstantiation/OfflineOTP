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
- `ios/`: Compose UIKit bridge plus narrow Foundation/UIKit/Vision service signatures.

A stub compile can pass while the published SDK rejects a signature. Real Gradle,
Kotlin/Native and Xcode builds remain the build of record. Do not loosen a stub to
hide a real API mismatch or describe an inert stub as platform behavior.

## Known boundaries

The Android stub keeps `ComposeView` final and exposes `AbstractComposeView.Content()`
as the custom-view extension point, matching
[AndroidX source](https://github.com/androidx/androidx/blob/ca175c23e35c88a0a4517e7d1bc01764a60e3dbf/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/ComposeView.android.kt).

The iOS date, clipboard and Vision stand-ins do not simulate OS expiry, local-only
pasteboards or QR decoding. Service stubs throw if executed where applicable.
Their presence does not validate Swift export names, native ownership or Apple ABI.

See [sandbox limits](../README.md) and
[native validation](../../../docs/shared-ui-native-validation.md).
