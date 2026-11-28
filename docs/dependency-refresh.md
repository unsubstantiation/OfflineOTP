# Dependency refresh — 2026-11-20

Direct dependencies were refreshed against published releases available on this date.
This is not a claim that every transitive dependency is current or every build passes.

| Area | Selected versions |
| --- | --- |
| Build | Kotlin 2.4.21, Gradle 9.7.0, AGP 9.3.1, AboutLibraries 15.3.0 |
| Compose | Plugin/runtime/UI/foundation/desktop 1.12.2, Material3 1.12.0-alpha03 |
| JVM | Coroutines 1.11.0, JSON 20260814, JNA 5.19.1, YubiKit 3.3.0 |
| Android | Compile SDK 37, Lifecycle 2.11.0, Biometric 1.4.0-rc01, Compose tests 1.12.1, Test core/runner 1.7.0, Test JUnit 1.3.0 |
| Android tooling | Build-tools 37.0.0, command-line tools 22.0 (15859902), NDK r30 (30.0.16248370) |
| Rust | pgp 0.21.1, UniFFI 0.31.0, thiserror 2.0.21, zeroize 1.9.2; CI toolchain 1.100.0 |
| Apple | SwiftLog 1.17.2 and YubiKit 1.5.0, pinned by commit; iOS runner macos-26/Xcode 26.6, Swift 6.2+ |
| Development/CI | Java 17.0.21.1, sandbox JDK4PY 25.0.5.1 with checksum, updated immutable action pins |

AboutLibraries 15 removed the legacy View library. The app already renders generated
license JSON in shared Compose; obsolete View dependencies/theme resources were removed.
Android's target/minimum SDK and Apple's deployment target are unchanged.

## Compatibility decisions and remaining holds

- **Supported build chain:** Gradle 9.7.0 and AGP 9.3.1 are the upper bounds in
  [Kotlin 2.4.21's compatibility table](https://github.com/JetBrains/kotlin-web-site/blob/master/docs/v.list).
  AGP was deliberately aligned down from the pre-existing, unsupported 9.5.0 pin.
  Newer Gradle/AGP releases should move together with a supporting Kotlin release.
- **Actual Compose artifacts:** common libraries are explicitly pinned rather than
  relying on deprecated plugin aliases with older defaults. Desktop retains the
  alias only for platform selection and strictly overrides its version. Material3's
  separate alpha version is the one in the Compose 1.12.2 release's component table.
  Biometric moves from an old alpha to the published 1.4.0 release candidate.
- **JVM bytecode:** Android's Compose consumers (`app`, `ui`, `ui-foundation`)
  now target Java/Kotlin 11, matching desktop Compose. Target 1.8 cannot inline
  JVM 11 library bytecode. Toolkit-free modules may retain target 1.8; no Android
  SDK level or native target changed. The compiler regression reproduces the
  mismatch and verifies target 11 emits classfile version 55; this is not a full
  Gradle/CodeQL pass. Follow-up Python regressions: 802 passed; scanning is unchanged.
- **UniFFI:** runtime/build dependencies and all four Kotlin/Swift/header/module-map
  outputs move together from 0.28.3 to 0.31.0. The generator checks its own version
  and uses real Cargo workspace metadata without resolving external dependencies.
  0.32.2 is newer, but its generator could not be built/downloaded through the allowed
  hosts. The obtainable 0.31.0 generator was used instead; no generated file was
  hand-edited or relabeled as a newer version. Rebuild native libraries with these bindings.
- **rand:** stays at 0.8.6 to match pgp's RNG trait generation. Legacy libraries with
  no verified newer release are retained rather than assigned guessed versions.

## Validation and environment limits

- Wrapper provenance/distribution checksum check passed; distribution bytes were
  not downloaded locally.
- Python regressions: 791 passed. Desktop sandbox: 482 passed, 2 skipped. Shared UI
  sandbox type-checks and all 3 headless platform tests passed.
- UniFFI regeneration and `generate-bindings.sh --check` passed. Generated Kotlin
  **and** `NativeApi.kt` compiled with Kotlin 2.4.21 against the real JNA 5.19.1 JAR
  from JNA's release-tagged `dist/` directory.
- These checks do not establish native ABI, SDK, packaging, or device compatibility.
  Gradle's distribution and Google Maven/SDK hosts are inaccessible locally; no
  Rust compiler or Xcode is available. Real Gradle/Rust/iOS validation remains required.
- A GitHub Actions dispatch attempt was rejected with HTTP 403 (integration
  permissions). The proposed write-enabled generation job was removed; existing
  build/scanning gates remain intact. Reconnect GitHub in Arena to enable dispatch.

Release references: [Compose](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.2),
[AboutLibraries](https://github.com/mikepenz/AboutLibraries/releases/tag/15.3.0),
[YubiKit](https://github.com/Yubico/yubikit-android/releases/tag/3.3.0),
[AndroidX published libraries](https://github.com/androidx/androidx/blob/89aa3683850dc14851761cdd08bd47d8df7c7f25/docs-public/build.gradle),
[AndroidX build-tools](https://github.com/androidx/androidx/blob/b6d04695a84a2b4374b4102524871207c2f197dc/buildSrc/public/src/main/kotlin/androidx/build/AndroidXConfig.kt),
[AndroidX Test](https://github.com/android/android-test/blob/axt_2026_11_09/build_extensions/axt_stable_versions.bzl),
[Android CLI](https://github.com/android-actions/setup-android/releases/tag/v4.0.4),
[NDK](https://github.com/android/ndk/releases/tag/r30),
[UniFFI](https://github.com/mozilla/uniffi-rs/tree/v0.31.0),
[SwiftLog](https://github.com/apple/swift-log/tree/1.17.2).
