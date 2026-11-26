# Shared UI completion status

**Migration remains in progress; no release/native-runtime completion is claimed.**
This is the current source-level status, superseding older incremental entries in the ownership
log and the historical cross-build audit. Stub/JVM checks do not verify actual Android/iOS layout,
window attachment, accessibility, native service binding or secure hardware behavior.

## Source changes implemented

- Android main vault uses shared header, search, category tabs, tag filters, sort, add, tools and
  account cards. Toolbar, speed-dial, tag drawer and category controls are hidden compatibility
  engines; the drawer is locked closed. Settings and hardware capabilities are available from
  the shared tools. Every published action is guarded against session replacement.
- Google Authenticator multipart collection/progress and final preview use shared selection and
  confirmation forms. Buffered secrets remain host-owned and are destroyed on cancel/lock.
- Settings overview has shared routes for all active preference groups including authentication,
  encryption, biometric quick unlock, backup credentials/source/retention, special features and
  guarded destructive reset. No visible advanced native-preference destination is offered;
  saved legacy destinations reopen the shared overview. The old preference Fragment remains
  hidden for compatibility/listener behavior, not as a visible feature implementation.
- Android OpenPGP provider selection uses a shared selector over discovered services. Signing-key
  selection is provider-owned UI reached through a guarded native service adapter, with ownership,
  session, mode and provider checks at result time, and service release on completion/lock/destroy.
- AboutActivity mounts common AboutContent, including generated dependency versions, license URLs
  and full license text. Flavor donation actions and the version-tap opt-in confirmation remain.
  Old About fragments/layouts remain unused compatibility code, not visible pages.
- Android onboarding welcome, step bodies and navigation chrome are shared. The material-intro
  pager is retained only as the fragment restoration/validation/result adapter; its native buttons,
  indicator and welcome layout are not mounted in the shared shell. Real pager reparenting and
  recreation behavior have not been verified on Android.
- iOS scanner's app-owned Cancel action uses Compose. VisionKit retains camera feed, recognition,
  guidance and native permission UI. The new Kotlin/Swift bridge has not been built with Xcode.
- Standalone authentication and credential confirmation keep protected Android inputs where native
  autofill/accessibility/IME/security behavior is required. No key is put in shared presentation.

## Still to finish / verify

- Secondary Android container toolbars (authentication, backup, Settings) are now hidden. Settings
  and Backup use common Back navigation with host task-state enforcement; authentication retains
  its existing no-Up behavior. Verify system insets, keyboard, Back and blocked-task behavior on Android.
- Retire hidden legacy preference/drawer/intro compatibility code after native restoration/parity
  tests prove that moving its state behavior does not break existing installations.
- Audit cross-platform capability/default/route parity against real Android, desktop and iOS builds;
  platform-only Android device authentication/KeyStore/provider settings must remain explicit rather
  than being silently advertised as supported on desktop or iOS.
- Actual Android rendering, scrolling/keyboard/large-text, protected input, lock/recreation, first-run
  create/restore and generated-license loading; real biometric/KeyStore/reset/recovery and provider
  service/intent/cancellation tests. Sandbox tests never authorize destructive KeyStore reset.
- Real macOS/Xcode KMP+Swift build, simulator/device lifecycle and VoiceOver/camera tests; real
  desktop Compose rendering and keyboard/window-size testing. No native build/test results yet.

## Platform/feedback ownership

OS pickers, permission sheets, biometric/device-credential prompts, camera feed/recognition and
provider-owned signing/security-key UI remain native capability adapters. App-owned modal forms,
choices, progress and confirmations should use shared Compose. Most active Android page feedback now uses the common feedback surface described below.
Closing-task/detached-callback notices and some compatibility adapters still use native delivery;
shared source alone is not evidence of cross-platform visual/accessibility parity. Legacy AlertDialog helpers remain in
unused compatibility editors/fragments; live main-vault migration dialogs no longer use them.

## Environment blocker

This workspace is Linux with no Android SDK/adb or Xcode. Available Kotlin sandbox checks compile
against stubs, not real Android or UIKit. Completing the native integration gate requires an Android
SDK/device/emulator and a macOS/Xcode runner (plus device-only biometric/hardware validation).
Do not treat passing source/JVM checks as permission to mark the migration complete.

## Native test handoff

`shared-ui-native-validation.md` records workflow dispatch commands, the permission blocker,
new Android component tests and the remaining real integration matrix. The added tests are in
`ComposeVaultDeviceTest`, already included by the Android workflow's instrumentation class filter.
They compile locally; no emulator execution or native build pass is claimed.

## Current shared-feedback status

The common `SharedFeedbackBanner`/`SharedFeedbackPage` now renders transient page feedback for
Android Settings, Backup, vault and About hosts, with polite live-region semantics, tap dismissal,
revision-based replacement and cleanup on host close. These are shared presentation primitives;
Android still owns localized messages, expiry, session checks and native capability operations.

- **Settings:** no direct Toast/Snackbar calls remain in SettingsActivity or its compatibility
  Fragment. Import/export, policy, encryption and biometric result notices use common page/form
  presentation. Import success crosses recreation using only counts, a boolean and session
  generation, not imported values. Export buffers are generation-bound and wiped on completion,
  cancellation, lock and destruction. Destructive compatibility callbacks reuse guarded routes.
- **Vault:** non-closing transfer/import/export/read/CSV/timestamp/QR errors and results use common
  feedback. Captured operation generations prevent late results crossing sessions. Google batch
  warnings stay in the visible common progress dialog; image-decoder errors are reported to the
  host rather than emitted behind that dialog. Email retries and Thunderbird/export selections
  check prompt/buffer/preview ownership. The old static image decoder retains a native-feedback
  compatibility wrapper, not used by the active vault route.
- **Backup:** picker and non-closing task/provider failures use common feedback. Successful
  operations return only an allowlisted success kind and generation to the vault, which waits for
  a matching frame/key before consuming the notice. No task payload or filename is forwarded.
- **About:** active clipboard fallback, version-tap and feature opt-in notices use common feedback;
  old unused fragments remain compatibility code.
- **Authentication:** recoverable biometric errors use common in-page feedback. Shared buttons,
  native password/IME/autofill submission and native hardware/biometric retry paths clear old
  status. Background/close drops it; protected credential inputs and native biometric checks remain.

### Remaining feedback work

- Fatal authentication/missing-credential/migration failures that immediately close the task or
  Activity still use native delivery; do not expose a vault frame merely to announce them.
- Backup locked-result closing failures and detached restore persistence callbacks retain native
  delivery until a destination/lifecycle-safe handoff exists.
- Audit remaining Activities, utility adapters and unused compatibility surfaces for live app-owned
  feedback. Do not infer whole-app or cross-platform parity from the hosts above.
- Execute real Android result/recreation/process-death, camera/provider, accessibility and protected
  input tests, plus iOS/macOS builds and desktop rendering. All native gates remain open.

### Latest local checks

- Python source/regression suite: **671 passed**.
- Kotlin sandbox: **all checks passed**, including feedback revision/close rules, result handoff,
  foreground/retry cleanup, decoder callback-only reporting and batch-warning collector retention.
- Shared UI tests: **168/168 passed**; scheduler: **3/3 passed**.
- Android instrumentation source compilation: **28 files compile** against sandbox stubs.

These checks are not native rendering, Android instrumentation execution, secure hardware testing
or an Xcode build. See `shared-ui-native-validation.md` for the outstanding native scenarios.

Hardware-key management operation results now use its existing shared status area, with current-
session checks and persistent uncertain-outcome text. Native USB/NFC commands and envelope storage
remain platform-owned; transport/cancellation/accessibility tests have not run on hardware.
