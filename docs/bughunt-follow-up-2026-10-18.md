# Bug hunt and unfinished-feature follow-up, 2026-10-18

> **Snapshot:** written 2026-10-18. The workflows described below have since been removed.
> A later bug-hunt pass exists: [`bughunt-follow-up-2026-10-25.md`](bughunt-follow-up-2026-10-25.md).

Second comprehensive pass over this checkout, in the user's two phases: (1) a full bug hunt
across the app sources, resources, manifest and tooling, and (2) completion of the unfinished
features the repository's own documents listed as planned-but-absent. The verification
environment still has no Android SDK, Gradle distribution or device; the gates that could run
here all pass, and the gates that could not are now defined as CI workflows that run them.

## Phase 1 — bug hunt results

Every remaining `.kt` source, every `res/values*` translation and the manifest were read or
re-checked; the previously converted files from the 2026-10-17 pass were spot-checked rather
than re-audited line by line. Two defects were found and fixed:

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

No production `TODO`/`FIXME` marker remained at this pass; the 424-entry thumbnail/drawable
mapping was bijective and no dangling `R` reference existed. At that date, the `IntroScreenActivity`
portrait lock (upstream issue #591) was deliberately left in place pending device acceptance. A
later follow-up removed the manifest lock and added `IntroScreenLaunchTest` to assert that the
wizard leaves orientation unspecified; see `docs/andotp-carryover-issue-pass-2026-10-19.md`.

## Phase 2 — unfinished features completed

The 2026-10-17 follow-up documented two workflows as "planned, not yet present" and a test
harness that had gone missing from the checkout. All of it now exists:

### 1. The Python guard suite (`scripts/tests`, 12 files and 72 tests at this pass)

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

- `python3 -m unittest discover -s scripts/tests` — **72 tests** (toolchain-gated runner tests
  included in this environment; they skip where kotlinc is absent).
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

# Second pass (later 2026-10-18): edit saves, the PIN warning and the custom-period card row

A further comprehensive audit read every remaining production file (Preferences, the
About screens, `LetterBitmap`, `DimensionConverter`, `BackupAgent`, `OtpParseResult`,
`NumberPickerPreference`, `PasswordEncryptedPreference`, the item-touch plumbing and the
flavor fragments), compared every Kotlin class against the upstream andOTP master Java,
diffed all `R.id`/`R.layout`/string references and menu/preference wiring, and checked the
trailing layout-only ids and orphaned strings against upstream history. It found two
defects and one unfinished feature surface; all three are fixed in this pass.

## Fixed: editing an existing entry could never save (TOTP/HOTP/STEAM)

`ManualEntryDialog` validated the typed secret with `Entry.validateSecret(secret, type)`
for every save, but when editing, the secret field is display-only (GONE and empty; the
stored secret is shown in `manual_secret_view` and the edit branches never read the typed
value). The strict validator rejects the empty string — `decodeBase32Secret("")` does not
match the Base32 text pattern and returns null — so the error was set on a hidden widget
and Save silently did nothing. Upstream only appeared to work because commons-codec decodes
`""` to an empty array. The fix gates typed-secret validation (and the throwaway
`Entry(type, secret, ...)` construction that would throw the same way) on `isNewEntry`;
new entries are validated exactly as strictly as before (`Checks.kt` still pins
`validateSecret`'s behaviour itself). mOTP edits were never affected.

## Fixed: PIN setup showed the password confirmation warning

`IntroScreenActivity.setupForPinInput` assigned
`R.string.intro_slide3_warn_confirm_password` where upstream used
`R.string.intro_slide3_warn_confirm_pin`. The PIN string exists in all 33 translations and
was orphaned by the conversion; the password path is unchanged.

## Completed: the custom-period indicator on the entry cards

Every card variant (`component_card_compact/default/full.xml`) ships a
`customPeriodLayout` row (alarm icon plus a bold `customPeriod` label) and every
translation ships `format_custom_period` (`"%d s"`), but no code bound them. The wiring
existed in andOTP v0.4/v0.5 (`EntryViewHolder.showCustomPeriod` for entries with
`hasNonDefaultPeriod()`, hidden otherwise) and was lost upstream in v0.6; every release
since has carried the dead row and the translated strings. `EntryViewHolder.updateValues`
binds the row again under the original contract, the sandbox fixture constructs the new
views and asserts both branches, and the localized format string is no longer an orphan.

## Unfinished-feature sweep (remaining candidates assessed, not defects)

- `docs/ui-decomposition-plan.md` "Still to extract" remains proposed refactor boundaries
  (the docs state they are not user-visible incomplete features) — unchanged.
- The triage ❌ brand-icon batch is already complete in this checkout (PR #42 added the
  missing vectors; 80 of the 81 requested brands resolve in `EntryThumbnail`, the rest is
  `500px`/`FiveHundredPx`). The remaining ❌ items are product/UX requests (#591 portrait
  lock is a documented deliberate choice), and the 🆕 gaps are explicitly "adopt only if
  wanted" — no code in this repository claims them as started work.
- Remaining orphaned strings (`card_separator`, `dialog_title_rename`, `button_settings`,
  `about_label_github`/`website`/`translators`, ...) are dead in upstream the same way or
  are documented removals from earlier passes; none marks a started-but-unfinished surface.

## Verification (all host gates re-run)

- `python3 -m unittest discover -s scripts/tests` — **82 tests** (PyYAML installed, zero skips; includes the new
  `test_ui_surface_regressions.py` pinning all three changes).
- `python3 scripts/check_build_inputs.py` — **passed**.
- `scripts/kotlin-sandbox/run.sh` — **all sandbox checks passed** (R stub regenerated after
  the source changes; the entry-card check now also asserts the custom-period row).
- `scripts/run-jvm-tests.sh` — **110 passed, 0 failed**.
- `scripts/kotlin-sandbox/compile-android-tests.sh` — **COMPILE OK**.

# Third pass (later 2026-10-18): TypedArray lifecycle, dimension parsing, thumbnail sizing and tag counters

A further full-coverage re-audit (80+ Kotlin sources, all layouts, the manifest and the sandbox stubs) focused on API lifecycle, input validation and the remaining unfinished-feature candidates listed in `docs/andotp-open-issues-triage.md`. Three defects with user-visible impact were fixed, one latent performance defect was removed, the sandbox stubs were updated to match the real Android API, and one unfinished feature was completed.

## Fixed: LetterBitmap recycled the colour TypedArray on every tile

`Utilities/LetterBitmap.kt` stored a single `TypedArray` in a field (`obtainTypedArray(R.array.letter_tile_colors)`) and recycled it inside `pickColor()` in a `finally` block. On first `getLetterTile` the array was recycled; a second tile from the same `LetterBitmap` instance threw `IllegalStateException: Cannot make calls to a recycled instance` (crash on long lists with the same issuer). The per-request `new LetterBitmap(context)` in `EntryThumbnail.getThumbnailGraphic` hid the crash but left a leaked TypedArray on early throws and an incorrect lifecycle. The fix copies the eight colours into an `IntArray` once (under `try/finally { recycle }`) and `pickColor` now indexes the array.

## Fixed: DimensionConverter accepted multi-dot numbers

`Utilities/DimensionConverter.kt` used `^\\s*(\\d+(\\.\\d+)*)\\s*([a-zA-Z]+)\\s*$` which matches `1.2.3dp` (multiple fractional parts). `Float.valueOf("1.2.3")` then threw late with a generic message. The pattern is now `\\d+(\\.\\d+)?` so exactly one fractional part is accepted and malformed dimensions fail at the matcher with a clear `NumberFormatException`.

## Fixed: EntryThumbnail could create a 0×0 bitmap for vector thumbnails

`Utilities/EntryThumbnail.kt` built vector brand tiles with `Bitmap.createBitmap(drawable.getMinimumWidth(), drawable.getMinimumHeight(), …)` and `drawable.setBounds(0,0, getMinimumWidth(), getMinimumHeight())`. For vectors `minimumWidth/Height` may be 0, which throws `IllegalArgumentException` in `createBitmap`. The code now prefers `getIntrinsicWidth/Height`, falls back to `getMinimumWidth/Height`, and finally to the requested tile size (or 1) so `createBitmap` never receives 0. The sandbox stub gained `getIntrinsicWidth/Height` to match the real API.

## Fixed: Entry card bound a fresh Settings on every bind

`View/EntryViewHolder.updateValues(entry)` constructed a new `Settings(context)` on every `onBindViewHolder` (including its `migrateDeprecatedSettings` check) for every visible card on every token refresh. The holder now takes `updateValues(entry, settings)` supplied by `EntriesCardAdapter` (which already owns a `Settings` instance); a deprecated `updateValues(entry)` overload delegates to the new path for compatibility. The binding semantics (custom period, tap-to-reveal, colours) are unchanged.

## Completed: tag counters in the drawer (#994)

Upstream issue #994 requests per-tag entry counts in the tag drawer; the existing drawer was selection-only. `Database/EntryList` gained `getTagCounts(): HashMap<String,Int>`, `View/TagsAdapter` gained `setTagCounts`/`getTagCounts` and renders `tag (count)` when a count is available, and `View/EntriesCardAdapter.updateTagsFilter` now pushes `entries.getTagCounts()` into the adapter after reconciling tags. The empty-drawer and no-tags cases keep their existing behaviour.

## Verification (host gates re-run)

- `python3 -m unittest discover -s scripts/tests` — **82 tests, 9 skipped (PyYAML-absent checks)** — unchanged.
- `scripts/kotlin-sandbox/run.sh` — **all sandbox checks passed** (stub regenerated for `length()`/`getIntrinsicWidth/Height`; `EntryViewHolder`/`EntryThumbnail` checks cover the new branches).
- Sandbox compilation previously failed on `TypedArray.length()` and `Drawable.getIntrinsicWidth`; both stubs now match the platform API.

# Fourth pass (2026-10-18): mOTP ignores a foreign period/PIN field on parse

A further comprehensive bug hunt covered the crypto core (`EncryptionHelper`, `EncryptedBackupHeader`,
`FileHelper`, `StorageAccessHelper`), the restore/backup task chain (`VaultRestoreOperation`,
`EncryptedRestoreTask`, `GenericRestoreTask`, `BackupHelper`), every dialog/adapter under `Dialogs/` and
`View/` (`EntryActionDialogs`, `TagsDialog`, `ThumbnailSelectionAdapter`, `PasswordEntryDialog`,
`HideableDialog`, `CredentialsPreference`, `NumberPickerPreference`, `EntryViewHolder`,
`EntryListPresentation`), `NotificationHelper`, `ScanQRCodeFromFile`, the manifest, every `res/values*`
resource file (XML well-formedness, unescaped `'`/`&`, duplicate resource names, translated-string format
argument parity — all clean), the `AndroidManifest.xml` permission/export surface, and a warnings-enabled
recompile of the sandbox source set (`kotlinc` without `-nowarn`) to surface anything the normal gate's
`-nowarn` flag hides. All settings-preference keys in `res/xml/preferences.xml` were cross-checked against
`Settings.kt` getters and their external call sites to rule out dangling/unwired preferences (none found).
One confirmed, user-visible defect was found and fixed.

## Fixed: a foreign `period` or `pin` field on an entry document was not kind-gated on parse

`Database/EntryJson.parsed()` read the JSON `period` field for *any* OTP kind and the `pin` field
whenever it was present, with no check that they were meaningful for that kind:

```kotlin
val period = if (json.has(JSON_PERIOD)) int(json, JSON_PERIOD) else DEFAULT_PERIOD
...
val pin = if (json.has(JSON_PIN)) text(json, JSON_PIN) else ""
```

Every other layer in the codebase treats an mOTP entry's period as fixed and non-configurable:
`Entry.getTokenStepSeconds()` always uses `TokenCalculator.MOTP_STEP_SECONDS` (10s) for an mOTP entry
regardless of the stored `period` field (this is the backward-compatible fix from the 2026-10-18 third
pass, "mOTP refreshes on its 10-second step even when the stored period is 30"); `EntryJson.serialize()`
never writes a `period` for mOTP; and `Entry.toUri()` only emits `period` for `HOTP`/`TOTP`/`STEAM`,
never `MOTP`. Similarly, `serialize()` only ever writes `pin` for mOTP, and `Entry.getPin()` is only
read in the `MOTP` branch of `updateOTP()`.

The parser was the one place that did not enforce either invariant. A backup/import document (hand-edited,
from a future version, or simply malformed) carrying `{"type":"MOTP","period":60,...}` parsed successfully
and produced an mOTP `Entry` with a non-default `period`. `Entry.hasNonDefaultPeriod()` would then report
`true`, and `View/EntryViewHolder.updateValues` would show the card's "custom period" row with
**"60 s"** — even though the entry's actual rollover is still every 10 seconds, because
`getTokenStepSeconds()` ignores the `period` field for mOTP. The same gap let a `pin` field on a
non-mOTP document survive into the parsed entry (harmless today, since `getPin()` is only read for mOTP,
but it contradicted the field's own documented invariant and the asymmetric write-side gating).

`EntryJson.parsed()` now forces `period = DEFAULT_PERIOD` for `OtpKind.MOTP` regardless of any supplied
value, and only reads `pin` when `kind == OtpKind.MOTP`, matching what `serialize()` ever writes and what
the `otpauth://` parser already enforces. Every other kind's parsing is unchanged (Steam's non-default
period, TOTP's period, HOTP's counter and the existing malformed-field rejections all keep their exact
prior behaviour — pinned by the existing `period 0`/`period -1`/numeric-string tests, which exercise the
default `TOTP` kind and are untouched by this change).

**Regression coverage:** `EntryJsonTest.motpIgnoresAStoredPeriodBecauseItNeverDrivesMotpTiming` and
`.nonMotpDocumentsIgnoreAStoredPin` (JVM suite), plus two new assertions in the sandbox's existing
`"the mOTP PIN is stored, round-trips and defaults to empty in older documents"` check. Mutation-verified:
reverting the fix fails both the JVM test and the sandbox check with the exact values described above
(`got 60`, PIN not empty).

## Unfinished-feature sweep (no new gaps found)

Every `android:key="@string/settings_key_*"` in `res/xml/preferences.xml` (44 keys) was traced to a
`Settings.kt` getter/setter and from there to at least one call site outside `Settings.kt` — no preference
is rendered in the UI without backing logic. No `TODO`/`FIXME`/`XXX`/`HACK` marker, stub, or
not-yet-implemented branch remains in `app/src/main`. The remaining candidates in
`docs/andotp-open-issues-triage.md` are unchanged: optional feature gaps that are product decisions, or
items that need a device/emulator run this environment still does not have.

## Verification (host gates re-run)

- `python3 -m unittest discover -s scripts/tests` — **82 tests** (PyYAML available in this run; some
  toolchain-gated tests skip without `kotlinc`/`java`, as before).
- `scripts/kotlin-sandbox/run.sh` — **all sandbox checks passed**, including the two new mOTP
  period/PIN assertions.
- `scripts/run-jvm-tests.sh` — **112 passed, 0 failed** (110 prior + 2 new `EntryJsonTest` cases).
- `scripts/kotlin-sandbox/compile-android-tests.sh` — **COMPILE OK**.
- Mutation check: reverting the `EntryJson.kt` fix reproduces both new failures with the exact
  mismatched values described above, then was re-applied.
