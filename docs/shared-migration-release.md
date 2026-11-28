# Shared migration: cleanup ledger and release handoff

Date: 2026-11-20. Chunk 10 source work is implemented. **Not release-certified.**
The table below records local evidence, not requested-OS/device parity.

## Architecture after migration

- `shared-domain/commonMain` owns portable entry documents, framing/format/KDF policy,
  session/workflow decisions, interchange and automatic-backup rules. Platform adapters
  still own cryptography, protected storage, filesystem operations and hardware.
- `ui-contract/commonMain` owns engine-free presentation, card, command, frame and drag contracts.
  `ui-foundation/commonMain` is the sole card/code/action and drag-list renderer.
- `ui-model/jvmHostMain` maps JVM engine values into those contracts;
  `ui/jvmHostMain` and Android seams supply JVM-specific host composition.
  Their distinct adapter responsibility remains; merging modules would not remove it.
- Android and desktop retain lifecycle/service adapters. Apple hosts retain native
  storage/authentication/document/hardware integration and guarded frame/snapshot dispatch.
  Sharing rendering does not imply identical native service implementations.

## Removal ledger

| Removed | Caller/replacement evidence | Preserved coverage |
| --- | --- | --- |
| `desktop/.../control/TokenTicker.kt` | No production callers; retired desktop table wrapper. Compose uses its own lifecycle tick effects and `AwtScheduler`; shared timing remains `ScreenTimerController`. | Former two tests retained as `DesktopTokenRefreshContractTest`, exercising the shared timer with desktop token refresh and inactivity; existing common timer, desktop lifetime and AWT scheduler tests retained. |
| `ui-model/.../vault/EntryDragTarget.kt` | No production callers; renderer uses `PortableEntryDragBounds`/`portableEntryDragDirection` directly. | All four original geometry tests now exercise the portable API, including mixed heights, boundary/missing/offscreen identities, adjacent steps and authoritative order. Explicit sandbox source list updated. |

No secret-bearing adapters, storage recovery paths or live native services were deleted.

### Deliberately retained — not dead code

- SwiftPM entry/protocol/workflow and presentation oracles: independently executable
  without UIKit/Compose. Remove only after SwiftPM fixtures, real framework export,
  Xcode app integration and corresponding platform parity tests pass. Source checks
  and Kotlin JVM stubs cannot establish Kotlin/Native ABI or runtime behavior.
- JVM/Swift serialization and crypto adapters: portable rules do not implement native
  JSON, crypto or IO. Migrating policy is not proof these adapters have no callers.
- PGPainless fallback: required when native OpenPGP is unavailable, including the
  sandbox. It is not a retry after native crypto rejection; signing never falls back.
- Desktop `run`/packaging forwarding task aliases: supported compatibility entry points,
  not a second Swing UI. AWT artwork, clipboard and scheduler services remain live.
- `VaultFrameHost` and `TapSequenceController`: Android host/instrumentation callers
  remain. A roots-only source scan can incorrectly label them unused.

## Automated release wiring

The manual, unsigned `.github/workflows/android.yml` `compose-validation` job selects
these eight classes for **both Fdroid and Play**, on API 28 and 36:

| Class | Required @Test methods |
| --- | ---: |
| ComposeVaultDeviceTest | 10 |
| IssuerArtworkDeviceTest | 1 |
| AuthenticationFlowDeviceTest | 9 |
| AutomaticBackupDeviceTest | 4 |
| BackupRestoreDeviceTest | 14 |
| EntryDocumentDeviceTest | 9 |
| InterchangeCodecDeviceTest | 2 |
| GoogleMigrationDeviceTest | 2 |
| Total | 51 |

`scripts/check_compose_device_results.py` requires every manifest method to pass;
missing XML/cases, malformed XML, failures, errors and skips fail the gate. Source
regressions require the workflow class list, explicit manifest and every selected
class's `@Test` methods to agree (setup methods are excluded). This is focused
migration instrumentation, **not the complete Android instrumentation suite**, and
neither a successful build nor checker unit tests substitute for running it.

Desktop CI now includes the shared presentation gate and architecture scanner.
Desktop packaging checks require the shared domain and all shared UI layers in the
app image. Use `REQUIRE_NATIVE_PACKAGE=1 scripts/packaging/verify-desktop-build.sh`
on the equipped Linux release host to fail if jpackage is unavailable. Its existing
independent-build zip comparison remains mandatory. GUI launch, Windows/macOS
packaging and native backend availability still need platform runs.

## Notices and dependency audit

This cleanup introduces no dependencies or copied third-party source. Existing
project license, andOTP attribution, generated About notices and artwork/font
notices remain intact. `shared/about/content.json` remains the shared attribution
source; existing generation/source checks passed. Do not remove notices with an
adapter: bundled native/crypto/Compose dependencies and artwork remain in use.
On equipped release hosts run `writeDependencyInventory`, review the resulting
inventory against packaged components and notices, and retain it with release
artifacts. Resolved dependency/license inventory was not generated in this sandbox.

## Local evidence (chunk 10)

| Gate | Result |
| --- | --- |
| Core headless JVM | 422 passed |
| App headless JVM | 443 passed |
| Desktop headless JVM | 479 passed, 2 artwork/resource assumptions skipped |
| Shared presentation / desktop host tests | 178 / 3 passed |
| Sandbox behavior checks | All passed |
| Android instrumentation stub compilation | 29 files compiled; **not executed** |
| Python regression suite | 767 passed |
| Shared boundaries / shell syntax / whitespace | Passed |

Headless compilation uses substitute dependencies/Compose/Android APIs. It does
not validate actual Gradle source-set wiring, Compose compiler/runtime, resources,
layout/accessibility, Swift compilation or native device behavior.

## Release blockers / next equipped-host checklist

Do not mark these passed based on the table above. Retain logs, toolchain versions,
artifact hashes and target OS/device details for each actual run.

- [ ] Real Gradle compilation/tests for core, shared domain, all shared UI targets,
  Android both variants and desktop distributions; verify `jvmHostMain` wiring.
- [ ] Execute the manual Android migration gate and the full relevant instrumentation
  suite. Test Android 17 separately; emulator API 28/36 runs do not certify it.
- [ ] Run `swift test` in `ios/`, build the real Compose XCFramework via
  `scripts/ios/build-compose-framework.sh`, then Xcode app/tests on simulator and
  device. Validate exported APIs, resource resolution, frame/snapshot lifetime,
  background/foreground handling and native SDK availability.
- [ ] Launch packaged desktop builds on Debian x86-64, Windows 11 x86-64 and macOS 27;
  exercise native OpenPGP, file pickers, clipboard expiry, scaling and shutdown.
- [ ] On iOS 27 and Android 17, test biometric cancellation, stale auth, relock,
  NFC and USB-C hardware separately. Report PRF and transport capability distinctly;
  no emulator/stub pass proves hardware support.
- [ ] Cross-build copied-vault/password recovery, full snapshot byte identity,
  all-kind/unknown-field round trips, import/restore confirmation and stale-result
  rejection; interrupted save/rekey/migration must preserve recoverable candidates.
- [ ] Automatic backup: no locked execution, encrypted recoverable configuration,
  session-only signing keys/passphrases, visible reconfiguration after incompatible
  rekey/recovery, provider cancellation/failure and retention safety.
- [ ] Visual/accessibility checks: drag/filter identity, concealment, screen reader
  labels, large fonts, keyboard/touch, insets (especially Android navigation bar),
  small screens, thumbnails and absence of protected background snapshots.
- [ ] Independent release reproducibility/package checks, dependency/license inventory
  review and normal signing/release procedures. No release was published here.

Only then consider removing retained independent oracles, one replacement/caller
set at a time with regression evidence. The user has deferred physical-device
verification; that deferral is not a parity waiver or successful test result.
