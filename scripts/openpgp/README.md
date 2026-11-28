# Unified OpenPGP backend packaging

The cryptography lives in `rust/offlineotp-openpgp` (rpgp 0.21.1 + UniFFI 0.31.0).
The foreign-language contract is `rust/offlineotp-openpgp/src/offlineotp_openpgp.udl`.
`rust/offlineotp-openpgp/uniffi.toml` keeps the generated loaders pointed at the
packaged `offlineotp_openpgp` library name.
Generated bindings are committed so a JVM/Swift build does not need to run bindgen:

- Kotlin/JNA: `rust/bindings/kotlin/uniffi/offlineotp_openpgp/`
  (generated file plus the hand-written `NativeApi.kt` byte-array facade).
- Swift: `ios/Sources/offlineotp_openpgp/offlineotp_openpgp.swift`;
  C header/module map for the XCFramework: `rust/bindings/swift/`.

## Regenerate bindings

```sh
cargo install uniffi --features cli --bin uniffi-bindgen --version 0.31.0 --locked
scripts/openpgp/generate-bindings.sh          # write
scripts/openpgp/generate-bindings.sh --check  # fail on drift
```

Generation requires Cargo workspace metadata (`--metadata-no-deps`), not a compiled
library. Build and test the native implementation separately:

```sh
cargo test --manifest-path rust/Cargo.toml
cargo build --manifest-path rust/Cargo.toml --release
```

## Desktop (Debian/Windows/macOS)

`desktop/build.gradle` compiles the Kotlin bindings and declares JNA.
`desktop-compose/build.gradle` stages the host cdylib from `rust/target/release`
into the distribution and configures the packaged launcher's native-library path. `RpgpOpenPgpPort` is
the default engine; PGPainless is only a fallback when the native library is absent.

## Android

```sh
cargo install cargo-ndk --locked
scripts/openpgp/build-android-jnilibs.sh
```

Install an Android NDK (CI uses r30). Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`,
or let the script select the newest NDK under `$ANDROID_HOME/ndk`. It installs the
ARM64, ARMv7 and x86-64 Rust targets, runs cargo-ndk from `rust/`, and stages the
libraries in `app/src/main/jniLibs`. Run it before assembling APKs that need native
OpenPGP; a debug Gradle build alone does not build the Rust libraries.

CI runs the same script before assembling release APKs. The OpenKeychain provider
flow remains available for users who configured it; `RpgpOpenPgpPort` is the in-app
rpgp path and fails explicitly when the native library is not packaged.

## iOS / macOS Swift package

```sh
scripts/openpgp/build-ios-xcframework.sh               # universal Apple slices
scripts/openpgp/build-ios-xcframework.sh --arm64-only  # Apple-silicon CI path
```

This writes `ios/Frameworks/offlineotp_openpgp.xcframework`. When that directory
exists, `ios/Package.swift` compiles the generated Swift bindings and defines
`OFFLINEOTP_OPENPGP_NATIVE`; `RustOpenPgpPort` then performs real rpgp operations.
Without the artifact the portable format layer still builds and tests, and the
OpenPGP port throws `backendUnavailable` instead of pretending to encrypt.

The iOS workflow uses `--arm64-only` for the device ARM64, simulator ARM64 and macOS
ARM64 library slices. Only the unsigned device IPA is uploaded; standalone SwiftPM
tests and simulator app artifacts are not CI outputs. The default local framework
build also includes Intel slices.
