# andOTP carry-over pass — issue #39 (2026-10-19)

This pass works through [OfflineOTP issue #39](https://github.com/unsubstantiation/OfflineOTP/issues/39),
the carry-over list from [the andOTP tracker triage](andotp-open-issues-triage.md). The triage
splits the upstream reports that were not already fixed into **confirmed defects** (`❌ applies`)
and **device-verification items** (`⚠️ verify`). This document records what changed, what is now
covered by an automated test, and what still needs a real device or emulator run.

Nothing here claims that the device matrix has been executed: the CI emulator jobs (fdroid/play ×
API 28/36) run the instrumentation suite that now contains these checks, and that is the gate.

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

## Device coverage added

The triage items below are crash/flow reports that cannot be proven from a host JVM. The new
`ActivityReachabilityDeviceTest` launches the named surfaces and recreates them on the emulator
matrix, which turns "retest on the current build" into a CI result rather than a manual step:

| Upstream | Surface | Instrumentation |
| --- | --- | --- |
| #873, #932 | Settings | launches; fails closed (finishes) without an unlocked vault |
| #948 | backup entry point | covered indirectly by the settings/backup reachability smoke plus the existing backup task tests |
| #591 | first-run wizard | orientation + recreation checks |

## Still a device/CI gate (not modified in this pass)

These need a real-device or emulator run before they can be closed; no code change is justified by
the current evidence:

* **#208** Back-key bypass — the flow (Back on `AuthenticateActivity` → `RESULT_CANCELED` →
  `finishAndRemoveTask`, and `VaultSession` keys being process-memory state) is fail-closed in the
  source, but the physical/gesture Back path needs a device run.
* **#376, #976** restore paths — bounded, all-or-nothing outcomes exist; legacy encrypted and
  plain-text backups need an end-to-end run.
* **#406** obscured-touch crash — `BaseActivity` rejects obscured/partially obscured events; retest
  with the BitWarden safe dialog.
* **#498** TalkBack — no automated accessibility claim is made; the hardening doc keeps this as an
  acceptance gate.
* **#533, #736, #948, #995, #996** — SAF backup/restore and OpenPGP exports need provider and
  OpenKeychain runs (screen-off/home during export included).
* **#847, #879** — KeyStore/StrongBox and OEM (MIUI) behaviour needs hardware runs; the code keeps
  the fail-closed policy (no silent key regeneration).
* **#865, #895** — Google Drive and Work Profile behaviour through SAF.
* **#892** — QR decoding is bounds-checked; a malformed-image corpus run on device is still needed.
* **#923** — single-tap handling; the tap mode settings and the delayed single-tap listener are
  unchanged by this pass, and the report needs a device repro (the default for both tap modes is
  `nothing`, so a user who never changed the setting gets no tap action by design).
* **#712** now has host coverage, but the screen-off/background scenario should still be confirmed
  on the emulator matrix.
