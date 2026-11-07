# Unified OpenPGP backend packaging

The cryptography lives in `rust/offlineotp-openpgp` (rpgp 0.21 + UniFFI 0.28).
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
cargo install uniffi-bindgen --version 0.28.3 --locked
scripts/openpgp/generate-bindings.sh          # write
scripts/openpgp/generate-bindings.sh --check  # fail on drift
```

UDL mode generates bindings without compiling Rust. Native compilation and the
crate's security tests still require a Rust toolchain:

```sh
cargo test --manifest-path rust/Cargo.toml
cargo build --manifest-path rust/Cargo.toml --release
```

## Desktop (Debian/Windows/macOS)

`desktop/build.gradle` compiles the Kotlin bindings, declares JNA, stages the host
cdylib from `rust/target/release` into the distribution, and passes
`-Djna.library.path=$APPDIR/native` to the packaged launcher. `RpgpOpenPgpPort` is
the default engine; PGPainless is only a fallback when the native library is absent.

## Android

```sh
cargo install cargo-ndk --locked
scripts/openpgp/build-android-jnilibs.sh
```

`ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT`) is optional: without it the script takes the newest
NDK under `$ANDROID_HOME/ndk`. It installs the three Android rustup targets itself — `cargo ndk`
cross-compiles with the toolchain rustup already has and does **not** provide `std` for them, so
skipping that step fails with `can't find crate for \`std\`` on a clean machine.

CI runs the same script before assembling release APKs. The OpenKeychain provider
flow remains available for users who configured it; `RpgpOpenPgpPort` is the in-app
rpgp path and fails explicitly when the native library is not packaged.

## iOS / macOS Swift package

```sh
scripts/openpgp/build-ios-xcframework.sh
```

This writes `ios/Frameworks/offlineotp_openpgp.xcframework`. When that directory
exists, `ios/Package.swift` compiles the generated Swift bindings and defines
`OFFLINEOTP_OPENPGP_NATIVE`; `RustOpenPgpPort` then performs real rpgp operations.
Without the artifact the portable format layer still builds and tests, and the
OpenPGP port throws `backendUnavailable` instead of pretending to encrypt.
