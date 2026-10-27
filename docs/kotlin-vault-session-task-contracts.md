# Kotlin vault, session and task contracts

## Scope and acceptance status

This migration introduced Kotlin contracts and Android-free session/task controllers. Later batches
converted the app's UI, storage and Android entry points to Kotlin; see the
[conversion record](kotlin-extracted-helpers.md). Current host and instrumentation sources live under
`app/src/test/kotlin` and `app/src/androidTest/kotlin`. The independent IPC client and
`Kotlin*InteropTest` suites mentioned in historical records are not in this checkout. The migration
did not introduce Compose, coroutines, a new serializer, or changes to ciphertext, JSON, backup
framing, transaction UUIDs or cryptographic algorithms.

**Implementation and test sources are present.** The sandbox checks described in the conversion record are partial and non-authoritative; passing Gradle and Android gates remains necessary for release confidence. Local Python/source grammar checks are not Kotlin/Java compilation, JVM JUnit, Android instrumentation, or a security/reproducibility certification. Both flavors must pass the existing build/lint/packaged-manifest/independent-release gates and instrumentation matrix on API 28 and 36 before release. Network and IPC protections remain separate gates, not properties granted by sealed types.

## Session contract

`SessionController` owns the process key and optional derived-key handoff. `VaultSession` is its static Java facade. `snapshot()` returns `Locked`, `Unlocking`, `Unlocked` or `Failed`, a generation and closed diagnostic enums; snapshots contain no keys, passwords, exceptions, Android references or saved-state payloads.

- `beginUnlock()` revokes existing authority and yields a new attempt generation.
- `activate(key, expectedGeneration)` rejects stale generations and failed sessions. Replacing the key identity advances the generation; re-activating the identical object does not.
- `lock(reason)` and `fail(error)` clear the key and pending handoff and advance the generation.
- Lock listeners retain their UI semantics; separate change listeners also observe key replacement and cancel old-generation tasks. Listener runtime exceptions do not skip remaining cleanup. Notifications happen outside the controller monitor.
- `OwnedSecret` copies bounded input, transfers bytes once, wipes untransferred bytes on `close()`, and redacts diagnostics. After `takeBytes()`, wiping belongs to the receiver. It is deliberately not a data class.
- Generation-bound handoffs reject a stale consumer; main/settings authentication consumers pass their captured generation. The legacy no-argument `takeDerivedKey()` samples the current generation instead and must not be used for asynchronous callbacks.

The mutation lock remains the existing database lock. Controller lock order is mutation lock then monitor; task delivery takes the mutation lock before its callback lock. An `Applied` result describes the transition at that time, not a lease or proof of future authority. Each later mutation still requires current-generation/key checks. These APIs are trusted in-process boundaries, not new IPC authentication mechanisms.

## Vault contract

`DatabaseHelper.readVault()` returns `VaultReadResult`:

- `Loaded` represents authenticated data, including a valid empty vault. It owns entries until `takeEntries()` transfers them exactly once. `close()` destroys only untransferred entries; a receiver must destroy transferred entries when finished.
- `Unavailable` has a finite error: missing file/key, authentication failure, corruption, I/O failure or recovery required. Production callers no longer use legacy list/exception reads.

`saveVault()` and `changeVaultEncryption()` return `VaultWriteResult`:

- `Saved` means an acknowledged authoritative write.
- `Rejected(error)` reports an unsuccessful operation without claiming successful migration.
- `RecoveryRequired(UNKNOWN)` means metadata commitment is uncertain or inconsistent. The process is quarantined and the session fails closed; a preferences `commit()` returning false is **not** proof that metadata rolled back.
- `RecoveryRequired(COMMITTED)` means metadata was acknowledged and matched the staged UUID, but ciphertext promotion failed. Recovery may finish promotion; it must not silently reinstall a session key.

Metadata callbacks return `VaultCommitDecision.COMMITTED`, `CANCELED` or `UNKNOWN`. `CANCELED` is only valid **before any metadata mutation**. Even a claimed cancellation is treated as uncertain if the new UUID is present. Exceptions, null decisions, mismatching committed UUIDs and unreadable markers are not acknowledged writes. Unknown commits block further operations in this process until restart/recovery or an explicit destructive reset. Never delete authoritative data merely to make recovery succeed.

Startup recovery is deferred while a live metadata callback is on the stack. The mutation lock is
reentrant, so constructing `Settings` or receiving a synchronous preference listener inside the callback
must not discard an uncommitted journal or promote it before the callback's decision is known.
`Settings` skips deprecated-setting migration during that interval. The guard is released in `finally`
on success, cancellation and exception; it is separate from the poisoned-transaction flag. A live
acknowledged commit requires its staged file and matching UUID, even though startup recovery normally
accepts “no journal” as clean. Missing/replaced journals cannot yield `Saved`.

Legacy `LoadResult`, boolean write APIs and metadata callback adapters remain for compatibility/tests. A legacy boolean write is true only for `Saved`; its metadata false result maps to `UNKNOWN`. `LoadResult.requireEntries()` is now a one-use ownership transfer rather than a repeatable shallow copy. Task-specific Java-facing domain DTOs such as encryption-change and setup results still project business status; only `Saved` can yield a successful key-installation path.

## Task contract and UI adapter

`TaskOutcome<T>` has `Value`, `Failure(TaskFailure)` and `Cancelled(TaskCancellation)`. Value means that the producer returned, **not** that the business operation succeeded (e.g. a denied authentication is a Value containing Rejected). Infrastructure failure does not fabricate a domain fallback value for migrated callers and contains no Throwable or provider details.

`TaskController` models one-shot Ready -> Running -> Completed -> Delivered, or cancellation. It contains metadata only. It supports one delivery claim, including a retained cancellation notification, and rejects stale pending completions after generation change.

`UiBasedBackgroundTask` keeps Android scheduling to executor threads plus main-loop posting (the runner itself is now Kotlin — see the [conversion record](kotlin-extracted-helpers.md); its contract is unchanged):

- A pending outcome survives callback detachment. Posted delivery captures the task, not a result or Activity.
- Revocation/key replacement cancels old-generation work and disposes pending Value payloads even while callbacks are detached. Delivery checks generation under the mutation lock.
- Results returned while cancellation unwinds are disposed rather than delivered. Cancellation does not roll back already committed metadata or provider output.
- `discardResult()` disposes owned output. `onCancellation()` performs idempotent owned-input cleanup even if the worker never started. Cleanup overrides must not acquire the mutation lock and must not block on external operations.
- Delivery transfers Value ownership to the consumer. `disposeOutcome()` is available for stale/replaced results; consumers must close or transfer owned values.
- Production task consumers use `setOutcomeCallback()`. The legacy `setCallback()` adapter remains for compatibility: it maps Failure to the fallback and suppresses Cancelled. `EntryPersistence` handles automatic-backup scheduling and callback binding.

`BackgroundTaskActivity` retains its task fragment across configuration changes, detaches on pause, rebinds on resume and guards callback delivery by source-task identity. A replaced task cannot update/remove the newer task's UI. Permanent fragment destruction detaches and cancels. Authentication handles infrastructure failure as failure; backup reports an error without successful export/import. Credential dialogs cancel/detach on close. Intro slide destruction cancels unfinished work; setup keys are copied into the Activity and the task-owned array is disposed after the callback. Intro is not converted to a new retained/coroutine lifecycle owner here.

`MainActivity` no longer suppresses normal orientation/screen configuration recreation in the manifest. Its saved bundle contains only the search filter; the new Activity reuses a live process-owned `VaultSession` if one still exists and rebinds its key to the new adapter. A Bundle is never treated as an unlock proof, and after process death the static session is absent and the normal authentication/unlock path runs. This lifecycle behavior still needs real rotation/process-death device acceptance.

`AuthenticationOutcome.Verified` owns a derived key and the task's originating generation; `Rejected` carries no key. Authentication closes the owned outcome, publishes the existing process-only handoff, and wipes temporary bytes. No raw key Intent extras are introduced.

Broadcast encrypted backup deliberately remains independent of an unlocked UI session, subject to its existing signature authorization, opt-in, encryption mode, configured destination and final write-permission checks. Its runner cancellation override ignores UI generation changes, but explicit cancellation still works. This is not a general authorization bypass for other tasks.

## Test coverage and remaining limits

The migration originally added the tests below; not all historical suites remain in this checkout. The current host, sandbox and instrumentation coverage is described in the [README](../README.md) and [vault security record](vault-security-hardening.md).

- 14 Kotlin/JVM tests for session transitions, stale/failed activation, one-use owned secrets/handoffs, listener isolation, task state transitions, cancellation, redacted outcomes and authentication key ownership.
- Five additional runner instrumentation tests for typed exception/null failure, detached completion/rebinding, key replacement disposing queued secrets, and pre-submission cleanup. The sandbox continues to check typed cancellation and result disposal.
- Five additional vault instrumentation tests for missing versus valid empty reads, entry ownership, pre-commit cancellation, unknown metadata quarantine, and committed promotion failure/recovery without implicit unlock.
- Four Python source-policy checks for boundary placement, typed production consumers, redaction and explicit recovery. These check source structure only.

Build the APKs with the Gradle commands in the README; do not substitute grammar parsing or Python checks for them. Android rotation/pause/replacement and provider behavior still require device acceptance review. Best-effort wiping cannot erase immutable Strings, platform/JCA copies, provider-held plaintext or a compromised process. Typed states do not make cancellation transactional, authenticate imported accounts, or eliminate partial manual-provider output.

## Subsequent UI work

The large UI classes were converted after the first five decomposition slices. That conversion did
not complete the remaining extractions; see the [UI decomposition plan](ui-decomposition-plan.md).
Gradle, lint and device acceptance remain necessary.
