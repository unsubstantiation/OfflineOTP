# Incomplete-feature audit — 2026-11-06

This pass continued the half-present-feature hunt with one concrete target: the unified
OpenPGP backend. The previous audit left it as a scaffold: a Rust crate with stub
markers, reflective JVM adapters that could not call the generated UniFFI surface,
an iOS port that always threw `backendUnavailable`, and workflows that only
`cargo check`ed the host crate.

## Completed

- `rust/offlineotp-openpgp` now performs real rpgp 0.21 cryptography. Exports are
  binary SEIPDv1 + AES-256; imports accept binary or ASCII-armored OpenPGP messages.
  Secret keys may be passphrase-protected. Recipient certificates are binding-checked
  and revoked/expired encryption keys are not selected. Decryption rejects plaintext,
  tampered ciphertext, wrong keys, blank output and oversized documents, and reads
  compressed messages through a bounded streaming path instead of `as_data_vec()`.
- The foreign-language contract is UDL (`src/offlineotp_openpgp.udl`). UniFFI 0.28.3
  generated Kotlin/JNA and Swift bindings are committed, with `NativeApi.kt` adapting
  `List<UByte>` to the JVM `ByteArray` ports. `scripts/openpgp/generate-bindings.sh`
  regenerates or checks them.
- Desktop defaults to `RpgpOpenPgpPort`; PGPainless is only an absent-native-library
  fallback. Gradle compiles the bindings, declares JNA, stages the host cdylib and
  passes `-Djna.library.path=$APPDIR/native` to the packaged launcher.
- Android keeps the complete OpenKeychain provider flow and now also packages the
  in-app rpgp path: CI builds per-ABI `jniLibs` with `cargo ndk`, Gradle compiles the
  bindings/JNA, and `RpgpOpenPgpPort` fails explicitly when the library is absent.
- iOS builds an XCFramework in CI. When `ios/Frameworks/offlineotp_openpgp.xcframework`
  exists, Package.swift compiles the generated Swift bindings and defines
  `OFFLINEOTP_OPENPGP_NATIVE`; `RustOpenPgpPort` then calls rpgp. Without the artifact
  the portable format layer still builds/tests and the port throws `backendUnavailable`.
- All five platform workflows run `cargo test` and `cargo build --release`.

## Verification limits

The local environment has no native Rust toolchain, Android SDK, Xcode or Gradle
distribution. Rust syntax/format was checked with a wasm rustfmt, and UniFFI bindings
were generated locally with uniffi-bindgen 0.28.3 (`generate-bindings.sh --check`
passes). The crate's security tests and native packaging are CI gates; they are not
claimed as locally executed here. The no-Gradle JVM gates continue to exercise the
explicit fallback/unavailable paths.

Local gates after the change:

| Gate | Result |
| --- | --- |
| `python3 -m unittest discover -s scripts/tests` | 401 tests, OK, 2 skipped |
| `scripts/run-jvm-tests.sh` | 325 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/run.sh` | all sandbox checks passed |
| `scripts/kotlin-sandbox/compile-desktop.sh` | 183 tests, 171 passed, 0 failed, 12 skipped |
| `scripts/kotlin-sandbox/compile-core.sh` | 304 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 20 androidTest files, COMPILE OK |
| `scripts/openpgp/generate-bindings.sh --check` | UniFFI bindings checked |

## Still optional/product-scoped

Backup pruning, browser-password editing/re-export, Nitrokey native OTP, paper
backups and preferences export remain the previously documented optional gaps; they
are not half-present stubs in the same sense as the OpenPGP backend was.
