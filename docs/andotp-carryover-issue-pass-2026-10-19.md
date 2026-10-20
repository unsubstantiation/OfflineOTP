# andOTP carry-over pass — issue #39 (2026-10-19)

This pass works through [OfflineOTP issue #39](https://github.com/unsubstantiation/OfflineOTP/issues/39),
the carry-over list from [the andOTP tracker triage](andotp-open-issues-triage.md). The triage
splits the upstream reports that were not already fixed into **confirmed defects** (`❌ applies`)
and **device-verification items** (`⚠️ verify`). This document records what changed, what is now
covered by an automated test, and what still needs a real device or emulator run.

The follow-up implementation is in [PR #52](https://github.com/unsubstantiation/OfflineOTP/pull/52)
(commit `b3b0f04` before this tracker update). Local checks passed as listed below, but nothing here
claims that the Android device matrix has been executed: the CI emulator jobs (fdroid/play × API
28/36) and provider-specific device checks remain the acceptance gate.

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

## Verification recorded for PR #52

* `scripts/kotlin-sandbox/run.sh` — passed.
* `scripts/run-jvm-tests.sh` — 136 passed.
* `scripts/kotlin-sandbox/compile-android-tests.sh` — passed; this is compile-only.
* `python3 -m unittest discover -s scripts/tests -v` — 95 tests run, 7 skipped because PyYAML is unavailable.
* `git diff --check` — passed.

## Instrumentation coverage added

`ActivityReachabilityDeviceTest` now establishes an unlocked test session, launches Settings and
Backup with fresh preferences, checks the toolbar, and recreates both activities. This provides
reachability fixtures, not proof of SAF provider behavior or TalkBack operation:

| Upstream | Surface | Instrumentation |
| --- | --- | --- |
| #873 | Settings | launch and recreation with an unlocked vault |
| #948 | Backup | launch and recreation with an unlocked vault |
| #591 | first-run wizard | orientation + recreation checks |

## Remaining device/CI gates

These need an Android Gradle/CI or real-device run before being closed; a host pass does not prove
the provider, lifecycle, OEM, or accessibility behavior:

* **#208** Back-key/gesture authentication flow — exercise cancellation, recents, and relock on device.
* **#376, #976** Restore paths — test legacy encrypted and plaintext backups end-to-end, including cloud documents.
* **#406** Obscured-touch crash — retest with the BitWarden safe dialog.
* **#498** TalkBack — verify focus order and overlay dismissal on-device.
* **#533, #736, #948** — test Files/SAF providers, missing-provider behavior, and screen-off/home during export/import.
* **#847, #879** — verify Android 11/Pixel KeyStore and MIUI/OEM behavior on hardware; this PR does not change the fail-closed policy.
* **#865, #895** — verify Google Drive and Work Profile behavior through SAF.
* **#873** — run the Settings/Backup instrumentation fixtures on the emulator/API matrix; no runtime instrumentation was run locally.
* **#892** — scan the reported QR and malformed-image cases on device.
* **#923** — verify configured single/double-tap behavior in the app; host controller tests do not validate preference UX.
* **#995, #996** — complete encrypted exports against current OpenKeychain and a real destination provider.
* **#712** has host coverage, but the screen-off/background timeout should still be confirmed on the emulator matrix.
