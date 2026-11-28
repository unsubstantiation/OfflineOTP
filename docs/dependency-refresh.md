# Dependency refresh — 2026-11-20

This is a direct-pin refresh, not a claim that every transitive dependency is current.

| Area | Updated pins |
| --- | --- |
| Build/UI | Gradle 9.7.0 (wrapper files and hashes), Compose 1.12.2, AboutLibraries 15.3.0 |
| JVM | Coroutines 1.11.0, JSON 20260814, JNA 5.19.1, YubiKit 3.3.0 |
| Android | Compile SDK 37, Lifecycle 2.11.0, Compose tests 1.12.1, Test core/runner 1.7.0, Test JUnit 1.3.0 |
| Android tooling | Command-line tools 22.0 (15859902), NDK r30 (30.0.16248370) |
| Rust | pgp 0.21.1, thiserror 2.0.21, zeroize 1.9.2; CI Rust 1.100.0 |
| Apple | SwiftLog 1.10.0, pinned by commit |
| Development/CI | Java 17.0.21.1, sandbox JDK4PY 25.0.5.1 with checksum, updated immutable action pins |

AboutLibraries 15 removed the legacy View library. The app already renders generated
license JSON in shared Compose; its obsolete View dependency and theme resources
were removed together. Android's target/minimum SDK levels are unchanged.

## Holds and unresolved checks

- Kotlin stays at 2.4.21. Gradle 9.7.0 is the maximum supported version in
  [Kotlin's compatibility table](https://github.com/JetBrains/kotlin-web-site/blob/master/docs/topics/gradle/gradle-configure-project.md).
  The existing AGP 9.5.0 pin exceeds that table's AGP 9.3.1 maximum; this refresh
  does **not** establish compatibility of that existing combination. Real Gradle
  configuration/build validation remains required before release.
- UniFFI stays at 0.28.3 across runtime, generator, and checked-in Kotlin/Swift
  bindings. Upgrading to 0.32.2 requires regenerating all bindings and testing the
  native ABI; no Cargo toolchain is available in this sandbox. Do not update only
  the runtime pin.
- rand stays at 0.8.6 to match pgp's RNG trait generation. SwiftLog stays at 1.10.0
  for Swift 6.1; newer releases require Swift 6.2. The YubiKit Swift commit already
  points at 1.5.0.
- Biometric's existing 1.2.0-alpha05 pin is not promoted to a newer prerelease.
  Android build-tools 36.0.0 and other unchanged Google Maven pins have not been
  independently confirmed current: this environment cannot access Google Maven
  or the Android SDK repository. No assertion of a complete Android audit is made.

## Validation

- Wrapper provenance/checksum verifier passed.
- Python regression suite: 786 passed.
- Kotlin sandbox checks passed; desktop sandbox: 482 passed, 2 skipped.
- These sandbox checks are not real Gradle, SDK, native ABI, or device validation.
  `./gradlew --version` could not download the distribution because outbound access
  to services.gradle.org fails TLS. Rust/native builds and iOS/Xcode validation
  remain outstanding. Scanning and platform compilation gates were not removed.

Release references: [Compose](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.2),
[AboutLibraries](https://github.com/mikepenz/AboutLibraries/releases/tag/15.3.0),
[YubiKit](https://github.com/Yubico/yubikit-android/releases/tag/3.3.0),
[AndroidX Test stable pins](https://github.com/android/android-test/blob/axt_2026_11_09/build_extensions/axt_stable_versions.bzl),
[Android CLI tools](https://github.com/android-actions/setup-android/releases/tag/v4.0.4),
[NDK](https://github.com/android/ndk/releases/tag/r30).
