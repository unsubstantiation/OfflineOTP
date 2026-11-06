# Generated UniFFI bindings

Do not edit `offlineotp_openpgp.kt` or the Swift/header files by hand. They are
generated from `rust/offlineotp-openpgp/src/offlineotp_openpgp.udl` by
`scripts/openpgp/generate-bindings.sh` (uniffi-bindgen 0.28.3).

`kotlin/uniffi/offlineotp_openpgp/NativeApi.kt` is the one hand-written file here:
it adapts UniFFI's `List<UByte>` surface to the `ByteArray` OpenPgpPort used by the
JVM clients and exposes a stable reflective entry point.

The Swift binding source is committed under `ios/Sources/offlineotp_openpgp/` so
SwiftPM can compile it as a target when the XCFramework is present. The C header
and module map in `swift/` are used by `scripts/openpgp/build-ios-xcframework.sh`.
