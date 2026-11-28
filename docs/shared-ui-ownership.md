# Shared UI ownership

One portable implementation owns app-rendered screens. Native hosts supply services
and lifecycle integration, not alternate app UI trees. SwiftUI and Swing production
renderers have been removed; this does not mean the remaining Swift engine is gone.

## Current layers

| Layer | Responsibility |
| --- | --- |
| `shared-domain/commonMain` | Portable entry/document/interchange rules and secret-free workflow decisions |
| `ui-contract/commonMain` | Engine-free presentation, frame, card/action and drag contracts |
| `ui-foundation/commonMain` | Shared Compose screens, forms, dialogs, theme and sole card/list renderer |
| `ui-foundation/iosMain` | Apple Compose controllers and migrating Kotlin/Native service adapters |
| `ui-model/jvmHostMain` | JVM engine-to-presentation adapters; depends on `:core` |
| `ui/jvmHostMain` | JVM/Android host composition; reuses the portable renderer |
| Android / desktop / iOS hosts | Sessions, request ownership, protected data, native services and delivery |

`commonMain` is not a portability guarantee by itself. `:ui` and `:ui-model` are
JVM/Android adapters, not the Apple engine API. The source boundaries are enforced
by `scripts/check_shared_boundaries.py`; real target compilation is a separate gate.

## Presentation and service boundaries

- `SharedVaultRenderer.kt` renders shared card/action/code content and drag lists.
  JVM and Apple adapters publish the same engine-free render contracts.
- Forms own editable presentation drafts, not durable credentials or filesystem authority.
  Hosts revalidate actions, session generation and selected identities before execution.
- Apple hosts still use Swift coordinators and `OfflineOTPKit` for substantial engine
  work. The [active Kotlin cutover](ios-kotlin-cutover.md) replaces these dependencies;
  only a minimal YubiKit SDK bridge is approved to remain Swift.
- OS document/permission sheets, biometric prompts, native camera recognition and
  SDK UI are capability boundaries. Their presence is not a duplicate Compose screen.
- Android retains some hidden compatibility state/lifecycle adapters. A hidden
  adapter is not necessarily dead code; remove it only with migrated callers.

## Lifetime and privacy rules

Concealed cards must not publish protected text, copy actions or unnecessary artwork
requests. Frame replacement must use current actions; lock/close/background invalidates
stale deliveries. Drag actions use stable identity and authoritative order, not an
old filtered index. Native results are admitted only to their originating live request.

Masking is defense in depth, not secure memory erasure. Immutable strings, native
copies and crypto internals cannot promise complete zeroization.

## Verification and related documents

The user reports non-iOS builds/platform checks verified. iOS has a confirmed build
failure and no recorded successful follow-up run. Stub tests do not execute Compose,
measure layout or validate Apple ABI/exported API signatures.

- [Current status](shared-ui-migration-status.md)
- [Shared-code plan and target matrix](shared-code-migration.md)
- [Portable renderer contract](portable-ui-presentation.md)
- [Native validation](shared-ui-native-validation.md)
- [Device checklist](shared-ui-device-checklist.md)

Earlier per-slice journals are retained in Git history rather than repeated here.
