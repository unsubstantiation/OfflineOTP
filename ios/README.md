# OfflineOTP for iOS

The app uses shared Kotlin/Compose presentation from `:ui-foundation`. Production
Swift engine/host dependencies still exist in `OfflineOTPKit` and `App/Sources`;
their Kotlin replacement is **in progress**, not complete. Only a narrow bridge to
the official YubiKit SDK is intended to remain in Swift.

**Status:** the last reported iOS build failed. Subsequent source/workflow fixes
have local checks but no recorded successful native run. All non-iOS builds/platforms
are user-reported verified. CodeQL also exposed iOS metadata compilation errors;
the [source fixes](../docs/ios-kotlin-cutover.md#metadata-compilation-follow-up) await a successful rerun.

## Requirements

- macOS with full Xcode, an iPhoneOS SDK and Swift 6.1+ (required by pinned YubiKit).
- JDK 17; Android SDK platform 36/build-tools 36.0.0 for root Gradle configuration.
- Rust/rustup, XcodeGen and Python 3; network access to resolve dependencies.
- No signing identity for the unsigned CI device build. Device installation and
  distribution require an appropriate signing/provisioning process.

The deployment minimum is **iOS 17**, not the CI runner version or the requested
iOS 27 verification target. `project.yml` is the source of truth; the generated
Xcode project, frameworks, icon output and build products are not committed.

## Build locally

From the repository root:

```sh
bash scripts/openpgp/build-ios-xcframework.sh --arm64-only
python3 scripts/ios/generate-app-icon.py
bash scripts/ios/build-compose-framework.sh
cd ios
xcodegen generate
xcodebuild -project OfflineOTP.xcodeproj -scheme OfflineOTP \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath build/DerivedData ARCHS=arm64 \
  LD_GENERATE_MAP_FILE=YES CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
```

Build both frameworks **before** Xcode resolves the local package: its manifest
selects native OpenPGP support based on the framework's presence. Missing native
OpenPGP must not silently become a release without cryptography.

`--arm64-only` supplies device ARM64, ARM64 simulator and macOS ARM64 Rust slices.
Omit that option if Intel simulator/macOS slices are needed. The Compose framework
script assembles the configured Apple slices. No SwiftUI UI fallback exists.

## GitHub workflow

Run **iOS build** (`.github/workflows/ios.yml`) manually on the branch being tested.
The runner input selects a supported macOS image; the workflow:

1. Requires Linux OpenPGP tests/build to pass.
2. Installs JDK 17 and Android SDK 36, and checks Xcode/Swift/iPhoneOS SDK availability.
3. Builds the Rust XCFramework, checks generated vectors/catalog, and generates the icon.
4. Runs `:ui-model:desktopTest` and builds/stages the Compose XCFramework.
5. Generates the Xcode project and builds an unsigned ARM64 Release device app.
6. Packages and inspects `OfflineOTP-ios-unsigned.ipa` before uploading it.

The job limit is 75 minutes; individual long steps are also bounded. This is a
ceiling, not expected runtime. Rust-framework, Gradle and Xcode output shares
`ios/build-log.txt`; diagnostics appear in the run, not a separate log artifact.
The workflow does **not** run standalone `swift test`, simulator XCTest, signing,
notarization or release publishing. Remaining Swift production dependencies still
compile as part of the Xcode app build.

## What the IPA check establishes

`scripts/ios/report-ipa-contents.py` inspects the actual archive for the app bundle,
ARM64 executable, icon and linked Rust OpenPGP evidence. The workflow also checks
the device platform. Static frameworks are linked into the executable; bundle size
alone does not establish completeness. Compose resources and bundled issuer
artwork may be present; no fixed IPA size is promised.

An unsigned IPA is not directly installable or App Store-ready. Native build
success also does not establish camera, VoiceOver, biometric, hardware-key,
background/foreground or vault-recovery behavior.

## Tests and generated data

Shared presentation/policy tests:

```sh
./gradlew --no-daemon :ui-model:desktopTest
python3 scripts/ios/generate-token-vectors.py
python3 scripts/ios/generate-thumbnail-catalog.py
python3 scripts/generate-theme.py --check
python3 scripts/generate-about.py --check
```

The vector/catalog scripts check freshness by default; use `--write` only after an
intentional source change. Theme/About content comes from `shared/` and no longer
emits duplicate Swift palette or link dictionaries.

`ios/Tests` retains legacy Swift package tests during engine replacement; they are
not the Kotlin/Compose CI gate. `App/Tests` still contains integration/bridge tests,
including readers of shared golden fixtures. Building the app does not execute them.
On an equipped Mac, generate the project and run the `OfflineOTP` scheme's test
action against an available simulator; no simulator/device pass is claimed here.

## Architecture and safety

- Shared Compose owns app-rendered screens/forms. Apple services own OS pickers,
  camera recognition, protected storage, authentication and lifecycle integration.
- Formatting/countdown policy and About links are shared Kotlin. Clipboard,
  QR-image decoding and native palette bindings now have Kotlin/Native replacements.
- `VaultStore`, crypto/storage adapters and larger coordinators are still Swift
  production dependencies. Do not delete them without migrating callers.
- Biometric/security-key unlock is local convenience. Keep the vault recovery
  password and a tested portable backup; no shortcut substitutes for recovery.

See the [iOS contract](../docs/ios-client-plan.md),
[hardware boundaries](../docs/ios-hardware.md),
[portable vault format](../docs/portable-vault-container.md) and
[current validation checklist](../docs/shared-ui-device-checklist.md).
