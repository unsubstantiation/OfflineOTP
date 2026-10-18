# Bug hunt and unfinished-feature follow-up, 2026-10-18

Second comprehensive pass over this checkout, in the user's two phases: (1) a full bug hunt
across the app sources, resources, manifest and tooling, and (2) completion of the unfinished
features the repository's own documents listed as planned-but-absent. The verification
environment still has no Android SDK, Gradle distribution or device; the gates that could run
here all pass, and the gates that could not are now defined as CI workflows that run them.

## Phase 1 — bug hunt results

Every remaining `.kt` source, every `res/values*` translation and the manifest were read or
re-checked (the full no-bug list is in the session record; the previously converted files from
the 2026-10-17 pass were spot-checked rather than re-audited line by line). Two defects were
found and fixed:

### Bug 1: the sandbox R stand-in was stale after the thumbnail batch

`scripts/kotlin-sandbox/stubs/org/shadowice/flocke/andotp/R.kt` no longer listed every
`R.drawable.thumb_*` constant referenced by `EntryThumbnail.kt` (~83 of them), so
`scripts/kotlin-sandbox/run.sh` failed before compiling anything — the one gate this
environment has was dead. `generate-r-stub.py` was rewritten to walk the actual drawable
directory and the Kotlin references; `generate-r-stub.py --check` now pins freshness in
`scripts/tests/test_kotlin_sandbox_harness.py`, so any resource-adding change that forgets the
regeneration fails the Python guards immediately.

### Bug 2: the Arabic translation dropped a format argument

`app/src/main/res/values-ar/strings_main.xml` carried upstream andOTP's `dialog_msg_confirm_delete`
without the `%1$s` placeholder the source string and every other translation use. On Arabic
devices the confirm-delete dialog showed the raw template; on API 24+ a missing positional
format arg in a translated string is also a lint error. The string now matches the source
argument list («%1$s»), and `scripts/tests/test_resource_references.py` gained a
translation-format-argument guard (mutation-verified: removing the placeholder again fails it).

No production `TODO`/`FIXME` marker remains; the 424-entry thumbnail/drawable mapping is
bijective; no dangling `R` reference exists. The `IntroScreenActivity` portrait lock (upstream
issue #591) is deliberately left in place: removing a user-facing orientation restriction
without device acceptance is a product decision, not a bug fix.

## Phase 2 — unfinished features completed

The 2026-10-17 follow-up documented two workflows as "planned, not yet present" and a test
harness that had gone missing from the checkout. All of it now exists:

### 1. The Python guard suite (`scripts/tests`, 12 files, 72 tests)

`python3 -m unittest discover -s scripts/tests` runs the whole battery with no external Python
dependencies (PyYAML-gated tests skip where it is unavailable):

| File | What it pins |
| --- | --- |
| `test_popup_menu_item_lookup.py` | Menu item lookups resolve against the real menu XML. |
| `test_kotlin_sandbox_harness.py` | run.sh's source list is valid; no stub shadows a production class; the checked-in R stub is fresh (`--check`); runner exit-code semantics. |
| `test_resource_references.py` | Every `R.*` reference in Kotlin/XML resolves; styleable constants; translated strings keep the source format arguments (mutation-verified). |
| `test_kotlin_document_io_conversion.py` / `test_kotlin_backup_components_conversion.py` | The converted document/backup surfaces keep their Java-compatible shapes, guard orders and refusal paths (asserted by string-position ordering, not fragile whole-text bans). |
| `test_manifest_source_policy.py` | The manifest policy is applicable to a source manifest with the documented expectations (uses-sdk, merged androidx components). |
| `test_no_plaintext_backup.py` | Backup encryption kind is enum-constrained; no plaintext branch can appear. |
| `test_secret_state_guards.py` | Every secret-bearing layout input has `saveEnabled="false"`; dialog classes clear/dismiss on the documented paths. |
| `test_documentation_links.py` | Every relative link in docs/ resolves — this found the missing `ApplicationTest.kt` (fixed below). |
| `test_build_inputs.py` | Mutation tests: unpinned actions, tampered wrapper, changed distribution URL and removed checksum are all rejected by `check_build_inputs.py`. |
| `test_jvm_test_suite.py` | Package/import structure, Gradle wiring, harness lockstep with run.sh, shim coverage, no android.jar usage — plus an end-to-end compile-and-run proving the reflective runner fails the run on a failing test. |
| `test_android_test_suite.py` | `ApplicationTest.kt` exists at the path `docs/language-security-assessment.md` links to, with the byte-exact entry vector; the vault round-trip is assumption-guarded and wipes; the GCM vectors live on the device side; the compile check cannot drift. |
| `test_ci_workflows.py` | android-ci.yml keeps its five jobs and the full flavor × API grid; dependency-baseline.yml stays manual; the emulator script validates arguments and tears down unconditionally. |

### 2. The JVM unit-test suite (`app/src/test/kotlin`, 14 files, 110 tests)

Plain JUnit 4 — `./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest` runs it
against real `junit:junit:4.13.2` (plus `org.json:json` for the host classpath, since the
platform's org.json is "not mocked" in unit tests). Coverage: RFC 6238/4226 vectors for all
three hash algorithms (independently cross-checked in Python before being trusted), the Steam
base-26 mapping re-derived from a hand-written HMAC truncation, mOTP against an independent
MD5, InputPolicy bounds and the JSON depth scanner, BoundedInputReader/BoundedOutput edge
cases, the encrypted-backup header framing and iteration ceiling, the ScreenTimerController
state machine (idempotent starts, obsolete-runnable suppression, backward-clock-safe
inactivity, pause/close), Tools token formatting, OtpUriParser acceptance and the full
closed error matrix, EntryJson field set/order/defaults and byte-identical round trips, Entry
clock-step behaviour including the backwards-clock fix from the previous pass, EntryList
identity/search/tag-filter/sort semantics, EntryActionTarget borrowed-identity resolution, and
the host-runnable crypto helpers (PBKDF2 split and bounds, RSA wrap/unwrap, framing checks).

Because this environment cannot fetch the junit jars, `scripts/run-jvm-tests.sh` drives the
same sources with the sandbox toolchain and a minimal hand-written JUnit 4 shim
(`scripts/jvm-tests/`: annotations, Assert, Assume, a reflective runner with JUnit's
per-method instance semantics). The shim compiles the exact source set `run.sh` compiles by
reading its `APP_SOURCES`/`STUBS` arrays out of that file, so the two gates cannot drift.

Two deliberate omissions, both documented in the test headers: `Entry.toUri()` (needs
`android.net.Uri`) and the AES-GCM round trips. The latter initialize GCM with an
`IvParameterSpec`, which Android's providers accept — the long-shipped andOTP behaviour this
fork keeps — but a desktop SunJCE rejects ("AlgorithmParameterSpec not of GCMParameterSpec").
Those vectors run in the instrumentation suite instead; only the provider-independent framing
checks run on the host.

### 3. The instrumentation suite (`app/src/androidTest/kotlin`, 4 files)

`app/build.gradle` now declares `testInstrumentationRunner` plus
`androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2` and `androidx.test:core:1.6.1`.

- **`ApplicationTest.kt`** — at the exact path `docs/language-security-assessment.md` links
  (that link was the one broken relative link in docs/). It carries the RFC vectors, the
  byte-exact `testEntry` document comparison, entry parse/serialize round trips and
  rejections, an assumption-guarded vault round trip through the real `DatabaseHelper`
  transaction path (own AES key, wrong-key rejection, `wipeDatabase` in `finally` so it never
  destroys real data), and the AES-GCM encrypt/decrypt/tamper/truncation vectors that cannot
  run on a host JVM.
- **`AppContextTest.kt`** — environment smoke: the target context is the app, private storage
  works (fails fast when the emulator wiring is broken).
- **`IntroScreenLaunchTest.kt`** — the first-run screen launches to a resumed state on a fresh
  instance.
- **`EntryDocumentDeviceTest.kt`** — the device-only document vectors: `Entry.toUri()` through
  the platform Uri builder for every kind (including the mOTP PIN being deliberately not
  exportable) and the platform org.json round trip.

`scripts/kotlin-sandbox/compile-android-tests.sh` type-checks the whole suite against run.sh's
sources and stubs plus the androidx.test stand-ins in
`scripts/kotlin-sandbox/stubs-android-test/` — no Android SDK needed, so the suite compiles
in every environment that can run the sandbox check. It is a compile check only; AGP's
`assembleDebugAndroidTest` and the emulator run are the real gates.

### 4. The emulator lifecycle script (`scripts/ci/start-emulator.sh`)

`scripts/ci/start-emulator.sh <fdroid|play> <28|36>` performs the complete lifecycle in one
command: validate arguments, install the pinned `default;x86_64` system image, create a
`pixel` AVD, boot headless (swiftshader, no snapshot), wait up to 10 minutes for
`sys.boot_completed`, print the connected device and the flavor's `connected*DebugAndroidTest`
task, run it, and always — via an EXIT trap — kill the emulator and delete the AVD. The exit
code is Gradle's.

### 5. The CI workflows

**`.github/workflows/android-ci.yml`** — five jobs, all action references pinned to verified
40-hex SHAs (the four APK workflows remain the per-variant artifact producers):

1. **source-checks** — the Python guard suite and `check_build_inputs.py`.
2. **host-tests** — bootstrap the sandbox toolchain, then `run.sh`, `run-jvm-tests.sh`,
   `compile-android-tests.sh` and the toolchain-gated Python guards.
3. **build-and-lint** — assemble all four variants plus both androidTest APKs, run the Gradle
   host unit tests (real JUnit), lint all variants (the focused `abortOnError` set), print
   each release APK's manifest with `apkanalyzer` and apply `check_manifest.py` to it, and
   upload the artifacts.
4. **instrumentation** — the full `flavor × {API 28, API 36}` matrix (4 jobs, no fail-fast)
   via `scripts/ci/start-emulator.sh`, with emulator logs attached on failure.
5. **reproducibility** — on master or manual dispatch only: `scripts/verify_reproducible_build.sh`
   (two independent source/Gradle-home builds, byte-compared APKs and dependency inventories).

**`.github/workflows/dependency-baseline.yml`** — manual-only `workflow_dispatch` job that
runs `:app:writeDependencyInventory` and uploads the report for human provenance review, so
no generated trust data is committed.

## Verification

- `python3 -m unittest discover -s scripts/tests` — **72 passed** (toolchain-gated runner
  tests included in this environment; they skip where kotlinc is absent).
- `python3 scripts/check_build_inputs.py` — **passed** across all six workflows.
- `scripts/kotlin-sandbox/run.sh` — **all sandbox checks passed** (regenerated R stub; env
  from `scripts/kotlin-sandbox/bootstrap-toolchain.sh`).
- `scripts/run-jvm-tests.sh` — **110 passed, 0 failed**.
- `scripts/kotlin-sandbox/compile-android-tests.sh` — **COMPILE OK** (4 instrumentation files).
- Gradle builds, `lint`, the Gradle-host unit tests, emulator instrumentation and the
  reproducibility comparison were **not run here** (no Android SDK/Gradle/device); the new
  workflows define those gates, and their future green runs are the evidence, not this
  document.

## Remaining acceptance boundary

Runtime behaviour that depends on Android resources, lifecycle, SAF/OpenPGP providers,
keystore behaviour or a device still needs the Android gates above. The architectural
extractions in `docs/ui-decomposition-plan.md` remain proposed refactor boundaries (not
user-visible incomplete features), and the upstream triage items marked ❌ in
`docs/andotp-open-issues-triage.md` — except the deliberately documented #591 portrait lock —
are available as follow-up work.
