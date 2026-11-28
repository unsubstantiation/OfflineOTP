# Shared-code consolidation plan

Chunks 1–10 established shared domain/workflow rules, portable Compose rendering and
cleanup gates. **The iOS engine/host cutover is not complete.** See
[current status](shared-ui-migration-status.md), [ownership](shared-ui-ownership.md),
[release handoff](shared-migration-release.md) and [iOS inventory](ios-kotlin-cutover.md).
The user reports non-iOS builds/platforms verified; the iOS build is confirmed failing.
Per-chunk handoffs record implementation-time evidence, not the latest test totals.

## 1. Product target matrix

| Product target | CPU | Current implementation / build evidence to obtain |
| --- | --- | --- |
| Debian desktop | **x86-64 / amd64**, not 32-bit | One Compose/JVM desktop app; `debian.yml` packages a Debian 12 x64 image. Validate packaged launch, native libraries, file access and hardware on Debian. |
| Windows 11 desktop | **x86-64**, not 32-bit | Same desktop app; `windows.yml` packages x64 on a Windows 2022 runner. Runner success alone is not Windows 11 runtime validation. |
| macOS 27 desktop | ARM64 is the current packaged architecture | Same desktop app; `macos.yml` currently packages ARM64 using `macos-15`. macOS 27 is requested runtime coverage, not established evidence. Intel/universal packaging is not added by this plan. |
| iOS 27 | Device ARM64; ARM64 simulator for development | Shared Compose presentation plus Swift engine/host. Current app deployment minimum is iOS 17; `ios.yml` runs shared Kotlin presentation tests and builds the frameworks/device app. iOS 27 simulator/device execution remains a release gate. |
| Android 17 | Preserve existing release ABIs; x86-64 emulator for tests | F-Droid and Play variants must share behavior. Current app compile SDK is 36 and target SDK is 35; focused workflow emulator checks use API 28/36. Android 17 runtime coverage and any SDK upgrade are separate work, not implied by this plan. |

OS target labels above are the user's requested coverage, not claims that matching SDKs/runners
are installed or supported by the current dependency versions. Never equate OS marketing versions,
Android API levels, SDK deployment minima, compiler targets and CI runner labels.

No 32-bit desktop target is planned. macOS CPU scope follows the existing ARM64 artifact pending
an explicit request to add Intel; this does not block shared-code migration. Do not raise minimum
OS versions, drop Android ABIs or alter release packaging as a side effect of consolidation.

## 2. Actual dependency graph (before migration)

An arrow means **depends on**; these are Gradle project edges, not a claim that all code is portable.

```text
:app             -> :core, :ui-model, :ui
:desktop-compose -> :core, :desktop, :ui
:desktop         -> :core, :ui-model
:ui              -> :core, :ui-model, :ui-foundation
:ui-model        -> :core, :ui-contract
:ui-foundation   -> :ui-contract
:ui-contract     -> (none)
:core            -> (no other project; JVM libraries remain)
```

- `core/build.gradle`: JVM-only engine; Java crypto/I/O, `org.json`, Commons Codec and JVM
  Yubico SDK dependencies prevent simply adding an iOS target.
- `ui-model/build.gradle` and `ui/build.gradle`: Android/desktop only, despite `commonMain`
  source directories. They depend on `:core`. Do not call these iOS-compatible today.
- `ui-contract/build.gradle`: truly multiplatform values/presentation rules, no Compose dependency.
- `ui-foundation/build.gradle`: truly multiplatform Compose forms/screens and iOS presentation
  bridge; no JVM engine dependency. These two modules have device/simulator iOS targets.
- `ios/Sources/OfflineOTPKit`: independent Swift domain/crypto/persistence implementation.
- `ios/App/Sources`: native services **and** workflow orchestration, especially `VaultStore.swift`,
  `ComposeVaultHost.swift`, `ComposeUnlockHost.swift`, `SessionAutomaticBackup.swift`.
- `app/.../Activities`, `Tasks`, `Utilities`: Android service integration **and** duplicated workflow
  orchestration. Shared forms do not imply shared transactions or session behavior.
- `desktop/.../control`: desktop controllers mix reusable workflow decisions with desktop execution.
- `rust/offlineotp-openpgp` and `rust/bindings`: existing OpenPGP backend/bindings. Retain this
  boundary; do not introduce another general-purpose engine language.

## 3. Duplication map and replacement order

Kotlin engine paths below are relative to `core/src/main/kotlin/com/fairuniversal/offlineotp`;
Swift counterparts are under `ios/Sources/OfflineOTPKit` unless noted.

| Chunk | Existing sources / responsibility | Destination and preservation gate |
| --- | --- | --- |
| 2: portable entries | `Database/Entry*`, `Database/VaultJson*`, `Utilities/InputPolicy`, `EntryUsage`, material types; Swift `Entry`, `EntryDraft`, `JSONValue`, `VaultDocument`, `InputPolicy`, material types | Portable domain types, explicit defaults/extensions and serialization adapters. Preserve counters, metadata, unknown fields and validation semantics before replacing callers. |
| 3: vault/backup protocol | `VaultContainer`, `EncryptedBackupHeader`, `EncryptedBackupCodec`, `CryptoPrimitives`, `PasswordVault`; Swift `VaultContainer`, `EncryptedBackup`, `VaultCrypto`, `PasswordVault` | Shared framing/KDF bounds/format dispatch with vetted crypto backends; exact-copy backups remain byte-for-byte copies. No wire-format redesign. |
| 4: restore workflow | Android `EncryptedRestoreTask`, `FirstRunBackupRestoreTask`, backup/setup activities; desktop `DesktopEncryptedRestore` and first-run host; Swift app `VaultStore`/Compose hosts | Shared read/validate/preview/confirm/install state machine; native picker/storage calls only in adapters. No mutation before confirmation; no wrong-format fallback. |
| 5: session/unlock | `VaultSession`, `SessionContracts`, `AuthenticationResultPolicy`; Android authentication task/activity; desktop unlock/lifetime controllers; Swift app unlock/store/biometric coordinators | Shared generation-bound session transitions. Preserve fail-closed cancellation, stale-result rejection, recovery passwords, lock cleanup. |
| 6: persistence/migration | `VaultStoragePorts`, `VaultContracts`, `PasswordVault`; Android `DatabaseHelper`, `Settings`, persistence tasks; Swift vault/store | Shared sequencing above native atomic storage. Interrupted save/rekey/migration never silently replaces the only recoverable file. |
| 7: import/export | OTP/provisioning, Google migration, CSV, LinSCP, preferences, password/email/SSH material codecs; corresponding Swift files and native host handlers | Portable codecs and rules first, shared orchestration second. Keep OpenPGP backend and native document access. |
| 8: automatic backup | `AutomaticBackupCredentialCodec`, retention/policies; desktop automatic-backup controller; Android persistence/backup tasks; Swift app `SessionAutomaticBackup`/credential store | Shared configuration, retention and scheduling decisions, native execution mechanisms. No locked backups; signing secrets remain session-only. |
| 9: UI/presentation | `:ui-model`, `:ui`, `:ui-foundation`, native host mappings | One portable presentation/UI stack after JVM dependency barriers are gone. Merge modules only when their distinct responsibility disappears. |
| 10: cleanup/release | Temporary conversion layers and retired native/JVM/Swift implementations | Delete after all callers migrate and replacement tests/native gates pass. Update notices, packaging, architecture docs. |

Chunk 2 should itself proceed as a narrow vertical slice: establish one portable entry contract,
round-trip it through existing JVM/Swift adapters and golden fixtures, then migrate the remaining
entry kinds. Do not move the whole core or replace crypto during that chunk.

## 4. Target layers and interface contracts

Names below describe responsibilities, **not new empty Gradle modules or unused interfaces**.
Introduce each concrete API in the chunk that supplies its first implementation and caller.

```text
shared-ui -> shared-application -> shared-domain
                   |                    |
                   +-- platform ports --+
Android host / Desktop host / Apple host supply adapters
```

### Domain

Portable immutable values, entry identity/validation, document formats, algorithm-independent
policies. No Compose, native SDK, Java/Swift platform types, filesystem handles or UI callbacks.
No `JSONObject`, `SecretKey`, Yubico session, `Context`, `NSData` or native URL in public contracts.
Use typed errors, not localized strings or engine exceptions crossing the boundary.

### Application

Own restore/unlock/save/rekey state machines and generation-bound authorization. Platform ports
return portable data/results. Keep secrets out of display state, persisted preferences, logs and
serializable exception payloads. Never promise physical string/COW zeroization.

| Port responsibility | Shared contract / authority rule | Native adapter work |
| --- | --- | --- |
| Atomic vault storage | Bounded reads, version/identity token, transaction result; commit only with current authority | Private paths, locks, atomic replace, durability, rollback/journal recovery |
| Document access | Opaque session-scoped document reference and bounded byte transfer | SAF, Files/security-scoped grants, desktop chooser, permissions and cancellation |
| Crypto backend | Named approved algorithms/parameter values, bytes and typed failures; shared header/AAD/KDF policy | Vetted crypto primitives and secure random; no silent weaker fallback |
| Secure local unlock | Vault-bound enrollment identity, capability and operation result | Keystore/Keychain/biometrics; authenticate returned key against the actual file |
| Hardware access | Report NFC/USB/OATH/PRF capabilities separately; scoped operations/results | Yubico transport discovery, permission prompts, connections, cleanup |
| Clock/scheduling | Monotonic elapsed time for deadlines; explicit wall-clock timestamps where needed | Foreground/background execution, lifecycle, timers; no new locked-vault authority |
| Camera/clipboard | Explicit user action, bounded result, cancellation/clear policy | Camera permissions, scanners, clipboard integration |

Cancellation invalidates result delivery and future authority; it cannot undo a provider write,
atomic promotion or hardware operation already started. Recheck session generation and destination
identity at commit boundaries. Do not pass raw native objects into common models to avoid designing
these contracts. Don't create a giant platform-service singleton or scatter OS-name checks in UI.

### UI and hosts

Shared UI renders state and emits intents. Hosts own window/activity/view-controller lifecycle,
OS integrations, dependency wiring and packaging. Capability-driven UI must distinguish unsupported,
unavailable, denied and busy—not hide real feature gaps behind an OS check.

## 5. Migration rules / architecture ratchet

Run `python3 scripts/check_shared_boundaries.py` or the full Python unittest suite.
The new guard pins the **current** project dependency graph and native-compatible common-source
import boundaries and checks their Android/desktop/iOS targets. It catches qualified platform-package
uses as well as imports. `scripts/tests/test_shared_architecture_boundaries.py` tests the checker
with invalid inputs so a passing scan isn't the only evidence that it works.

Existing `test_core_module_boundary.py`, `test_desktop_module_boundary.py`,
`test_native_compose_foundation.py` and `test_compose_ui_module.py` remain in force. The new ratchet
does not pretend the legacy JVM modules are already portable. Update its reviewed graph in the same
change that migrates an edge; never relax an old boundary just to make a migration compile.
Source scanning is intentionally lightweight, not a Kotlin/Gradle parser or a replacement for
compiler-enforced dependencies. New third-party common libraries require explicit review and real
multiplatform compilation. No CI triggers or release automation are changed in chunk 1.

Use adapters, not dual writes, while replacing an implementation. Keep old/new behavior comparison
fixtures until every caller moves. No automatic migration on ordinary open, wire-format change,
minimum-OS bump, or security-policy change hidden inside structural work.

## 6. Verification map

| Boundary being migrated | Existing evidence to preserve / expand |
| --- | --- |
| Entry documents and presentation | Core/desktop entry-document tests; Swift `EntryDocumentTests`, `EntryExtensionContractTests`; golden resources; UI-model/shared UI tests |
| Vault files and backup formats | `VaultContainerTest`, `VaultContainerContractTest`, `FullBackupRestoreContractTest`; Swift `VaultContainerTests`, `FullBackupRestoreContractTests`; `desktop/src/test/resources/golden/`; Android `BackupRestoreDeviceTest` |
| Copied-file unlock and sessions | Core session tests, desktop unlock/lifetime tests; Android `AuthenticationFlowDeviceTest`; Swift password-vault and app-host tests/guards |
| Writes and migration | Core/desktop storage/transaction/failure tests, Android database/session tests, Swift vault tests; add failure injection before removing adapters |
| Native hardware and permissions | Existing hardware adapters/contracts, then actual NFC/USB/PRF/biometric/provider device checks; stubs are not sufficient |

Local repeatable gates:

```sh
python3 scripts/check_shared_boundaries.py
python3 -m unittest discover -s scripts/tests
bash scripts/kotlin-sandbox/compile-core.sh
bash scripts/kotlin-sandbox/compile-desktop.sh
bash scripts/kotlin-sandbox/compile-ui.sh
bash scripts/kotlin-sandbox/run.sh
bash scripts/kotlin-sandbox/compile-android-tests.sh
```

Native gates (on equipped runners): Gradle core/desktop/shared UI tests and distributions on each
desktop OS; both Android variants built and instrumentation executed; Kotlin iOS framework
build and Xcode app build. The standalone SwiftPM test suite is no longer an iOS CI gate. Run against the requested OS versions when suitable
SDKs/devices are available. The manual Android `compose-validation` job now selects 51 tests across
Compose/artwork, authentication, automatic backup, restore, entry documents and interchange/Google
migration. Its explicit report manifest rejects missing/skipped/failed/error results; this wiring
is not evidence of a device run. See the [release handoff](shared-migration-release.md).

## 7. Completion and handoff

The shared migration slices and cleanup work are implemented; this is not a declaration
that the full iOS application has been converted to Kotlin. The broader replacement
and its sole permitted Swift SDK bridge are tracked in [the active inventory](ios-kotlin-cutover.md).
Use [current status](shared-ui-migration-status.md) and the [release checklist](shared-migration-release.md)
rather than historical per-chunk test counts to determine remaining acceptance work.
