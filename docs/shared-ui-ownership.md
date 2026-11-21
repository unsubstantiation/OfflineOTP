# Shared UI ownership and migration boundary

The desired architecture is **one production implementation per portable screen**, with hosts
providing file pickers, key access, clipboard, lifecycle, camera and persistence adapters. Matching
screens implemented independently in Kotlin and Swift are not shared UI. Golden tests and generated
colors help, but do not solve that duplication.

## Current production boundary

- `ui/commonMain`: Compose forms, cards, actions, theme and dialog layout, consumed by Android
  and all desktop distributions. Portable changes belong here rather than in Activity builders.
- `ui-model/commonMain`: toolkit-free state, input and presentation logic, but currently depends
  on the JVM `core` module. The Multiplatform source-set name does not mean iOS can consume it.
- Android `VaultComposeView`: hosts shared forms, tracks request ownership and disposes staged data.
  MainActivity/controller still own session checks and native IO. No decoded entries or file bytes
  are added to reusable Compose forms.
- Desktop Compose host: uses the same form components with desktop IO/controller adapters.
- iOS: the release vault screen, its cards and its dialogs are now rendered by Compose in
  `:ui-foundation` and hosted from `ios/App/Sources/ComposeVaultHost.swift`. The Swift store remains
  the session, storage and capability authority. The old SwiftUI screens were deleted from
  `ios/App/Sources`; there is no selectable fallback. `:ui` still has only Android/JVM
  targets and depends on JVM `core`; portable values live in `:ui-contract` and `:ui-foundation`,
  which are native-compatible.

## Full iOS cutover

The last SwiftUI production screen was replaced by one shared Compose composition:

- `:ui-contract` owns `PortableCard`, `PortableChip`, `PortableVaultState`, `PortableRow`,
  `PortableSurfaceState`, `PortableInputBounds`, and `AutoLockOptions`: strings, numbers and
  booleans only, with no engine, toolkit or host type.
- `:ui-foundation` owns `PortableVaultScreen`, `PortableSurfaceDialog`, and the engine-free shared
  Compose dialogs consumed directly across Android, Desktop, and iOS (`AboutDialog`,
  `AutoLockDialog`, `PasswordChangeDialog`, `BiometricEnableDialog`, `PreferencesImportDialog`,
  `EntryConfirmationDialog`, `CheckboxConfirmationDialog`, `SharedEntryPinDialog`,
  `EntryCounterDialog`, `EntryDetailsDialog`, `TagsDialog`, `HideableDialog`,
  `MultiChoiceSelectionDialog`, `QrCodeDialog`, `EntryCopyDialog`, `EncryptedExportDialog`,
  `EncryptedRestoreDialog`, `OpenPgpExportDialog`, `OpenPgpImportDialog`, `EmailImportDialog`,
  `LinScpExportDialog`, `LinScpImportDialog`, and `UriImportDialog`).
- `PortableVaultViewController` (`iosMain`) exposes `PortableVaultPresentation`, `PortableVaultActions`
  and `PortableSurfaceActions` to Swift, routing dialog keys directly to the shared `:ui-foundation`
  composables while reserving `PortableSurfaceDialog` for host navigation/edit menus. One
  main-thread composition survives frame replacement; closing the host revokes every callback.
- `AppDelegate.swift` installs `AppRootViewController`, which switches between
  `UnlockComposeViewController` and `VaultComposeViewController`, supplies native document
  pickers/camera/lifecycle adapters and owns the scene-privacy cover. Every action is re-checked
  against the live `VaultStore` session, entry index/fields and generation before touching data.
- The previous SwiftUI list, editor, credential/email sheets, backup, migration, LinSCP, QR,
  settings, unlock and every `UIViewControllerRepresentable` wrapper were deleted. No SwiftUI
  source is compiled into the app and no `excludes:` list is needed in `project.yml`.

Validation here is source-level: the contract is compiled with no toolkit classpath, the shared
Compose declarations compile against the sandbox stubs, and the frame/privacy rules have host-JVM
tests. Kotlin/Native framework linking, Xcode compilation, simulator/device interaction,
accessibility and visual comparison still need a Mac and are **not** established by this change.

## This increment

Removed the separate Android LinSCP `AlertDialog`/`EditText` read prompt. Both its encrypted-vault
password and Base64 session-master-key input now use the desktop `LinScpImportDialog`. The form has
host-supplied secret-label/key-picker capabilities instead of a copied Android layout. Desktop keeps
its sessions.key file picker; Android keeps its existing text-key entry. Read and selection stages
share the same dialog implementation and clear secret drafts on submit/cancel.

The host owns the byte buffer until successful decoding, cancellation, replacement or lock. A
failed read keeps only source bytes for explicit retry, not the entered password. Successful
transition to selection revokes and disposes the read request without dismissing the new request.
Tests prevent reintroducing the native Android prompt and exercise callback/cleanup behavior.

## Android shared dialog hosts

`ui/src/androidMain/.../host/ComposeDialogViews.kt` provides Android hosts (`ComposeDialogHost`
subclasses) for shared forms rendered from `:ui-foundation` and `:ui`:

- `BiometricEnableComposeView` (`BiometricEnableDialog`)
- `DeleteConfirmationComposeView` (`EntryConfirmationDialog`)
- `PinEntryComposeView` (`SharedEntryPinDialog`)
- `CounterEntryComposeView` (`EntryCounterDialog`)
- `ThumbnailPickerComposeView` (`ThumbnailPickerDialog` + `VaultArtwork`)
- `TagsComposeView` (`TagsDialog`)
- `HideableNoticeComposeView` (`HideableDialog`)
- `EntryCopyComposeView` (`EntryCopyDialog`)
- `EmailDetailsComposeView` (`EmailDetailsDialog`)
- `EntryDetailsComposeView` (`EntryDetailsDialog`)
- `EmailImportPasswordComposeView` (`EmailImportDialog`)
- `MultiChoiceSelectionComposeView` (`MultiChoiceSelectionDialog`)
- `QrCodeComposeView` (`QrCodeDialog`)
- `CheckboxConfirmationComposeView` (`CheckboxConfirmationDialog`)
- `EncryptedRestorePasswordComposeView` (`EncryptedRestoreDialog`)

Each extends `AbstractComposeView`, wraps the shared form in `ForestTheme`, and uses
`DisposeOnDetachedFromWindow`. They attach an invisible composition to the Activity decor rather
than nesting another Android window: the shared Material 3 `AlertDialog` already owns its platform
window. `show()`/`dismiss()` are the only native lifecycle commands. The app keeps only capability
adapters: `SettingsActivity` verifies the password and calls `BiometricKeyStore.save`;
`EntryActionDialogs`, `AndroidVaultController` and `MainActivity` track active hosts for lock
dismissal, wipe staged import buffers on close/lock, and re-resolve live entries before mutating
the vault. No password, key or entry is retained in the Compose views.

The native `AlertDialog.Builder` / `EditText` implementations are retired from `EntryActionDialogs`
(counter, PIN, delete, thumbnail), `AndroidVaultController` (QR code display, SSH field copy,
FairEmail details, password details, tag picker, and password entry editing), `MainActivity`
(auto-time warning, password import mode chooser, FairEmail encrypted backup password prompt,
FairEmail/Thunderbird multi-choice account export, password archive/CSV export confirmations,
used-tokens notice, and vault-unavailable notice), `SettingsActivity` (preferences export mode,
encryption-change warning, login-backup old-password removal, and keystore reset confirmation),
and `BackupActivity` (OpenPGP key-file passphrase mode chooser). The main unlock Activity,
onboarding wizard, and About/library screens still retain their native autofill/fragment adapters
while `:ui-foundation` carries the shared Compose forms (`SharedUnlockScreen`,
`SharedIntroScreen`, `AboutDialog`).

## Next work in dependency order

1. Move the remaining Android ordinary import/backup/settings dialogs (including the main unlock
   Activity's autofill-aware prompt) onto shared forms, keeping each native capability adapter.
2. Extend the portable DTO boundary so the richer `:ui` vault renderer can replace the simplified
   `:ui-foundation` renderer used by iOS without importing JVM `Entry` types into native targets.
3. Expand shared navigation/settings once each migrated surface is the release path, not an unused
   alternative. Keep capability-driven adapters for OS-specific features.

Each migrated screen must have host wiring, failure/cancellation/session tests, and deletion or
explicit retirement of its old implementation. Do not claim all-build UI sharing until native
iOS compilation and device behavior have passed; Android/JVM stubs cannot prove that boundary.

## First native-compatible extraction

`:ui-foundation` now owns the existing Compose theme and unlock screen, moved rather than copied.
It has Android/JVM and iOS device/simulator targets and **no engine or model project dependency**.
`:ui` exports it transitively; the existing JVM unlock API is now a small wording adapter. Desktop
continues rendering the same screen. Android's shared Compose theme now also comes from this
module; its native authentication Activity has not yet been replaced.

The iOS source set exports `UnlockPresentation.viewController` using `ComposeUIViewController`.
Its main-thread adapter accepts busy/error updates and revokes submissions on close. It does not
implement authentication, biometrics, vault creation, foreground/background privacy covering or
native file access. Those remain host responsibilities.

The authentication integration now requires that framework in `ios/project.yml`. Build it on a
Mac with `scripts/ios/build-compose-framework.sh` before XcodeGen. `ComposeUnlockHost.swift` hosts
the shared `UnlockScreen` through a UIKit adapter, including create/confirmation and biometric
actions. `AppRootViewController`'s inactive-scene cover and VaultStore's key/biometric logic remain
in place. The whole release UI now uses shared Compose; no SwiftUI screen remains. No native
framework/Xcode build was run in the Linux sandbox, so this wiring is not yet native-validated. Gate it on password unlock, first-run creation, biometric access,
lock/close during authentication, rotation, keyboard, VoiceOver and privacy snapshots.

The sandbox independently compiles foundation common sources **without core/model classes** before
compiling the JVM UI hosts, and source tests reject engine/Java imports in this module. That checks
the new dependency boundary, not Kotlin/Native ABI correctness or actual Compose rendering.

## About migration

The existing desktop `AboutDialog`, `AboutContent` and `AboutNavigation` now live in
`:ui-foundation`, with their generated `AboutInfo` catalogue. Desktop uses the same declarations
through `:ui`'s transitive API; Android's native About/library integration still reads the common
catalogue. iOS Settings opens the shared About surface through `VaultCoordinator`. The SwiftUI content list
was removed; only the generated Swift link-ID catalogue remains, rather than a fallback view. Version lookup,
explicit external-link opening and sheet dismissal are native adapters. Cryptography/network
information previously shown only in iOS is retained in the common layout.

This requires rebuilding the Compose XCFramework before XcodeGen/Xcode. Native linking, VoiceOver,
sheet dismissal and external-link behavior still need Mac/simulator/device validation. The
settings and vault screens are now shared Compose surfaces, with no SwiftUI fallback.

## Auto-lock settings migration

The iOS timeout picker now opens the exact `AutoLockDialog` used by desktop, moved into
`:ui-foundation`. Its common action/scroll components moved with it and remain used by existing
JVM forms. The Swift preset/label implementation was removed. `:ui-contract` is the new pure
Kotlin multiplatform dependency for presentation rules: it has neither Compose nor engine
project dependencies and holds the single `AutoLockOptions` definition. Both `:ui-model` and
`:ui-foundation` consume it, avoiding an inverted dependency on JVM code or Compose in the model.

The iOS adapter accepts saves only while its originating session is unlocked/current and its
expected setting value remains unchanged. Cancel/close revokes callbacks. iOS now stages selection
until Save, matching desktop; opening or cancelling does not mutate the timeout. Custom values
remain visible. Persistence uses the existing VaultStore setter and its existing storage semantics;
this migration does not claim new disk-failure detection in that setter.

The Swift duplicate-rule tests are retired with the duplicate implementation; the existing golden
JVM contract still tests the single shared Kotlin rule, and native wiring is pinned by source gates.
The independent contract compilation deliberately has no Compose or engine classpath. Native
framework linking, simulator behavior, settings dismissal on lock and accessibility still need
Xcode/device validation. Android's native numeric setting is not migrated in this increment.

## Vault password change migration

The iOS three-password SwiftUI form was removed in favor of the existing desktop Compose
`PasswordChangeDialog`, now in `:ui-foundation`. Field bounds are supplied by each engine's
InputPolicy rather than importing a JVM utility into the native-compatible module. The shared
form scrolls, masks all fields and clears drafts before submission/dismissal; new and current
passwords must differ. The native adapter rechecks field bounds, confirmation and session identity.

Rekey semantics have **not** been silently unified: desktop locks before starting its asynchronous
transaction and blocks normal close; iOS's existing synchronous `VaultStore.changePassword` keeps
the session open and updates biometric key material. The common form has explicit host behavior
wording rather than falsely promising desktop's lock/close guarantees on iOS. Native error and
success messages (including biometric recovery instructions) are preserved. This engine/lifecycle
difference remains separate parity work; UI sharing does not prove cryptographic transaction parity.

Swift app tests cover adapter rejection after close/session replacement, wrong-password retry and
entry preservation. They are added but not executed in this environment. Shared Compose/stub
compilation, Python wiring guards and Android sandbox checks do not replace Xcode validation.
Rebuild the required Compose XCFramework before native testing.

## Preferences import confirmation migration

The desktop `PreferencesImportDialog` moved into `:ui-foundation` and iOS now presents it after a
file pick instead of applying immediately. Platform wording is a host parameter (desktop default,
iOS override); the confirm button is disabled while a plan failed, which desktop never produced
but the shared form now guards for every host.

iOS computes the plan by applying the picked document into a throwaway in-memory settings port.
`PreferencesExport.apply` validates the whole document before writing, so the scratch run yields
identical applied/unknown/refused counts without touching real settings. Only the shared dialog's
Apply writes through `VaultStore.preferencesPort`, then reloads the inactivity delay so the
auto-lock sheet reflects the imported value. Foreign-platform and unreadable files surface their
existing specific messages in the dialog's failure state rather than a settings footnote.

Swift adapter tests cover plan-without-apply, confirm-applies, foreign-file refusal and stale
session rejection; they are added but not executed in this environment. Shared Compose/stub
compilation, Python wiring guards and Android sandbox checks do not replace Xcode validation.
Rebuild the required Compose XCFramework before native testing.

## Delete confirmation migration

The iOS SwiftUI "Remove entry?" alert was replaced with the desktop `EntryConfirmationDialog`,
now in `:ui-foundation`. The title follows the desktop ("Delete account?"); the message keeps the
entry name, matching Android and iOS's previous prompt. A failed removal now keeps the dialog open
with the error appended — the desktop failed-request behavior — instead of dismissing into a toast.

The native adapter revalidates the pending removal on confirm: same session generation, same index,
unchanged entry fields and still revealed. A stale or dismissed request reports an error and
removes nothing. Swift adapter tests cover successful removal, stale-session and closed-request
rejection; they are added but not executed in this environment. Shared Compose/stub compilation,
Python wiring guards and Android sandbox checks do not replace Xcode validation. Rebuild the
required Compose XCFramework before native testing.

## Biometric enrollment confirmation

iOS biometric-enable confirmation now uses the shared Compose password dialog; SwiftUI retains the
native settings toggle and availability/refusal copy. The host checks that the vault is still
unlocked and biometrics remain available before asking `VaultStore` to verify the password and
store the device key. Password entry is ephemeral in Compose and masked. This moves the interactive
confirmation form off SwiftUI while preserving iOS's native biometric/keychain adapter.

## CI packaging requirement

The iOS build now runs `:ui-foundation:assembleOfflineOTPPresentationReleaseXCFramework` before
XcodeGen and copies the XCFramework into `ios/Frameworks`. Xcode's binary dependency is required;
there is no missing-framework branch that substitutes SwiftUI. The macOS workflow explicitly checks
for `ANDROID_HOME` because root Gradle configuration also needs Android SDK configuration, and the
job budget was increased to account for the Kotlin/Native framework build. Local builds use
`scripts/ios/build-compose-framework.sh` for the same required artifact.

## OTP QR scanning and image import

Android's ZXing intent now explicitly requests QR formats (not every barcode family), disables
beeps, and routes the returned payload through the existing shared OTP/migration import decision.
iOS adds an on-device VisionKit QR-only camera reader alongside its existing Vision image import;
both send the payload into the same vault import/parser path, including duplicate and Google
Authenticator migration handling. iOS now declares a camera purpose string, and the image picker
continues to work without camera access. Desktop Compose already has a file-image picker backed by
ZXing, shared across Windows, Linux (including Debian), and macOS; it decodes locally and applies
the same URI/migration confirmation flow.

## First-run desktop full restore

When the desktop client has no vault in the selected folder, the shared Compose unlock screen offers
**Restore from encrypted backup** beside **Create vault**. The restore dialog asks for the backup
file/password and a new password for the destination vault. It decodes the complete backup before
creating anything, then provisions the new vault in one encrypted write with all decoded entries;
there is no merge/replace prompt and no empty vault is created on decode failure. The backup
password is not reused as the destination password. This first-run path is available on desktop, iOS and Android. Desktop and iOS use the shared Compose
restore dialog directly. Android keeps its existing multi-slide auth setup: its final slide now asks
Create or Restore, decrypts the backup asynchronously, then initializes the new vault with all
entries in the same journaled write while preserving the selected Android auth/encryption mode. The
backup password is separate from the selected local authentication credentials. Native file-picker
adapters remain platform-specific.
