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
  `PortableSurfaceState`, `PortableDetailRow`, `PortablePasswordEditDraft`,
  `PortableEmailAccountDraft`, `PortableEmailField`, `PortableEmailEditDraft`,
  `PortableEmailPasswordDraft`, `PortableEmailConnectionDraft`, `PortableEmailIdentityDraft`,
  `PortableEmailSettingsDraft`, `PortableEntryDraft`, `PortableEntryDragBounds`,
  `portableEntryDragDirection`, `PortablePasswordImportMode`, `PortableCardLayout`,
  `PortableLabelDisplay`, `PortableTapMode`, `PortableCardDisplaySelection`, `PortableInputBounds`,
  `PortableThumbnailAssets`, `PortableVectorDocument`, `PortableToolItem`, `PortableToolGroup`,
  `PortableEntryDialogKind`, `PortableLibraryNotice`, `PortableLibraryCatalog`, and `AutoLockOptions`:
  strings, numbers, booleans, and toolkit-free vector geometry only, with no engine, toolkit or host type.
- `:ui-foundation` owns `PortableVaultScreen`, `PortableSurfaceDialog`, `SharedVaultScreen`,
  `SharedVaultToolsBar`, `SharedVaultToolsDialog`, `SharedVaultEntryDialog`, `VaultArtwork` (`SharedIssuerArtwork`, `SharedCatalogArtworkIcon`),
  `SharedVectorArtwork` (`BundledVectorVaultArtwork`, `SharedVectorBadge`, `SharedVectorCanvas`),
  `SharedVaultComponents`
  (`SharedVaultFilterHeader`, `SharedVaultCategoryBar`, `SharedVaultTagBar`, `SharedVaultSearchField`,
  `SharedVaultTab`, `SharedVaultTagChip`, `SharedVaultGlobalProgressBar`, `CountdownRing`, and `VaultLetterTile`), and the engine-free shared Compose
  dialogs/forms consumed across Android, Desktop, and iOS (`AboutDialog`, `AutoLockDialog`,
  `PasswordChangeDialog`, `BiometricEnableDialog`, `PreferencesImportDialog`, `EntryConfirmationDialog`,
  `CheckboxConfirmationDialog`, `SharedEntryPinDialog`, `SharedVaultEntryDialog`, `EntryCounterDialog`, `EntryDetailsDialog`,
  `TagsDialog`, `HideableDialog`, `MultiChoiceSelectionDialog`, `QrCodeDialog`, `EntryCopyDialog`,
  `EncryptedExportDialog`, `EncryptedRestoreDialog`, `OpenPgpExportDialog`, `OpenPgpImportDialog`,
  `EmailImportDialog`, `LinScpExportDialog`, `LinScpImportDialog`, `UriImportDialog`,
  `ArchiveExportDialog`, `PasswordExportDialog`, `StorageLocationDialog`, `EmailChoiceField`,
  `SharedPasswordEntryDialog`, `SharedPasswordImportDialog`, `SharedCardLayoutDialog`,
  `SharedThumbnailPickerDialog`, `SharedEmailDetailsDialog`, `SharedEmailAccountDialog`,
  `SharedEmailEditDialog`, `SharedEmailPasswordDialog`, `SharedEmailSettingsDialog`,
  `SharedManualEntryDialog`, `HardwareEnrollmentDialog`, `HardwareUnlockDialog`,
  `HardwareCopyDialog`, `HardwareOathDialog`, and `BusyProgressDialog`).
- `PortableVaultViewController` (`iosMain`) exposes `PortableVaultPresentation`, `PortableVaultActions`
  and `PortableSurfaceActions` to Swift, routing every reachable dialog key directly to the shared
  `:ui-foundation` composables (`SharedVaultToolsDialog` for both `Settings` and `Backups and links`;
  `SharedCardLayoutDialog` for card density, token grouping, `Hide issuer`, and
  `Show previous code`; `MultiChoiceSelectionDialog` for selective FairEmail/Thunderbird exports and
  configurable `Search fields`; `PasswordExportDialog`, `ArchiveExportDialog`, `SharedManualEntryDialog`,
  `SharedEmailAccountDialog`, `SharedThumbnailPickerDialog`, `UriImportDialog`, etc.) while keeping
  `PortableSurfaceDialog` only as a defensive fallback. One
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
- `EncryptedExportComposeView` (`EncryptedExportDialog`)
- `UriImportComposeView` (`UriImportDialog`)
- `PasswordImportComposeView` (`PasswordImportDialog` -> `SharedPasswordImportDialog`)
- `PasswordExportComposeView` (`PasswordExportDialog`)
- `ArchiveExportComposeView` (`ArchiveExportDialog`)
- `HardwareCopyComposeView` (`HardwareCopyDialog`)
- `OpenPgpExportComposeView` (`OpenPgpExportDialog`)
- `StorageLocationComposeView` (`StorageLocationDialog`)
- `PreferencesImportComposeView` (`PreferencesImportDialog`)
- `BusyProgressComposeView` (`BusyProgressDialog`)
- `HardwareEnrollmentComposeView` (`HardwareEnrollmentDialog`)
- `CardDisplayComposeView` (`CardLayoutDialog` -> `SharedCardLayoutDialog`)
- `AutoLockComposeView` (`AutoLockDialog` + `AutoLockOptions.seconds`)

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
`otpauth://` URI import and duplicate-issuer confirmations, `UriImportDialog` link/QR-image/QR-camera
import with staged multi-part migration collector preservation across camera and image handoffs,
used-tokens notice, and vault-unavailable notice), `SettingsActivity` (preferences export mode,
preferences import confirmation with dry-run setting counts, private vault `Storage location`
summary, encryption-change warning, login-backup old-password removal, keystore reset confirmation,
and sync/encryption notices, plus `BusyProgressComposeView` for background encryption changes so no
`android.app.AlertDialog` remains in `SettingsActivity`), `HardwareKeyActivity`
(`HardwareCopyDialog` seed-export selection and confirmation with touch/OATH-password options,
`HardwareEnrollmentComposeView` FIDO2 enrollment confirmation with dedicated optional FIDO PIN
input, `DeleteConfirmationComposeView` FIDO2 removal confirmation, and `BusyProgressComposeView`
security-key connection progress so no `android.app.AlertDialog` remains in
`HardwareKeyActivity`), `HardwareUnlockActivity` (`BusyProgressComposeView` cancelable security-key
connection progress), and `BackupActivity` (encrypted backup export
password, encrypted backup restore password, `OpenPgpExportDialog` multi-recipient confirmation,
OpenPGP key-file passphrase mode chooser, and OpenPGP key-file passphrase prompt). The main unlock
Activity, onboarding wizard, and About/library screens still retain their native autofill/fragment
adapters while `:ui-foundation` carries the shared Compose forms (`SharedUnlockScreen`,
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

## Multi-identity SMTP creation, OpenPGP 64-recipient bounds & persisted tag filters

- `SharedEmailSettingsDialog` (`:ui-foundation`) and `PortableEmailSettingsDraft` (`:ui-contract`)
  now own multi-identity SMTP creation (`Add SMTP identity` -> `PortableAddedEmailIdentityDraft`
  with masked password input, up to `PortableInputBounds.MAX_EMAIL_IDENTITIES = 16`), primary
  identity promotion (`Make SMTP #N primary` -> `primaryIdentityIndex`), and staged secondary
  removal across Android (`VaultComposeView` -> `EmailSettingsDialog`), Desktop (`ComposeMain` ->
  `EmailSettingsDialog`), and iOS (`PortableVaultViewController` + `ComposeVaultHost`). Both Kotlin
  and Swift `EmailSettingsInput.build` validate credentials/ports/addresses, scrub added passwords
  on UI submit (`withoutSecrets()`), rewrite `"primary"` flags when reassigned, and place the
  chosen primary at `identities[0]`.
- `OpenPgpExportDialog` (`:ui-foundation`) defaults `maxRecipients` to
  `PortableInputBounds.MAX_OPENPGP_RECIPIENTS` (`64`), matching `OpenPgpKeyFilePolicy.MAX_RECIPIENTS`
  (`:core`), `DesktopOpenPgpExport.MAX_RECIPIENT_FILES`, iOS `OpenPgpBackupCodec.maxRecipients`, and
  the Rust `rpgp` backend, while `OpenPgpBackupCodec.seal` (Kotlin & Swift), Android
  `OpenPgpKeyFileBackupTask`, Desktop `DesktopOpenPgpExport`, and iOS `ComposeVaultHost` all enforce
  the `16 MiB` aggregate recipient-key byte budget (`BoundedInputReader.MAX_DOCUMENT_BYTES`).
- `SharedCardLayoutDialog` (`:ui-foundation`) exposes `minimizeOnCopy` when supplied by the Desktop
  host (`ui_minimize_on_copy` iconifying `java.awt.Frame` after copying), and both Desktop and iOS
  persist reconciled tag selections (`ui_selected_tags`, `ui_untagged_selected`) across sessions and
  portable preference exports.
- `SharedPasswordEntryDialog`, `SharedManualEntryDialog`, and `SharedEmailAccountDialog`
  (`:ui-foundation`) form one shared multi-type creation and editing suite across Android, Desktop,
  and iOS: `SharedPasswordEntryDialog(editing = false)` creates canonical Chrome-dialect website
  `PASSWORD` entries (`PasswordEntryInput.buildNew` / `PasswordCsvExport.create`), all three creation
  dialogs scrub typed credentials from Compose state on submit, cancel, or mode switch, and
  `SharedEmailAccountDialog` shares custom `ThumbnailCatalog` artwork selection with OTP, Note, SSH
  key, and Password creation.

## Android vault tools convergence

Android's `action_tools` shortcut now mounts `VaultToolsComposeView`, a lifecycle-only bridge to
`SharedVaultToolsDialog`. Android and Desktop both project the capability-filtered `VaultTool`
catalog through `vaultToolGroups`; iOS continues to supply portable groups to the same foundation
dialog. Tools close before dispatching native pickers or editors. Android binds callbacks to the
opening vault generation and dismisses tools and storage-location prompts on lock/destruction.
Existing Android menu shortcuts remain available during migration.

The Android shared tools surface includes URI/QR, password, email, Thunderbird and LinScp imports;
archive, CSV, email, Thunderbird and LinScp exports; storage location, card display, search fields,
auto-lock, password change, backup filename timestamps and About. Archive selection is independent
of CSV availability, so mixed revealed browser-password/archive selections no longer hide archive
export. Selection revalidates the session and live reveal state before using the existing export path.
Encrypted backup/restore, OpenPGP backup/restore and preferences transfer are also available from
shared Tools. Their Android Backup/Settings hosts accept allowlisted fresh-launch action identifiers
bound to the opening session generation. Recreation does not repeat these shortcuts; existing
picker save/resume and locked-result handling remain authoritative. No vault key or password travels
in these handoffs. OpenPGP still uses the user's configured key-file/provider mode and retains its
normal setup/error handling. Hardware setup retains its existing Android entry point. The underlying
Backup/Settings screens are not yet fully unified.

Shared code-area rendering also retains HOTP counter labels beside revealed codes. Email adapters
forward host failure text to foundation forms, and Desktop uses the shared vault notice banner.
Sandbox gates type-check these seams but do not execute Compose layout or native picker behavior.

## Shared preferences-export disclosure

`PreferencesExportDialog` in `:ui-foundation` now owns the export confirmation on Android,
Desktop and iOS. The common form explains the unencrypted, platform-specific settings export and
exclusion of vault entries/authentication material. Android's `PreferencesExportComposeView`
opts into an unchecked saved-backup-password checkbox, preserving its existing optional-secret
codec policy. Desktop and iOS do not expose that option and continue exporting non-secret settings
only. No password or serialised settings bytes enter Compose state.

All hosts confirm before opening their destination picker. Android rejects replaced prompts and
stale vault generations before calling its codec; Desktop checks its live session before and after
the picker; iOS routes `prefsExportConfirm` through the existing surface-epoch check. Native settings
ports, file picking, error reporting and payload cleanup remain platform-owned. Sandbox compilation
covers the Kotlin/iOS bridge, not Swift/UIKit rendering or a native device run.

## Shared restore mode selection

`RestoreModeSelection` owns merge/replace actions and the destructive replacement disclosure for
both encrypted and OpenPGP restore forms on Desktop and iOS. Android's Backup activity now uses
`RestoreModeDialog` through `RestoreModeComposeView` before opening either restore picker, including
shared Tools shortcuts. Merge is the default for each new prompt; replace requires explicit selection.
The old native replace switch remains hidden as picker-resumption state, not a second visible control.

Android captures the choice before the picker and retains the existing decrypt/validate/commit pipeline.
Confirmations check prompt identity, session generation and activity liveness; lock and destruction
remove the prompt, and a consumed callback cannot launch a second picker. This does not add a decoded
entry-count preview to Android: its mode choice occurs before file selection, whereas the other hosts
embed the same selection controls in their decoded preview. Native backup overview/type controls and
hardware setup remain migration work.

## Compose backup overview

Android's Backup activity now mounts `BackupOverviewComposeView` / `SharedBackupScreen` as its visible
page. Format choices, selected-format label, description, provider/key-file warnings, enabled backup
and restore actions, and task progress are projected as non-secret portable tool groups. Both this
full-page overview and the Desktop/iOS tools dialogs use `SharedVaultToolGroups`, so enabled,
informational, detailed and grouped actions have one renderer.

Legacy backup XML IDs and hidden widgets remain compatibility/state hooks for the existing engine
and picker-resumption tests; they are no longer a second visible screen. Provider availability and
background task changes republish the shared frame, including early provider-error branches. The
activity rechecks live availability, session and pending-picker state before dispatch; the view also
rejects disabled, busy and closed frames. Lock clears its callbacks' usable state and shows only a
locked warning; destruction permanently closes the view. Native backup tasks, provider binding,
SAF permissions and decrypt/restore commit behavior are unchanged. Hardware setup and Android's
remaining native Settings preferences still require migration.

## Compose hardware-key management page

Android's visible hardware-key page is now `SharedHardwareKeysScreen`, mounted through
`HardwareKeysComposeView`. It uses the same `SharedVaultToolGroups` renderer as shared tools and
backup overviews, with masked, bounded OATH password input that is cleared on submission, device
busy transitions and stop/lock. The password array is consumed/wiped even for rejected callbacks;
FIDO enrollment and seed export retain their dedicated shared credential/confirmation dialogs.

USB/NFC discovery and FIDO/OATH clients stay Android-owned. Hidden legacy password/account view IDs
and account buttons remain compatibility hooks during migration: Compose account actions delegate to
those live handlers, retaining the tested clock-rollback, expiry, HOTP advance-confirmation and
uncertain-write rules. Account action keys include the list revision. Timer expiry republishes the
visible labels, and live session/busy/modal checks prevent stale actions. Stop clears both account
presentations and pending OATH input; resume publishes a fresh empty page; destruction closes it.
Android's remaining native Settings preferences and the separate hardware-unlock screen still need
migration. No native USB/NFC or Compose-rendering validation is implied by sandbox checks.

## Shared hardware-unlock PIN form

Android's separate hardware-unlock activity now mounts `HardwareUnlockComposeView` around the same
`HardwareUnlockDialog` used by Desktop. The common form accepts host transport instructions and
labels: Desktop keeps PC/SC guidance; Android explains its USB/NFC connection and FIDO PIN. The
legacy ScrollView and PIN/start IDs remain hidden compatibility hooks, not visible credential UI.

Each Android form is single-use. Submit/cancel/destruction revoke its callbacks and every submitted
PIN array is wiped, including rejected calls. The form is dismissed before the existing cancelable
hardware-progress prompt opens, so two credential/progress dialogs are not stacked. Cancellation or
failure returns to a new empty shared form; stop/lock/destroy revoke it. Completion must still own
the current foreground attempt before offering a derived key to the session. Envelope equality,
vault decryption verification, raw-key wiping and the no-key-material-in-Intent policy are unchanged.
Android's remaining native Settings preferences remain migration work. Real USB/NFC and native
Compose rendering still require device validation.

## Shared Settings overview with native-only fallback

Android Settings now opens a shared Compose grouped overview (`SettingsOverviewComposeView` ->
`SharedVaultToolsScreen`). That full-page surface is also used by the backup overview and shares its
action renderer with Desktop/iOS tools. Card display, search fields and auto-lock launch the same
shared Compose forms as the vault tools, persisting via typed Android settings adapters. Backup
folder/storage access and portable preferences transfer use their existing host adapters.

Authentication/encryption, biometric setup, autofill, theme/language, OpenPGP configuration, automatic
backups and other Android-only preferences remain available under the explicit **Authentication and
Android-specific settings** destination. The native PreferenceFragment remains in a separate hidden
container, made visible for that destination. It is recreated when entered so native controls reload
values saved by shared forms. Back/Up returns to the overview first; recreation preserves the chosen
destination. This is an incremental migration, not a claim that all native preferences are converted.

Overview dispatch and dialog saves check the current key/session generation, prompt identity and
busy state. Lock/destruction close the overview and prompts. Authorized encryption/key-store changes
rebind the overview generation; old dialog callbacks retain their original generation and cannot
write into the replacement session. Sandbox checks cover typed persistence, stale saves, task-busy
dispatch and advanced/back routing, but not native fragment/Compose rendering.

### Tap-action preferences

Android single/double-tap settings now use the common `SingleChoiceSelectionDialog`, with an
Android host providing localized action labels and a snapshot of the current action. Confirm is
single-use; invalid indices and dismissed hosts cannot dispatch. Dismiss leaves storage unchanged.
The typed `applyTapAction` adapter preserves native lowercase ListPreference values for all five
existing modes, including copy-to-background and keystroke sharing. Runtime action execution and
platform permissions are unchanged. Saves additionally require prompt ownership, a non-busy host,
and the same unlocked key/session generation. Native advanced controls remain compatible.
