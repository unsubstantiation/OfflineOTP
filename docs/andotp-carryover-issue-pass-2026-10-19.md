# andOTP carry-over pass — issue #39 (2026-10-19)

This pass works through [OfflineOTP issue #39](https://github.com/unsubstantiation/OfflineOTP/issues/39),
the carry-over list from [the andOTP tracker triage](andotp-open-issues-triage.md). The triage
splits the upstream reports that were not already fixed into **confirmed defects** (`❌ applies`)
and **device-verification items** (`⚠️ verify`). This document records what changed, what is now
covered by an automated test, and what still needs a real device or provider-specific run.

The implementation was merged in [PR #51](https://github.com/unsubstantiation/OfflineOTP/pull/51)
and [PR #52](https://github.com/unsubstantiation/OfflineOTP/pull/52). The Android emulator matrix
(fdroid/play × API 28/36) passed in [CI run #39534025930](https://github.com/unsubstantiation/OfflineOTP/actions/runs/39534025930)
on commit `282c1d3`. This verifies those instrumentation scenarios on emulators; it does not prove
real-device OEM/KeyStore, SAF/cloud-provider, OpenPGP-provider, or TalkBack behavior, which remain
manual acceptance gates.

## Fixed

### #591 — the initial setup screen forces portrait orientation

* `IntroScreenActivity` was locked to `android:screenOrientation="portrait"` in the manifest. The
  lock is gone, so the wizard follows the system auto-rotate setting like every other screen.
* Removing the lock exposed a latent rotation defect: the wizard commits the vault and the
  credentials on its last slide, so a recreation used to come back to a slide whose inputs were
  empty. Forward navigation was then blocked (`canGoForward == false`) and the only remaining copy
  of the freshly derived vault key lived in the destroyed activity.
* `IntroScreenActivity` now saves **only the setup outcome** in its instance state (never key
  material), restores the finished slide's success state, and refuses to run the setup task a
  second time after recreation. `FinishIntroTask`'s derived key is published to the one-use,
  generation-bound `VaultSession` handoff as soon as the vault commit succeeds, so a rotation
  between the commit and the activity result can no longer lose it.
* Tests: `IntroScreenLaunchTest` (no requested orientation, survives repeated recreation) and
  `scripts/tests/test_carryover_issue_fixes.py::IntroScreenOrientationTest`.

### #712 — re-lock on inactivity does not fire

* Root cause: the idle timer was a `Handler` delay. `MainActivity.onPause` cancelled it and
  `onResume` restarted it, and a `Handler` delay does not advance during deep sleep. Time spent
  with the screen off or the app in the background therefore never counted, which is exactly the
  reported scenario ("lock phone while leaving the app open, wait, unlock: codes still visible").
* `ScreenTimerController` now keeps an `elapsedRealtime` deadline (which *does* advance during
  sleep) across a foreground suspension: `suspendForeground()` drops the callback but keeps the
  deadline, and `hasInactivityExpired()` reports whether it passed. `MainActivity` checks the
  deadline on resume, re-arms the timer, and starts the normal authentication flow (which revokes
  the session key through `beginUnlock`) before showing the list again.
* `Settings.getAuthInactivityDelay()` fell back to a hardcoded `0` while the preference declares a
  30-second default; a store without the written default silently disabled the feature. The
  accessor now falls back to `@integer/settings_default_auth_inactivity_delay`.
* Tests: `ScreenTimerControllerTest` (background time counts, re-arm from now, fired/cancelled
  timers never report expired, `pause()` vs `suspendForeground()` semantics) and
  `test_carryover_issue_fixes.py::InactivityRelockTest`.

### #880 — the "Hidden" placeholder wraps onto two lines

* All three card layouts keep the (still localized) hidden-token label on one auto-sized line:
  `maxLines="1"` plus `autoSizeTextType="uniform"` with a minimum/maximum matching each card size.
  Short translations are unaffected; long ones shrink instead of wrapping on small screens.
* Test: `test_carryover_issue_fixes.py::HiddenLabelLayoutTest`.

### #805 — the keyboard hides the entry dialog's Save/Cancel buttons

* `ManualEntryDialog` and `PasswordEntryDialog` now request
  `SOFT_INPUT_ADJUST_RESIZE` on their windows. The entry form was already a `ScrollView`, so the
  content scrolls while the buttons stay reachable instead of the dialog being panned.
* Test: `test_carryover_issue_fixes.py::DialogImeTest`.

## Code fixes in PR #52

| Upstream | Code-level change |
| --- | --- |
| #208, #736 | Fail-closed authentication results; preserve picker-selected action/URI across relock and recreation; retry only after re-authentication; handle password cancellation and screen-off. |
| #376, #533, #865, #895, #948 | Harden SAF content-URI flows and missing-picker/provider errors; clean up failed or canceled manually-created backup documents. Automatic backups continue to use the configured SAF location. |
| #406 | Guard stale entry indexes; obscured-touch events continue to be rejected by `BaseActivity`. |
| #498 | Improve Speed Dial focus, overlay labeling, and TalkBack traversal behavior. |
| #873, #948 | Add Settings and Backup activity launch/recreation instrumentation fixtures. |
| #892 | Add the reported CCP `otpauth://` URI as parser regression coverage. |
| #923 | Use monotonic delayed single/double-tap sequencing with coverage for the rapid-third-tap case. |
| #976 | Add warning-confirmed, bounded strict-UTF-8 plaintext restore; plaintext export remains unavailable. |
| #995, #996 | Require a ready OpenPGP provider and valid recipients for encryption; reject empty output and report failures. |
| #847, #879 | No KeyStore policy change in this PR; retain the fail-closed/no-key-regeneration behavior. |

## Verification recorded at PR #52 merge

* `scripts/kotlin-sandbox/run.sh` — passed.
* `scripts/run-jvm-tests.sh` — 136 passed.
* `scripts/kotlin-sandbox/compile-android-tests.sh` — passed; this is compile-only.
* `python3 -m unittest discover -s scripts/tests -v` — 95 tests run, 7 skipped because PyYAML is unavailable.
* `git diff --check` — passed.

## Follow-up verification — 2026-10-20

* GitHub Actions run [#39534025930](https://github.com/unsubstantiation/OfflineOTP/actions/runs/39534025930), commit `282c1d3` — passed source checks, sandbox/host tests, all-variant build, Gradle host tests, lint, manifest policy, and the complete instrumentation matrix: fdroid/play × API 28/36 (all four connected-test jobs passed).
* The separate play/fdroid debug/release APK workflows for `282c1d3` all passed.
* `scripts/kotlin-sandbox/compile-android-tests.sh` — passed after the instrumentation fixes.
* `python3 -m unittest discover -s scripts/tests -v` — 101 passed.
* `bash -n scripts/ci/start-emulator.sh`, `python3 scripts/check_build_inputs.py`, and `git diff --check` — passed.

## Instrumentation coverage added

`ActivityReachabilityDeviceTest` establishes an unlocked test session, launches Settings and
Backup with fresh preferences, checks the toolbar, and recreates both activities. These fixtures
passed in all four emulator matrix cells in CI run #39534025930. That verifies activity reachability
on the tested emulator images, not SAF provider behavior or TalkBack operation:

| Upstream | Surface | Instrumentation |
| --- | --- | --- |
| #873 | Settings | launch and recreation with an unlocked vault |
| #948 | Backup | launch and recreation with an unlocked vault |
| #591 | first-run wizard | orientation + recreation checks |

## Remaining device/provider gates

The emulator matrix above clears the CI-only instrumentation gate. These reported paths still need
a real-device, provider, or accessibility check before they can be claimed fully verified; emulator
success does not prove behavior on a vendor ROM or external document provider:

* **#208** Back-key/gesture authentication flow — exercise cancellation, recents, and relock on device.
* **#376, #976** Restore paths — test legacy encrypted and plaintext backups end-to-end, including cloud documents.
* **#406** Obscured-touch crash — retest with the BitWarden safe dialog.
* **#498** TalkBack — verify focus order and overlay dismissal on-device.
* **#533, #736, #948** — test Files/SAF providers, missing-provider behavior, and screen-off/home during export/import; the emulator activity-launch fixture does not exercise an external provider.
* **#847, #879** — verify Android 11/Pixel KeyStore and MIUI/OEM behavior on hardware; this PR does not change the fail-closed policy.
* **#865, #895** — verify Google Drive and Work Profile behavior through SAF.
* **#873** — Settings/Backup reachability and recreation passed on API 28/36 emulators; verify the reported fresh-install path on the affected physical ROM if available.
* **#932** — the Settings screen launches and recreates in CI, but the reported crash-on-touch path requires identifying and interacting with the affected preference on-device.
* **#591** — the wizard's unspecified orientation and repeated recreation passed in CI; physically rotate a device with auto-rotate enabled to verify sensor/layout behavior.
* **#892** — scan the reported QR and malformed-image cases on device.
* **#923** — verify configured single/double-tap behavior in the app; host controller tests do not validate preference UX.
* **#995, #996** — complete encrypted exports against current OpenKeychain and a real destination provider.
* **#712** — host deadline tests pass, but no instrumentation test simulates screen-off/deep-sleep; confirm the reported lock timeout on a physical device.

## Follow-up regression coverage — 2026-10-22

A follow-up review added runnable regression tests for the paths that can be exercised without
vendor hardware or external providers:

| Report(s) | Added/expanded coverage | Boundary |
| --- | --- | --- |
| #376 | Host JUnit NIST AES-GCM vector plus current-format PBKDF2 backup framing, legacy SHA-256 backup compatibility, wrong-password and tamper rejection; sandbox test writes through `BackupHelper` to a fake `content:` provider and reads it back. | A fake resolver does not prove Google Drive behavior. |
| #533, #865, #895 | Fake content-provider writer/read round trip, plus a fake SAF tree test that verifies automatic backups request new unique Drive-compatible documents; permission checks and invalid-password refusal are covered. | Files/Drive/Work Profile grants still need provider/device verification. |
| #847, #879 | Instrumentation round-trip using a unique AndroidKeyStore RSA alias; lookup after deletion is asserted not to regenerate a key. | Pixel 4 / MIUI behavior is not represented by the CI emulator KeyStore. |
| #892 | Device test generates a PNG with the exact reported CCP `otpauth://` URI, decodes it through `ScanQRCodeFromFile`, and parses the resulting account; malformed image input is also rejected. | Tests ZXing and Android BitmapFactory, not the specific third-party camera/scanner app. |
| #208, #406, #498, #591, #712, #736, #873, #923, #932, #948, #976, #995, #996 | Existing focused auth/session, obscured-touch, accessibility source, timer, SAF failure, reachability, tap-sequence, plaintext restore, and OpenPGP policy tests remain in the host/device suites. | The detailed external-app, TalkBack, cloud-provider, OEM, and UI-interaction gates below remain manual. |

`EncryptionHelper` now retains its `IvParameterSpec` overload for compatibility but converts it to
`GCMParameterSpec(128, nonce)` before initializing AES-GCM. This preserves the existing nonce/tag
framing while allowing the backup regression vectors to run on standard desktop JCA providers as
well as Android. The new device tests are wired into the repository's API 28/36 CI matrix, but this branch's
instrumentation has not yet run there. The previously passing matrix was on the base commit; even a
passing branch matrix cannot substitute for testing the specific vendor devices and external apps
in the remaining-gates list.

## Completion pass — 2026-10-22: master build fix and device-level regressions

The follow-up above left two gaps that this pass closes.

### The master build was red

The #932 settings-tap test called `Preference.performClick()` with no arguments. The framework
`android.preference.Preference` has no public click entry point (its click handling is package
private), so `:app:compileFdroidDebugAndroidTestKotlin` failed on `master` after PR #54 and the
whole instrumentation matrix was skipped. The test now dispatches the touch through the real
settings list row:

```kotlin
val list = fragment.getListView()
val adapter = requireNotNull(list!!.adapter)
list.performItemClick(null, position, adapter.getItemId(position))
```

That is the same path a finger takes into `PreferenceScreen`'s item-click listener, so the #932
crash-on-touch scenario is exercised rather than approximated. The test also reads the stored
boolean before and after the click, which fails if the touch is not actually dispatched to the
preference.

### Device regressions for the remaining checklist

| Report(s) | New automated coverage | Boundary |
| --- | --- | --- |
| #208 | `AuthenticationFlowDeviceTest.aCancelledUnlockPromptCannotRevealTheVault` launches the locked app, presses Back on the prompt and asserts the session key is gone and `MainActivity` reached `DESTROYED` (its `finishAndRemoveTask()` fail-closed path). `theConfiguredPinStillUnlocksTheVault` proves the same flow still unlocks with the right PIN, so "never unlock" cannot pass the test. | The device-credential (`Keyguard`) variant is Android's own UI and is not scripted here. |
| #406 | `ObscuredTouchDeviceTest` builds real `MotionEvent`s through the public 14-argument `obtain(...)` overload and asserts an `FLAG_WINDOW_IS_OBSCURED` (and, from API 29, `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`) tap is not delivered, while the same unobscured tap reaches a click listener. | Reproduces the system's obscured-touch flag, not the BitWarden app itself. |
| #712 | `AuthenticationFlowDeviceTest.backgroundTimeCountsTowardsTheInactivityRelock` activates a session, moves the activity to `CREATED`, sleeps past a one-second timeout and resumes: the prompt appears and the session key is revoked. | Emulator pause/resume, not a physical screen-off or deep-sleep cycle. |
| #736 | `BackupScreenOffPolicy` now owns the teardown decision (`allowExit` plus no pending picker, action or deferred restart) and `BackupActivity` delegates to it; `BackupScreenOffPolicyTest` pins all eight pending combinations plus the running-task case. | The policy is exhaustive; an OEM screen-off broadcast during a real cloud-provider export still needs a device. |
| #376 | `BackupRestoreDeviceTest` restores a current-format PBKDF2/AES-GCM backup and a legacy SHA-256-keyed backup through `EncryptedRestoreTask`, and asserts distinct failure outcomes for a wrong password, a truncated frame and a missing document, all on the real Android crypto/bounded-read stack. | The fixture is an app-private cache document; SAF provider delivery is covered by the fake-provider checks and remains a provider/device gate. |
| #976 | `BackupRestoreDeviceTest` restores a legacy plain-text document through `PlainTextRestoreTask` and rejects malformed UTF-8/JSON with `backup_toast_plaintext_invalid`. | Same cache-document boundary as #376. |
| #923 | `ActivityReachabilityDeviceTest.tapPreferencesDefaultToNothingAndMapEveryListedValue` pins the documented default (both taps do nothing) and maps every `TapMode` back from the preference string, so a card tap action can no longer be silently unreachable. | Gesture delivery itself is covered by `TapSequenceControllerTest`; the preference UX is not driven by a finger here. |
| #932 | The settings tap test above now touches the row and asserts the value persists and the screen survives. | Still a check for the affected preference/path only; the original ROM is not reproduced. |

The sandbox stubs were corrected alongside the tests: `MotionEvent` now carries the platform's
`FLAG_WINDOW_IS_*` values (`0x200`/`0x400`, previously `1`/`2`) and the flag-word `obtain(...)`
overload, `AdapterView` owns the adapter/item-click members, and the test-only androidx stand-ins
gained `ActivityScenario` state transitions and the lifecycle monitor.

### Verification for this pass

* `python3 -m unittest discover -s scripts/tests` — **117 tests, 9 skipped** (PyYAML absent).
* `scripts/kotlin-sandbox/run.sh` — **102 checks passed**.
* `scripts/run-jvm-tests.sh` — **144 passed, 0 failed** (including `BackupScreenOffPolicyTest`).
* `scripts/kotlin-sandbox/compile-android-tests.sh` — **COMPILE OK**, ten instrumentation sources.
* `git diff --check` — passed.

### Remaining acceptance gates

Every report in issue #39 now has an automated regression or a pinned policy check. The gates that
remain are hardware, provider and accessibility acceptance checks rather than known defects:

* **#847, #879** — Pixel 4 / MIUI KeyStore behavior on physical hardware.
* **#376, #533, #736, #865, #895, #948** — a real Files/Google Drive/Work Profile provider during an
  export and a restore, including screen-off while the provider is open.
* **#406** — the BitWarden safe dialog specifically.
* **#498, #591** — TalkBack traversal and a physical rotation.
* **#892, #923** — the reported scanning app and a physical tap sequence.
* **#995, #996** — OpenKeychain encryption on a device.
* **#208, #712** — the device-credential prompt and a real screen-off/deep-sleep cycle.
