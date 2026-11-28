# Portable session/unlock authority (chunk 5)

> Implementation handoff; test counts and unexecuted gates below describe that slice.
> See [current verification status](shared-ui-migration-status.md) for subsequent user verification and iOS failure.
> Earlier Swift-oracle retention gates are superseded by the [active Kotlin cutover](ios-kotlin-cutover.md);
> the standalone SwiftPM suite is no longer an iOS CI gate.

## Boundary

`shared-domain/.../SessionAuthority.kt` owns secret-free generation/phase transitions,
one-shot provider delivery tickets, result admission and elapsed inactivity deadlines.
It never stores credentials, keys, native handles, files, callbacks or platform clocks.
Callers serialize it using their existing JVM mutation locks or Apple main-thread owners.
Provider success is **not** vault authentication: native adapters authenticate the encrypted
file before activating a session. Generation and ticket exhaustion fail closed, never wrap.

Native adapters still own key material, cleanup, authenticated file opening, biometric/PRF
providers, retained tasks, mutation locks, backup leases, scheduling and UI lifecycle.
Portable password recovery remains authoritative; local convenience enrollment is not a
substitute for recovery. No vault wire format changes are part of this chunk.

## Integration

- JVM `SessionController` delegates metadata to the authority while retaining synchronized
  ownership, bounded derived-key handoffs, backup leases and listener cleanup.
  `AuthenticationResultPolicy` delegates admission to `SessionResultPolicy`.
- Android biometric callbacks capture immutable generation/ticket pairs. Cancelled, duplicate
  and superseded callbacks cannot consume a newer attempt. Hardware delivery is one-shot;
  parent `onStop` does not cancel a legitimate child hardware activity. Native hardware
  discovery still owns cancellation and authenticated file checks. Password tasks release
  retained password references on cancellation/worker handoff (not a zeroization guarantee).
- Desktop unlock operations use one-shot tickets while retaining worker/result disposal and
  key ownership. Desktop vault state uses the shared generation authority. The existing
  lifetime controller reaches shared inactivity decisions through `ScreenTimerController`.
- Apple `VaultStore` adopts all successful authenticated opens through shared activation.
  Biometric tickets also bind the vault identity and enabled state; password attempts,
  dismiss/background/lock invalidate pending delivery. Hardware host tickets bind the store
  generation and foreground visibility. Keys and native file validation remain Swift-owned.
- Inactivity decisions use the shared saturating deadline policy. Android/JVM retain elapsed
  realtime scheduling; Apple uses Swift `ContinuousClock` (including sleep), with an explicit
  Date seam for deterministic existing tests. Expired sessions cannot be revived by interaction
  or settings changes. Backward elapsed clocks fail closed.

## Regression evidence

Linux sandbox results for this chunk:

- Core: 390 passed, including 11 new portable session tests covering transitions, recovery,
  generation exhaustion, handoff cleanup, stale/duplicate/cancelled provider delivery and clocks.
- App JVM: 411 passed; desktop: 475 passed, 2 skipped; shared UI: 3 passed.
- Full Kotlin sandbox checks passed; Android instrumentation sources compile against stubs.
- Python: 742 passed; shared architecture boundary checks and `git diff --check` passed.

Added Apple deferred-biometric and shared-export tests plus Android on-device authority/handoff
coverage. **These native tests have not run here.** Stub compilation/source guards do not prove
Android runtime behavior or Kotlin/Native Swift ABI correctness. Xcode/framework compilation,
Apple tests, real Android SDK/device instrumentation, biometric prompts, hardware USB/NFC and
background/sleep behavior remain native release gates. Existing native adapters/oracles are
retained until those gates pass. Strings and Swift copy-on-write arrays carry no guaranteed
zeroization claim.

The subsequent persistence slice is implemented; see [its handoff](portable-persistence-workflow.md).
