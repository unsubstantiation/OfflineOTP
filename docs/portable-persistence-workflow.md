# Portable persistence/migration sequencing (chunk 6)

## Shared authority and native ownership

`shared-domain/.../PersistenceWorkflow.kt` supplies the secret-free persistence state machine,
read/write failure vocabulary, commit-state vocabulary, initialization admission and journal
recovery decisions. It holds no bytes, passwords, keys, callbacks, file paths or settings objects.
Native callers serialize it with their existing storage/mutation locks and report completed steps.
There is no filesystem, crypto or settings implementation in common code.

`VaultContracts` aliases portable failure/commit-state types but keeps JVM owned-entry results
native. `VaultCommitDecision` retains its JVM helper surface and maps to the shared commit enum.
`VaultStoragePorts` remains the JVM atomic byte/settings adapter boundary; Swift keeps its native
protocols. Atomic promotion, bounded reads, durability, UUID encoding, preference commits and
provider cleanup remain native responsibilities.

## Sequences

- Atomic save/create: serialize and encrypt, authenticate the complete candidate, report verified,
  obtain promotion admission, perform native atomic replacement and acknowledge promotion.
  Ordinary saves rely on atomic replacement, not a new backup on each save. Existing rollback
  copies are not silently deleted or overwritten by ordinary saves.
- Explicit legacy migration: authenticate/open the legacy source and verify a complete portable
  candidate; require continued permission; atomically preserve the exact original bytes; report
  backup completion; recheck permission before promoting; acknowledge only after the native
  write succeeds. Opening/saving legacy data does not silently migrate it.
- Password-engine rekey: authenticate the source, preserve its bytes, derive new credentials into
  a **memory-only** settings adapter, seal/authenticate/promote a self-contained vault, and only
  then publish compatibility credential settings. This also applies to fresh creation.
  A crash before promotion leaves legacy bytes with their original settings; after promotion,
  the new file carries its own recovery parameters even if settings publication is interrupted.
- Android metadata transactions: authenticate source/candidate, preserve old bytes when replacing,
  stage ciphertext plus transaction UUID, then invoke the native settings commit. Shared policy
  permits promotion only for acknowledged COMMITTED plus a matching marker. CANCELED with no
  matching marker is rejected before promotion. Every other combination requires recovery and
  retains the journal; the existing process-poison guard blocks further mutation until restart.
- Android startup recovery: a valid staged transaction with a matching committed marker is
  promoted; an uncommitted abandoned journal is discarded; malformed/live-mismatched/blocked
  recovery cannot discard or promote. A live expected promotion requires its own journal and
  matching marker, not merely a successful no-op recovery.

Cancellation cannot undo an issued atomic replacement. A write error can occur **after** promotion
(e.g. read-back or acknowledgment failure); password engines no longer automatically restore a
backup over a potentially valid new file. They preserve the rollback copy and report failure.
Desktop already locks before rekey and tells users to try the current then new password after an
ambiguous result. Apple now also revokes old-key session authority on rekey I/O failure and shows
explicit recovery guidance. No path claims an ambiguous failure proves the password stayed unchanged.

An exact legacy rollback copy still requires its matching original legacy settings; it is not a
new portable full-backup format. Successful rekey produces the independent recoverable container.
No vault wire format or automatic-backup scheduling/credential policy is redesigned here.

## Integrations and retained adapters

Android persistence tasks, Settings transactions and EntryPersistence reach the workflow through
DatabaseHelper; their generation checks, cancellation callbacks and mutation locks remain intact.
Desktop VaultController/write gates reach it through PasswordVault. Apple VaultStore explicitly
injects SharedPersistenceAdapter into PasswordVault for save/create/rekey/migration decisions.
Standalone SwiftPM retains NativePersistenceAdapter as an independent oracle, with no runtime
fallback from a rejected shared decision. Keep this oracle until native parity gates pass.

## Regression evidence and outstanding native gates

Linux validation:

- Core: 401 passed, including 7 portable sequencing/recovery tests and 4 new password-engine
  fault-injection tests. Journal recovery covers the complete boolean decision matrix.
- App JVM: 422 passed; desktop: 475 passed, 2 skipped; shared UI: 3 passed.
- Full Kotlin sandbox passed, including existing Android journal/poison/reentrant recovery checks.
- Android instrumentation sources compile against stubs (28 source files).
- Python: 747 passed; architecture boundaries and `git diff --check` passed.

Fault injection covers failed creation without credential publication, failed legacy rekey with
unchanged settings, promotion followed by acknowledgment failure, settings failure after promotion,
backup failure and migration cancellation. Added Apple interruption/oracle/bridge tests and an
Android device regression for canceled metadata transaction cleanup.

**Not executed here:** Apple framework/Swift compilation and tests; real Android SDK/device tests;
OS filesystem/power-loss tests and interrupted native settings commits. Source guards/stubs do not
prove native ABI correctness or durability. Verify old-password/new-password recovery, original
rollback bytes, background cancellation and failed storage writes on the target platforms. No
physical zeroization guarantees are made for JVM strings or Swift copy-on-write storage.

Chunk 7 (import/export) has not started.
