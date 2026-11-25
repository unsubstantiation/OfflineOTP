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
choices, progress and confirmations should use shared Compose. Android Toast/Snackbar delivery
currently remains a native transient/accessibility feedback adapter with host-localized text;
it is not evidence of cross-platform visual feedback parity. Legacy AlertDialog helpers remain in
unused compatibility editors/fragments; live main-vault migration dialogs no longer use them.

## Environment blocker

This workspace is Linux with no Android SDK/adb or Xcode. Available Kotlin sandbox checks compile
against stubs, not real Android or UIKit. Completing the native integration gate requires an Android
SDK/device/emulator and a macOS/Xcode runner (plus device-only biometric/hardware validation).
Do not treat passing source/JVM checks as permission to mark the migration complete.
