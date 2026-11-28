# Native validation handoff

The user reports all non-iOS builds/platform checks verified. The iOS build is
confirmed failing; later workflow/source fixes have not been validated by a
successful native run. Local source and stub checks are not native evidence.

## Run the workflows

Select the branch under review, not a historical session branch:

```sh
gh workflow run ios.yml --ref <branch>
gh workflow run compose-desktop-validation.yml --ref <branch>
gh workflow run android.yml --ref <branch>
```

- **iOS build:** provisions Java/Android dependencies, checks Xcode/Swift 6.1+,
  builds Rust and Compose XCFrameworks, runs shared Kotlin presentation tests,
  builds the unsigned ARM64 device app and checks the IPA. No standalone legacy
  SwiftPM tests, simulator XCTest, signing or publishing.
- **Compose desktop validation:** real Gradle tests/distribution checks on the
  workflow's configured Linux, Windows and macOS runners.
- **Android build:** manual migration validation selects 51 tests across eight
  classes on F-Droid/API 28 and Play/API 36. The report checker rejects missing,
  skipped, failed and errored cases. Release signing remains separately gated.

The integration's last dispatch attempt returned HTTP 403; it did not start a run.
An authorized user can dispatch manually. Never add tokens to the repository or
weaken permissions to bypass missing Actions access.

## Record evidence

For each result, retain the commit/artifact hash, run URL, toolchain, target OS,
device/architecture and checks actually executed. Distinguish a compile from tests
running, and tests running from physical hardware validation. SDK/deployment minima
and runner labels do not establish coverage of a requested OS version.

## iOS follow-up

1. Rerun `:ui-foundation:compileIosMainKotlinMetadata` and unchanged CodeQL autobuild
   after the [source fixes](ios-kotlin-cutover.md#metadata-compilation-follow-up).
2. Confirm framework compilation and Swift exports against the real Apple SDK.
3. Confirm the Xcode app links required Compose and OpenPGP code and bundles resources.
4. Run relevant integration tests on simulator/device; app compilation alone does not run them.
5. Exercise lifecycle/privacy, Files-provider cancellation, password recovery,
   interrupted writes, biometric cancellation and NFC/USB-C capabilities separately.
6. Continue the [Kotlin cutover](ios-kotlin-cutover.md); a green legacy Swift suite
   is not an acceptance criterion for the shared implementation.

Use the [device checklist](shared-ui-device-checklist.md),
[iOS build guide](../ios/README.md) and [release ledger](shared-migration-release.md).
