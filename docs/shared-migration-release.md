# Migration cleanup and release handoff

## Current status

The user reports all non-iOS builds/workflows and platform checks verified. The iOS
build is confirmed failing; subsequent fixes await a successful native run. The
shared-code migration slices are implemented, but the broader iOS engine/host
cutover is **not complete**. See [current status](shared-ui-migration-status.md).

Historical headless/stub test totals in individual handoffs describe their recorded
revision. They are not native validation or the current regression-test count.

## Cleanup ledger

| Removed | Replacement and retained coverage |
| --- | --- |
| Desktop `TokenTicker.kt` | No production callers; former tests retained as `DesktopTokenRefreshContractTest` against shared timers/token refresh |
| JVM `EntryDragTarget.kt` wrapper | Renderer calls portable drag geometry directly; four geometry tests retained |
| iOS `CodeFormat.swift` | Shared `PortableCodeFormat`; Kotlin tests replace the old Swift formatting suite |
| iOS `Clipboard.swift` | Kotlin/Native `IosClipboard` with shared bounded expiry policy |
| iOS `QrImageReader.swift` | Kotlin/Native Vision QR-only adapter |
| iOS `ForestPalette.swift` / `AboutInfo.swift` | Kotlin native palette/shared About exports; generators no longer emit Swift copies |
| Standalone SwiftPM CI test stages | Shared Kotlin presentation tests, native framework builds and the actual Xcode app build remain required |

The last five service/binding replacements are source-complete changes, not claims
of a successful native iOS build. See the [remaining cutover inventory](ios-kotlin-cutover.md).

## Live code that is not an obsolete duplicate

- Platform crypto, storage, permissions and lifecycle adapters still have callers.
- JVM/Swift serializers and models remain where production callers have not migrated.
  The accepted direction is Kotlin replacement, not indefinite retention of a second engine.
- PGPainless supports missing-native desktop environments; signing/crypto rejection
  must not trigger an alternate-engine retry.
- Desktop run/package aliases forward to Compose; AWT services are not a Swing UI fallback.
- Some Android compatibility controllers still own restoration/listener behavior.

The user approved only a **minimal Swift YubiKit SDK bridge** as the final exception.
Legacy Swift oracles are no longer an acceptance gate for shared Kotlin. Remove their
package/tests when production dependencies are replaced, not by hiding compile errors.

## Release wiring

- Android's manual migration gate selects **51 tests across eight classes**, on
  F-Droid/API 28 and Play/API 36. Missing XML/cases, malformed reports, skips, failures
  and errors fail the checker. This is not the full instrumentation suite.
- Desktop CI includes shared UI/headless and boundary checks. App images require
  shared domain/UI jars and native dependencies. On the equipped Linux release host,
  use `REQUIRE_NATIVE_PACKAGE=1 scripts/packaging/verify-desktop-build.sh` to disallow
  a missing-jpackage skip and compare independently built distribution ZIPs.
- iOS requires passing Linux Rust tests, explicit Java/Android/Xcode prerequisites,
  Rust/Compose frameworks, the unsigned device app and IPA inspection. No standalone
  SwiftPM tests, simulator XCTest, signing or publishing run in this workflow.

## Candidate-specific checklist

Retain artifact/commit hashes, run URLs, toolchain, OS/device and actual outcomes.
Apply these checks to changes since the user-verified non-iOS baseline; do not
relabel that baseline as untested.

- [ ] Successful follow-up iOS framework/Xcode build and required integration tests.
- [ ] Complete Kotlin cutover without losing entry kinds, unknown fields or native services.
- [ ] Cross-build copied-vault/password recovery and separate export/restore routes.
- [ ] Interrupted save/rekey/migration retains recoverable files; stale results cannot install.
- [ ] Automatic backups remain unlocked/session-bound, with encrypted config and session-only signing.
- [ ] Device privacy/lifecycle, accessibility/insets, camera and provider cancellation checks.
- [ ] NFC and USB-C, OATH and PRF/UV capabilities checked separately on applicable hardware.
- [ ] Package launch/reproducibility, dependency inventory, notices and normal signing/distribution review.

## Notices

Existing project license, andOTP attribution and artwork/font notices remain binding.
Theme/About metadata comes from `shared/`; generator freshness is checked in tests.
Generate `writeDependencyInventory` on an equipped host and review packaged
components against their notices. No migration, source test or user report alone
constitutes a security audit, signing approval or permission to remove notices.
