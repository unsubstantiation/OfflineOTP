# Comprehensive bug and unfinished-work audit follow-up

**Date:** 2026-10-17

**Scope:** current `OfflineOTP` branch contents; this is a follow-up to the historical audit in [`bughunt-findings.md`](bughunt-findings.md).

The review combined repository-wide searches for unfinished markers, source/resource policy tests, and targeted review of the OTP update path, sensitive UI state, backup completion, encryption-change progress, locale/configuration handling, and the relevant lifecycle and task contracts. Confirmed issues and unfinished work below were fixed in this follow-up. The audit does not claim Android runtime acceptance where the available environment could not provide it.

## Fixes

### Time-based OTPs now refresh after a backward clock change

`Entry.updateOTP()` previously refreshed cached time-based tokens only when the current step was greater than the cached step. If the device clock moved backwards, a token from a later step could remain visible. It now samples epoch seconds once, recalculates when the time step changes in either direction, and reuses the cached current token as the previous token only on an adjacent forward step. Changing the secret, type, period, digits or mOTP PIN also invalidates the cached token immediately; replaced secret bytes are copied before the old array is wiped. TOTP and Steam calculations use the same explicit time sample.

A deterministic Kotlin-sandbox regression check moves both token types from a later step back to an earlier step, verifies current and previous values against independent calculator calls, and verifies that an unchanged step does not recalculate.

### Secret-bearing views no longer save widget state

Password/PIN entry widgets in authentication, credential-change, intro, encrypted-preference and backup-password layouts explicitly disable saved view state in XML. The bound preference/dialog widgets also disable it at runtime. The manual-entry secret input and displayed secret have the same protection. Authentication clears its widget as soon as the task takes ownership and on teardown; the intro wizard clears after transferring to setup and on teardown. Password-entry and manual-entry dialogs clear their secret controls when dismissed, including when the password callback throws.

`PasswordEncryptedPreference` no longer retains a decrypted backup password in a plaintext instance field. It decrypts only to populate the active dialog and keeps only the encrypted value in its field. Device-test assertions and Python XML/source guards cover the protected controls and cleanup; device execution remains pending.

### Backup completion now includes the chosen filename

Successful backup results already carried a sanitized display filename in `BackupTaskResult.payload`; `BackupActivity` previously ignored it. The success toast now displays the filename as a second line and still finishes with the existing result. Failures keep their existing localized message.

### Encryption changes now show modal progress

The indefinite Snackbar placeholder was replaced by a non-cancelable indeterminate progress dialog. Navigation remains blocked while the encryption task is running. The dialog is dismissed when the task stops and on Activity destruction, then rebound from the retained task state after configuration recreation. The task and cancellation/lifecycle contract are unchanged.

### Locale handling no longer mutates shared `Resources`

`ThemedActivity` and the separately implemented `SecureCaptureActivity` now create locale-specific base contexts through `LocaleContextHelper` instead of calling deprecated `Resources.updateConfiguration()`. Both paths are covered explicitly; the scanner activity does not inherit from `ThemedActivity`. If the chosen locale changes while either activity is backgrounded, it requests recreation so resources are resolved with the new locale.

A device interop test checks that the localized context's configuration selects the requested locale without changing the base context's configuration. It has not been run here.

### MainActivity now follows normal configuration recreation

The manifest no longer suppresses orientation/screen/keyboard configuration recreation for `MainActivity`, and the corresponding manual callback was removed. The saved bundle still stores only the search filter. An existing process-owned `VaultSession` may be reused across Activity recreation and is rebound to the new adapter; a saved bundle is not treated as a key or authentication proof. After process death the in-memory session is absent, so the normal unlock/authentication path still applies.

### Compatibility wrapper TODO clarified without breaking the Java API

The no-offset `TokenCalculator.TOTP_RFC6238` overload is retained as a documented Java-compatible entry point; existing production/tests call that overload, and the compiled-surface check pins it. The stale TODO suggesting removal was misleading because removal would break that surface. The explicit-time call path is now used by `Entry` so the clock sample is consistent.

### CI/build-input gate and an incomplete test harness were repaired

The repository-wide build-input audit found that all four APK workflows used mutable action tags even though the build policy required immutable commit pins. The checker failed locally for those tags, while five Python tests errored because their fixture assumed a missing `android-ci.yml`. There was no workflow running the documented host, lint, packaged-manifest, emulator, or independent reproducibility gates. The workflows now use verified full action SHAs and the pinned Ubuntu/JDK inputs; the checker scans every `.yml`/`.yaml` workflow, handles missing files cleanly, and tests mutations across the actual workflow set.

Planned (**not yet present in this checkout**): `android-ci.yml` to run the Python/Gradle source checks, both-flavor host tests and focused lint, debug/release/test-APK builds, packaged-manifest inspection, the API 28/36 instrumentation matrix for both flavors, and the independent APK/dependency comparison. Planned (**not yet present**): a read-only, manual `dependency-baseline.yml` that uploads checksum metadata and the dependency inventory for human provenance review rather than committing generated trust data. The build documentation now accurately says dependency locking is not enabled with the existing strict resolution guards; the workflow generates verification metadata only. The emulator helper now rejects missing, extra, or unsupported flavor arguments before starting.

The first sandbox rerun exposed a stand-in mismatch: the app correctly calls Android's Java `LocaleList.size()` method, but the fake API surface declared a Kotlin `size` property. The stand-in and a source guard now match the platform call shape, so the previously documented sandbox compile can actually reach the activity and locale checks.

## Verification

- `python3 -m unittest discover -s scripts/tests -v` — **not reproducible in this checkout**: `scripts/tests` and `app/src/test` are absent from the repository, so the earlier "180 passed, 2 skipped" result cannot be re-run here.
- `python3 scripts/check_build_inputs.py` — **passed** across every checked-in workflow; mutable action tags are rejected.
- `scripts/kotlin-sandbox/run.sh` using the hash-verified toolchain from `scripts/kotlin-sandbox/bootstrap-toolchain.sh` — **all sandbox checks passed**, including backward-clock, secret/PIN invalidation, activity compilation and locale checks. This compiles a reviewed source subset against stand-ins; it is not an Android build. The About-page fixture prints a known `PackageManager.NameNotFoundException` to stderr while its sandbox check still passes.
- The four APK workflows in `.github/workflows/` were checked by `check_build_inputs.py`. The `android-ci.yml` and `dependency-baseline.yml` workflows described above are **not present** in this checkout and have not been added.
- Android Gradle build, JVM Gradle tests, lint, packaged-manifest inspection, APK reproducibility and instrumentation/device acceptance were not run because no Android SDK or Gradle distribution is available here. The new workflows define those gates; their presence is not evidence that they have passed.

## Remaining acceptance boundary

No production TODO/FIXME marker remains in the app Kotlin, layout or manifest sources after this follow-up. The repository's architecture/decomposition plan still records proposed refactor boundaries; these are not user-visible incomplete features and were not mixed into this bug-fix pass. Runtime behavior that depends on Android resources, lifecycle, Activity recreation, SAF/OpenPGP providers, keystore behavior or a device still needs its documented Android gates.

### OpenPGP backup failures are reported

`BackupActivity.handleOpenPGPResult` returned silently when the OpenPGP provider asked for user interaction without a pending intent, or reported an error without an error object, and for unrecognised result codes. Those paths now show the export or import failure message, matching the operation that was requested.
