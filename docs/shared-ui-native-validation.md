# Shared Compose migration: native validation handoff

The source/JVM checks are not a release sign-off. The current workspace has no Android SDK,
emulator or Xcode, and GitHub workflow dispatch returned `403 Resource not accessible by integration`.
No native workflow was started by that rejected request. Do not weaken workflow permissions or
introduce credentials into the repository to bypass the connection's Actions permissions.

## Run the existing workflows

With an authorized GitHub connection/account, select branch `arena/22c4b5b2-offlineotp` in Actions:

- **Android build** (`android.yml`): includes emulator execution of `ComposeVaultDeviceTest` and
  `IssuerArtworkDeviceTest` for the configured flavors.
- **iOS build** (`ios.yml`): use the default macOS runner unless runner capacity requires a different
  supported image. This must compile the new scanner controls KMP/Swift interface.
- **Compose desktop validation** (`compose-desktop-validation.yml`): real Gradle/Compose artifacts
  across the configured Windows/macOS/Linux matrix; JVM sandbox stand-ins do not replace this gate.

Equivalent CLI dispatch commands (run outside the sandbox with Actions permission):

```sh
gh workflow run android.yml --ref arena/22c4b5b2-offlineotp
gh workflow run ios.yml --ref arena/22c4b5b2-offlineotp -f runner=macos-15
gh workflow run compose-desktop-validation.yml --ref arena/22c4b5b2-offlineotp
```

Retain run links, exact tested commit and sanitized build/test logs. Do not attach vault files,
real provisioning payloads, passwords, derived keys, preference exports or recovery material.

## Added instrumentation coverage

`ComposeVaultDeviceTest` now includes tests for shared Settings busy/closed navigation, setup-shell
native content attachment/release, About dependency version/full-license rendering and credential
shell dismissal/input clearing/replay rejection. These run with fixture values only. The test source
compiles under the sandbox gate, but **has not executed on a device or emulator**. A component-host
attachment test does not prove full material-intro fragment restoration/reparenting is correct.

## Required integration checks beyond component tests

Use a disposable test vault and backups, never the user's live vault.

1. First run: welcome → encryption → credentials → platform-backup information → completion.
   Rotate/recreate at each step, including empty/invalid credential confirmation. Test create and
   encrypted-backup restore, picker/password cancellation, failed restore/retry and single completion.
   Verify native pager chrome does not reappear, Back is blocked during work and no credential is
   restored into a saved hierarchy or Bundle. Confirm the one-use key handoff still opens the vault.
2. Vault chrome: small phone, landscape, large text, keyboard and TalkBack. Test all five categories,
   tags (including untagged and SINGLE rule), sort, search, clear filters and creation. Ensure hidden
   drawer/FAB/menu views cannot take focus or intercept gestures and every former action is reachable.
3. Settings: busy Back blocking, canceled/stale dialogs, lock/reunlock while prompts are open and
   Activity recreation. Test authentication/encryption transitions using disposable data; ensure
   invalid combinations are rejected before any write and a failed transaction leaves a usable vault.
4. Protected input: password visibility toggle, confirmation, IME, accessibility/autofill policies,
   empty separate-backup credential clear, cancellation and process death. Existing backup passwords
   must still open existing backups after source changes; plaintext removal requires explicit consent.
5. Biometric quick unlock: unavailable/not-enrolled/cancel/lockout/success, rotation and lock while the
   OS prompt is open. A revoked prompt must never persist a shortcut. Test native KeyStore invalidation
   and recovery separately. Authorized destructive reset is intentionally absent from automated tests.
6. Provider OpenPGP: discovery, absent provider, installation/uninstallation, provider switch, signing
   key selection/cancellation, lock/recreation while provider UI is open, and service disconnection.
   Late or replayed results must not change settings; services must be unbound after completion.
7. About: both flavors, real generated license catalog/version metadata, license text/URLs, external
   browser absent/clipboard fallback, donation links and canceled special-feature opt-in.
8. Google multipart import: camera/image routes, duplicate/mixed batches, cancel, lock/reunlock and
   stale confirmation; import once only and wipe every canceled buffered account.
9. iOS: build exported scanner controls interface, camera permission denied/unavailable/cancel/success,
   VoiceOver, modal dismissal and backgrounding. VisionKit must stop scanning when its host disappears.
10. Desktop: real Compose window/keyboard/scroll/render tests; verify unchanged supported imports,
    exports, editors and settings on each release OS. Account for capability-specific native adapters,
    not just equal menu labels. Device-only hardware/biometric tests require appropriate hardware.

The migration remains open until these gates provide evidence. Keep hidden compatibility engines
until native restoration/default/persistence tests justify removing them; source removal alone is
not proof of feature parity.

### Busy-state follow-up

Settings/Backup/hardware grouped actions now expose disabled semantics during work rather than
merely ignoring clicks in the host. The Settings component test asserts that both Back and the
sample action are disabled. Verify this on real Compose, including detailed action rows and TalkBack;
no emulator execution is implied by the local test-source compilation.

### Setup-navigation follow-up

Verify busy Back/Next are disabled during create, restore picker, backup-password prompt, read/decrypt
and vault creation. Cancel/error must release retry/navigation. Final Next remains disabled until
success, then permits finishing while Back remains disabled. Also attempt pager swipes: the completion
fragment enforces the same busy/completed rules as the common shell. Rotate/background during each
transition and confirm no stale callback can navigate or reopen committed credential configuration.
The extended component and sandbox tests are not evidence of native pager execution.

### Settings feedback follow-up

Verify save failures and encryption/authentication-result feedback renders on the shared page, is
announced politely by accessibility services, permits tap dismissal and expires as intended. Repeat
the same message rapidly and confirm an older expiry/dismissal cannot remove its replacement.
Lock/recreate/destroy with pending feedback and confirm it neither leaks into a new session nor
retains a destroyed Activity. The added instrumentation source compiles locally; it has not run on
a device. Import/export and biometric modal feedback still retain their existing native delivery.

### Preference-transfer follow-up

Test export success/failure and picker launch/grant failures with the common Settings banner.
Lock/unlock or rotate while the export picker is open: old serialized buffers must be wiped, never
written by a late result and never put in saved state. A lost pending export must be retried.
Cancel/dismiss/replace the import confirmation, then attempt a delayed callback: no settings may be
applied. Confirm a valid document once and verify subsequent callback delivery cannot import again.
Import success/unknown-key and recreation feedback remain native pending a safe handoff design.

### Biometric feedback follow-up

Exercise unavailable/not-enrolled selection, successful enrolment, OS cancellation/lockout and
save failure. Availability/success should appear on the shared Settings page, while an enrolment
failure should remain readable inside the password dialog and allow a retry. During native work,
password entry/confirmation must be disabled; cancel must revoke permission to persist the key.
Dismiss, lock or replace the form while pending and verify a late native callback cannot update
feedback or save credentials. These flows have sandbox guards, not executed device evidence.

### Import feedback recreation gate

Import ordinary settings and settings containing a backup password, with/without unknown keys.
After recreation, verify one shared notice includes success and skipped counts where relevant.
Rotate again: the consumed handoff must not be saved/replayed. Lock or terminate the process before
restoration: no result may be shown in a different session. Inspect saved state to confirm it holds
only counts, a boolean and session generation, never imported values, URI or document bytes.

### Backup picker feedback gate

On devices/profiles without document-picker handlers or with blocked picker access, verify the
shared Backup page displays the launch failure after fallback handling. Verify missing configured
backup-location feedback too. Repeat failures rapidly, tap-dismiss, then lock/destroy before the
expiry: no old timer may clear newer feedback or publish into a closed page. Task-completion
notices that outlive this Activity are explicitly not migrated in this increment.

### Backup non-closing failure feedback

Exercise corrupt/unreadable import, export write failure, rejected URI schemes, unavailable
OpenPGP provider, missing recipient/secret keys, empty provider output, unverified signatures and
provider errors. Verify failures remain readable on the shared Backup page and can be dismissed
or replaced without stale expiry. Confirm URI cleanup, service release, retry and lock/session
checks are unchanged. Success/closing and detached callback notices are intentionally still native.

### Backup success destination handoff

Export and import successfully through every supported format. On return, verify a single generic
success notice appears on the shared vault page, never containing document payloads or filenames.
Exercise result delivery before/after a vault frame exists and verify it waits for the matching
session/key. Lock or replace the vault session before result delivery: no old success may appear.
Rotate/kill while returning: operations must never replay merely to recover feedback; losing an
unpersisted transient notice is preferable to crossing sessions. Check detached restore-save failure
still reports via its existing native adapter. These scenarios have not been run on a device.

### Executed handoff regression coverage (sandbox only)

The Backup-result receiver now has executed host checks for unknown result kinds, a valid result
arriving before any vault view, a mismatched frame generation, delivery after the matching frame
appears, consumed-pending replay, stale generations and locked-session rejection. These use stub
Activities/Compose state, not Android Activity-result or rendering execution. Updated UI/scheduler
checks and compilation of all 28 Android instrumentation sources pass after the vault/Backup edits.

### Vault export completion feedback

For email, stored password-vault, Thunderbird, LinSCP and browser CSV exports, exercise successful
writes and provider failures. Verify a shared success/failure notice, buffer wiping and failed-file
cleanup. Lock/reopen a different session before picker return: no old outcome may be shown in the
new vault. Check rapid consecutive results do not let an old expiry clear newer feedback. Source
and stub checks pass, but none of these document-provider scenarios have run on Android here.

### Import completion and password retry

Import email, passwords, Thunderbird and LinSCP records and verify a shared success notice after
closing their forms. Wrong email/password inputs must show the common in-dialog failure and permit
retry without an additional Toast. Dismiss/replace/lock email import before a delayed password
callback; it must not apply or access released bytes. Repeat a Thunderbird confirmation callback:
once the original preview/prompt is consumed, no second import may occur. Sandbox bridge checks
are executed; real window, file-provider and accessibility behavior is not validated here.

### Google import completion notice

Complete a multi-part Google Authenticator import, including duplicates and storage failure.
Verify the confirmation closes and the shared vault notice shows counts or save failure, without
account labels/secrets. Lock/replace the session before confirming: the original epoch must not
publish a result into a new session. Verify no-data exports and immediate import-picker launch
failures for email/Thunderbird/LinSCP show shared feedback. Modal/window ordering remains untested.

### Google batch-progress warnings

Scan a multipart QR twice, scan a part from another export, provide invalid data, and simulate
camera/image-picker launch failure. The shared progress dialog must show a readable localized
warning while retaining the right batch; cancel must still wipe it. A terminal capacity/invalid
batch error that closes collection should show the common vault banner. Ensure picker launch
failure does not leave a second, hidden banner behind the progress dialog. Executed sandbox tests
verify duplicate-dialog text/collector retention, not actual camera/picker window behavior.

### QR visible-surface feedback

During multipart Google collection, scan an ordinary OTP QR/deep link: show the shared progress
warning without losing the staged batch or importing that entry. Test standalone malformed QR
and entry-save failure; the banner must use the original scan session. Simulate camera/image launch
failure from URI entry both with and without a staged batch and verify only the currently visible
shared surface reports the failure. Sandbox tests cover batch preservation/warning text, not camera
or native accessibility behavior.

### Image QR decoder feedback

Select unreadable, oversized, malformed, no-QR, bad-checksum and invalid-format images, both during
batch collection and standalone import. Verify a shared in-dialog warning or vault banner as
appropriate, with no native Toast from the active route. Cancellation must remain silent and a
staged batch must be retained where valid. Stub decoder tests cover callback-only error reporting;
Android bitmap/ZXing/provider execution and accessibility have not been run here. Existing static
API instrumentation still uses the compatibility wrapper, so add active-Activity coverage on device.

### Remaining vault transfer/read feedback

Exercise unreadable email/password/Thunderbird/LinSCP input, CSV preparation/launch failure, no
exportable archive, malformed incoming URI and backup-timestamp commit failure. Expect shared
feedback tied to the current operation session. Empty LinSCP input must release staged entries
and use the shared secret retry error for protected input or page feedback for plain input, not a
Toast behind a form. Fatal auth failure still uses native feedback before task removal; do not
attempt to expose a vault frame for that notice. Actual Android execution remains outstanding.

### About feedback gate

Without a browser handler, open a notice/license URL and verify clipboard fallback plus shared
feedback. Exercise version-tap count/reset, existing special-feature status, opt-in cancel/confirm,
rapid repeated notices, Back and recreation before expiry. Confirm no stale timer clears a new
message and no old host publishes after destruction. These remain device gates; sandbox checks
exercise state ownership, not clipboard access or native accessibility announcements.

### Standalone authentication biometric feedback

Cancel/fail/lock out biometric unlock and verify status appears in the shared authentication page
and is announced politely. Retry through password, hardware or biometric: the old status should
clear. Background, lock or destroy the page while native work is pending: no late callback may
publish into an inactive/new host. Verify native protected input/autofill/IME behavior is unchanged.
Fatal missing-credential/migration-close feedback remains native. No device execution is claimed.

### Hardware management status

Verify no-candidate, password-vault-only, copy success/uncertainty, enrolment success/failure and
removal results appear in the shared status area, without Toasts. Uncertain outcomes must remain
readable until replaced by a later explicit operation. Lock/change sessions while USB/NFC work is
pending: stale callbacks must not replace status or persist credentials. Stub host checks cover
status publication/session rejection; actual USB/NFC, touch/PIN and accessibility need devices.

### Shared persistent-status component

Check status messages on grouped tools/hardware pages, authentication, biometric enrolment and
hardware unlock across Android/Desktop/iOS. Verify consistent readable styling, no empty status
node/spacing, polite screen-reader announcements and no duplicate native Toast on hardware unlock
failure. Retry/background/close must retain existing message-lifetime rules. The new authentication
component test compiles locally but has not run on Android; VoiceOver/TalkBack and real device
hardware/biometric checks remain required.

### Android system navigation overlap regression

The Android Activity content root now owns the union of system-bar, display-cutout and IME insets;
children receive consumed insets to avoid double-padding legacy fitsSystemWindows/Compose roots.
Themed activities and the separate intro host install this policy. Test Android 15+ target-35
edge-to-edge behavior with gesture and three-button navigation, portrait/landscape, cutouts, keyboard
open/closed, and rotation. Bottom buttons/cards/feedback must remain above the navigation bar; the
keyboard and navigation inset must use their maximum, not their sum. Verify no extra top/bottom gap
and that system-bar visibility changes reapply cleanly. Camera capture deliberately keeps its own
fullscreen policy. Sandbox checks cover inset union and non-accumulating padding; device visual
verification remains required.
