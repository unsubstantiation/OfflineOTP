# iOS Kotlin cutover — in progress

## Required end state

Application logic, entry models, vault operations, lifecycle/orchestration and native
Apple service adapters are Kotlin/Kotlin Native. All application UI is shared
Compose. The user approved **only a minimal Swift bridge to the official YubiKit
SDK** as an exception. Existing OATH/FIDO files contain policy and orchestration;
they are not already a compliant thin bridge.

The earlier characterization of the iOS migration as complete was incorrect.
`OfflineOTPKit` and the Swift hosts remain production dependencies. Removing their
names from CI or wrapping their APIs does not replace them.

User verification: all other builds/platforms are verified. iOS build failure is
confirmed by Actions run 46113060304. That run stopped compiling the standalone
Swift test suite; it does not establish whether the subsequent Kotlin framework
and Xcode app stages would pass.

## Implemented in this follow-up

| Removed implementation | Replacement / live caller migration |
| --- | --- |
| `App/Sources/CodeFormat.swift` | `ui-contract/commonMain/.../PortableCodeFormat.kt`; the host now calls the Kotlin formatter/countdown API. The former Swift formatting suite is replaced by seven Kotlin tests, including clipboard-policy tests. |
| `App/Sources/Clipboard.swift` | `ui-foundation/iosMain/.../platform/IosClipboard.kt`; Kotlin/Native calls UIKit directly. The shared policy bounds retention by both 30 seconds and token expiry, refusing expired/non-finite timestamps. Pasteboard is local-only and OS-expiring. |
| `App/Sources/ForestPalette.swift` | Generated `IosHostPalette.kt` supplies UIKit colors from the same shared theme tokens; the generator no longer emits Swift. |
| `App/Sources/AboutInfo.swift` | Native hosts call the existing shared Kotlin `AboutInfo` export; the generator no longer emits a duplicate Swift link dictionary. Unknown IDs return an empty URL. |
| `App/Sources/QrImageReader.swift` | `ui-foundation/iosMain/.../platform/IosQrImageReader.kt`; the root calls the Kotlin QR-only Vision image adapter. No image-provider/camera permission changes and no alternate parser fallback. |

The iOS workflow no longer builds/runs the standalone SwiftPM test suite. It runs
`:ui-model:desktopTest` for shared presentation policy and builds the real Compose
XCFramework, followed by its existing Xcode app build. Xcode still compiles the
remaining production Swift package dependency: its retirement is **unfinished**.
The unexecuted legacy tests are not accepted as Kotlin/Compose validation.

The two attempted fixes to the retired package's `isEmpty()` assertions were
reverted rather than made part of the cutover. The app-test correction remains
because those tests still belong to the current app target, pending replacement.

## Remaining production replacement inventory

1. **Shared engine:** replace Swift `Entry`/drafts/JSON/material models and their
   callers with shared Kotlin domain values. Replace token generation and crypto
   orchestration with shared algorithms plus Kotlin/Native platform crypto ports;
   preserve RFC/golden vectors and existing password/KDF/wire policy.
2. **Vault IO:** port Keychain/biometry, protected files/settings, bounded/security-
   scoped document reads, atomic persistence, migration/rekey/recovery and full
   vault-copy operations. Do not lose last-recoverable-file handling or cancellation.
3. **Application workflows:** replace `VaultStore`, `SessionAutomaticBackup`,
   `AutomaticBackupCredentialStore`, native shared-adapter wrappers and
   `ComposeVaultHost`/`ComposeUnlockHost` coordinators. Preserve generation/identity
   checks, no locked backup, recoverable encrypted config and session-only signing.
4. **UIKit host/services:** replace root/window/app lifecycle, native picker,
   live camera scanner and privacy cover. Palette/About bindings are already Kotlin.
   A process entry point may use a minimal Objective-C launcher for Kotlin UIKit
   hosting; that is not an excuse to retain Swift application orchestration.
5. **YubiKit boundary:** expose only SDK operations/data through the approved
   Swift bridge. Move timeout/cancellation authority, capability policy, OATH naming
   validation and PRF/unlock decisions into Kotlin. Preserve NFC and USB-C separately.
6. **Build retirement:** remove OfflineOTPKit from Xcode/SwiftPM, its old tests and
   obsolete generators/ratchets once their production callers have been replaced.
   Keep only SDK-bridge tests in Swift, if needed; Kotlin owns shared behavior tests.

This is an inventory, not completed work. The package currently has roughly 9,300
lines and the app hosts roughly 8,500 lines before these removals. A wholesale
file deletion would remove working features, not implement this cutover.

## Evidence limits

Shared formatting/clipboard policy is exercised on the JVM. Source guards check
caller migration, deletion and workflow selection. Kotlin/Native service signatures
are also included in the existing JVM stub seam, but those stubs cannot validate
Apple SDK signatures, Swift export names or service behavior. A real framework
build plus Xcode app build is required for those; device tests remain required for
pasteboard expiry, image decoding, security, hardware and lifecycle behavior.
