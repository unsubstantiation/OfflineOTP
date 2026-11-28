# Shared restore workflow — chunk 4

> Implementation handoff; test counts and unexecuted gates below describe that slice.
> See [current verification status](shared-ui-migration-status.md) for subsequent user verification and iOS failure.
> Earlier Swift-oracle retention gates are superseded by the [active Kotlin cutover](ios-kotlin-cutover.md);
> the standalone SwiftPM suite is no longer an iOS CI gate.

Status: **implemented; native release validation outstanding** (2026-11-20).
Session migration is implemented as a shared slice. See [migration plan](shared-code-migration.md),
[entry documents](portable-entry-documents.md) and [wire policy](portable-vault-protocol.md).

## Common application rules

`shared-domain/.../RestoreWorkflow.kt` adds the actual shared application decisions used by the
Android, desktop and Apple restore paths. It contains no password, key, document, platform Entry,
native handle, provider callback or filesystem access. Hosts retain/clean their detached payloads;
only validated counts, identity-equality outcomes and opaque session/setup authority cross into it.
Calls are serialized by the native host's UI confinement or existing mutation lock, not by a new
global session singleton.

Transitions:

```text
start -> READING -> decoded/validated -> PREVIEW -> explicit confirm -> CONFIRMED
      -> plan against current entries -> beginInstall -> INSTALLING -> SUCCEEDED / FAILED
```

- `start` issues a monotonically increasing, per-workflow ticket. A new request invalidates earlier
  completions/confirmations. An old ticket cannot cancel the newer request.
- Header/KDF/crypto and full entry-document/material validation must succeed before `decoded`.
  Invalid, oversized, duplicate-account documents never become a preview. Existing-vault restore
  rejects an empty import; first-run restore permits an empty, valid vault copy.
- `confirm` is a separate, single-use transition. A file read or successful decryption does not
  grant installation authority. A replace is explicit; merge is the existing-vault default.
- `beginInstall` rechecks authority/permission and the **final combined** entry count immediately
  before native storage. It can be consumed only once. Count limit remains 2,000.
- Generation changes, destination unavailability, dismissal/lock or cancellation revoke pending
  transitions. Storage failure cannot reuse a consumed confirmation: reopen/revalidate to retry.
- Once native storage has actually begun, cancellation cannot undo that operation. A successful
  write is not retroactively undone by dropping its UI result. Existing platform transaction,
  mutation-lock, rollback and stale-publication gates remain authoritative.

`RestoreMergePolicy` returns imported indices, retain-current and final-count decisions. Identity
comparison is still done by the native Entry adapter, preserving the existing account identity
semantics without exporting seeds to presentation state. Imports are ordered first; entries already
in the current vault retain their **current instances/counters/metadata/extensions**. Replace takes
the import verbatim and excludes the current list. Duplicate inputs and over-limit combinations
are refused before any destructive ownership changes. The decision's index list is defensively
copied. The low-level merge planner permits an empty list; the workflow applies first-run versus
existing-vault empty-import rules before it.

## Production integration

### Android

- Source selection opens a read-only picker. The old pre-picker merge/replace question is removed;
  the final mode choice belongs to the validated preview, not an untrusted/unread file.
- `BackupActivity` stages a validated decoded document, displays its entry count through the shared
  Compose confirmation form, and consumes it only on a current-session confirmation. This also
  applies to successful OpenPGP backup output after existing signature policy checks.
- Replaced, stale or dismissed callbacks cannot consume another request's payload. Cancel/lock/
  destruction clears the pending document reference; temporary native entries used to validate
  the preview are destroyed immediately.
- `VaultRestoreOperation` applies common validation/confirmation/install decisions, reloads the
  **current** vault and plans the combination under `VaultSession.MutationLock`, and rechecks the
  exact native key and session generation before the native persistence call.
- `VaultRestoreMerge` is now the native identity/ownership adapter over the portable merge policy;
  the independent JVM merge algorithm is removed. Dropped detached duplicates are still destroyed.
- First-run `FirstRunBackupRestoreTask` uses shared validation, with cancellation-safe payload
  ownership. `FinishIntroTask` applies the same consent/install rules using the captured setup
  generation and the real `canInitialize` storage guard, then uses the existing single initial
  vault/settings transaction. The initial Restore action is explicit consent to restoring the
  selected source into a **new** vault with the supplied setup credentials; it is not an implicit
  destructive replace of an existing vault.
- The encrypted-read task releases its password reference after taking it, clears it on queued
  cancellation, and now wipes the loaded bytes even when cancellation occurs immediately after
  the native read. Physical JVM String zeroization is not promised.

### Desktop

- `DesktopEncryptedRestore` uses the common read/preview/confirm/single-use install transitions.
  Existing worker/result ownership remains in its native Work adapter. Request identity, active
  lifetime, lock state and the native key identity prevent stale delivery/rekey reuse.
- `BackupCoordinator` applies the shared transaction gate and final-size check against the current
  vault, then saves before publishing. Direct/programmatic restore methods represent the caller's
  explicit restore intent; the production dialog still requires its separate preview confirmation.
- First-run Compose host carries its explicit setup Restore intent through shared validation,
  confirmation and install gates, with active-host and actual empty-destination checks. It retains
  existing controller atomic-create semantics and destroys detached entries on every outcome.

### Apple

- Production `VaultStore` uses the exported Kotlin state machine directly; no native workflow
  fallback is added. Preview owns decoded entries plus the exact source bytes and selected format/
  variant. It does not change the vault.
- Confirmation must match that preview and its current session. It consumes the staged entries
  once, **without decrypting again**, plans against current live entries via the common merge
  policy, saves, then publishes. Passwords are not kept in staged state; the Compose draft password
  is cleared after a successful preview. The old `restore(...password:...)` argument is retained
  for app source compatibility only, not reused during confirmation.
- Closing/leaving the restore surface, lock, a new session, a new preview or failed/stale confirmation
  releases the staged payload. Matching source bytes/format prevents applying a preview count to a
  changed selection. Foundation/COW storage cannot guarantee physical zeroization.
- First-run restore applies shared validation/consent/install gating before native vault creation.
  As on desktop, the existing setup Restore button is explicit source/new-destination consent.
- Other document-install callers, including separately confirmed OpenPGP/import transactions, use
  the shared final transaction/merge rules. Their codecs, document pickers, signature requirements
  and selection UX remain native/adapted, not redesigned in this chunk.
- Standalone SwiftPM `VaultRestoreMerge` remains an independent compatibility oracle; production
  `VaultStore` no longer calls its native planning algorithm. Native engine/oracle deletion waits
  for actual Kotlin/Native and Xcode validation, not just source guards.

## Preserved security and storage contracts

The source-family/KDF selection is still chunk 3's shared wire policy: no wrong-password retry into
another format/KDF, no automatic pre-0.6.3 fallback. Entry parsing remains bounded/all-or-nothing and
native kind-material validators remain mandatory. Unknown extension fields stay attached through
staging, merge, save and reload. Full backups remain exact encrypted-file copies. Nothing changes
the vault container, crypto backend, new-vault recovery-password requirement, device conveniences,
automatic-backup authority or OpenPGP backend.

Storage durability, destination/version locking, migration/rekey recovery and session lifecycle
are still the existing native adapters' responsibilities (chunks 5–6 will consolidate those).
This reducer is not a replacement for atomic create/replace or the mutation lock. Do not claim it
can roll back a committed native write on cancellation.

## Validation and remaining release gates

Executed in this Linux workspace:

- Core: **379 passed**. New common decision tests cover decode without writes, confirmation before
  decode, one-shot confirmation/install, cancellation at each boundary, old/new ticket isolation,
  session/permission changes, malformed/empty/duplicate/over-limit inputs, valid empty first-run,
  current-vault growth, storage failure and portable merge order/metadata retention decisions.
- Desktop: **475 passed, 2 skipped** (477 discovered), including existing real encrypted fixture,
  save/reopen, current-edit preservation, replace, malformed/duplicate, cancellation and storage
  failure coverage; new no-preview/no-replay and key-replacement tests pass.
- App/core JVM: **400 passed** (overlaps core; do not sum as unique tests).
- Shared UI/headless: **168 + 3 passed**. Android sandbox integration passes, including actual
  native-host preview callback replacement, stale-session rejection and payload cleanup, plus
  disabled/busy/pending-picker guards.
- Python tooling/source guards: **736 passed**; architecture checks and diff whitespace check pass.
- All **28 Android instrumentation source files compile against stubs**. A new exported-policy/
  merge regression is authored; real Android device execution has not run here.

Authored native Apple tests exercise exported common transitions, first-run consent, preview
without mutation, changed-source rejection, lock/unlock invalidation, single-use consumption and
save/reopen. Existing Apple entry-document/store tests are updated to preview before confirmation.
**Swift/Xcode/Kotlin-Native compilation and XCTest execution are not available here.** In particular,
exported enum/list/boxed-Boolean bridging remains a real native build gate, not established by
JVM/stub/source checks. Real Gradle builds, both Android variants/device instrumentation, requested
OS runtime checks and native storage/provider failure checks remain required before release.

Chunk 4 is implemented at the available-code/test level; the session slice is implemented; see [its handoff](portable-session-authority.md). Keep the native
oracles and compatibility adapters until the release gates pass.
