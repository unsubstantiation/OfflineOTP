# Cross-build consolidation — 2026-11-07

Historical audit. For the current module graph, requested x86-64/Apple/mobile target matrix,
architecture boundaries and chunk-by-chunk hand-off, see the
[shared-code migration plan](shared-code-migration.md) (2026-11-20). Older implementation and
validation descriptions below are dated evidence, not the current platform support matrix.

Scope: Android, Windows, macOS, Debian/Linux and iOS. This is an incremental audit,
not a declaration of identical clients or native/device validation.

## Architecture and remaining differences

| Area | Current implementation | Next consolidation boundary |
| --- | --- | --- |
| Vault cards and entry forms | Android and all desktop distributions use shared `:ui` Compose cards/models; iOS now uses the native-compatible `:ui-foundation`/`:ui-contract` Compose surface with Swift adapters | Compare native accessibility, keyboard and visual behavior on device; keep the golden format fixtures. |
| Password Edit | Android now uses the desktop `PasswordEntryDialog` and `PasswordEntryInput`, instead of a separate native dialog | Expand editor scope deliberately for non-common fields; do not silently overwrite alias columns. |
| OTP, Notes, Keys, Email editing | Android shared host/controller routes Edit to shared manual/email forms; Keys inline Edit routing fixed | Audit remaining detail/menu actions and metadata coverage; this change does not claim every field has an editor. |
| Theme | `shared/theme/forest.json` generates Compose, SwiftUI and Android resource bindings | Typography, spacing, focus order and responsive layout still require native comparisons. |
| Navigation | Desktop shared Compose controls; Android retains Activity toolbar/navigation (`showControls = false`); iOS native navigation | Move ordinary Android controls into shared components incrementally, retaining native back/menu behavior. |
| Import/export | Shared JVM codecs/policies; Swift counterparts with golden corpus; Android native chooser/dialog wiring, desktop Compose transfer dialogs, iOS sheets | LinSCP export selection/password/warning now uses the shared Android/desktop form; import/master-key prompts remain a separate migration. |
| Preferences/unlock/backup | Native Android activities, desktop Compose surfaces, iOS SwiftUI | Inventory semantics and defaults before replacing surfaces; capability-dependent controls must remain platform-specific. |
| About | Shared content exists; Android native Activity/library integration, Compose desktop, iOS native view | Share presentation where practical without losing library notices. |
| Platform services | Android SAF, camera, hardware keys and lifecycle; desktop files/hardware integration; iOS Keychain/biometrics, document pickers | Keep native adapters. Similar appearance is not a reason to remove capabilities. |

## This increment

- Removed Android's redundant password editor and unused native email-creation dialog.
- Password form now scrolls, supports Show/Hide, uses the browser field limit (65,536 UTF-16
  code units), never preloads the stored password, and retains blank-as-preserve behavior.
- Android password callbacks require editor ownership and a current entry/session; persistence
  failure must not mutate the original. Key Edit now reaches the shared manual editor.
- JVM and Swift password replacement preserve the full stored entry metadata rather than
  rebuilding a partial record. JVM runtime tint is preserved separately from JSON.
- iOS password saves check the opening field snapshot, session generation, reveal state and
  common-form eligibility; menus use the same eligibility policy.
- `desktop/src/test/resources/golden/password-edit-contract.json` is consumed by JVM and Swift
  tests. Ineligible alias-only records may be rejected earlier by JVM decoding; neither client
  may offer the common editor for those records.
- Theme values and Android resource names are unchanged. Edit `shared/theme/forest.json`, run
  `python3 scripts/generate-theme.py`, and check in all generated bindings. The Python gate runs
  `--check`; builds need no generator runtime or network. Android night-mode library overrides
  remain aliases to generated resources.

## Gates and outstanding validation

Sandbox core: 346 passed. Desktop: 394 passed, 2 skipped. Shared UI-model: 141 passed.
Headless AWT: 3 passed. Android sandbox checks passed, including shared Key/Password opening,
failed persistence, dismissed callbacks and lock cleanup. Android instrumentation sources:
28 files compile against stubs. Python source/tooling tests: 560 passed.

These are **not** native Gradle builds, Android instrumentation execution, Swift/Xcode compilation,
Swift test execution, or device/simulator visual validation. Run those release gates before
claiming platform parity. Swift test additions are checked in but not executed in this environment.

Next increments should separately gate/commit transfer dialogs, ordinary navigation controls,
then preference semantics. Do not attempt a wholesale rewrite or remove native integrations.

## Follow-up: LinSCP export form

Android now hosts the same `LinScpExportDialog` as desktop, replacing native selection and
password dialogs. Default-empty selection, default-off plain text, warning text and password
confirmation therefore come from one component. Cancel clears both password drafts.
Android retains ACTION_CREATE_DOCUMENT, session-guarded storage writes and pending-byte cleanup.
The Activity validates selection bounds, matching nonempty encrypted-export passwords, request
ownership, session generation and selected-entry membership independently of button enablement.
Failed preparation stays in the form with an error; native chooser launch ends the form.
Import and its Android master-key input are unchanged. iOS continues using its native transfer
sheet and existing equivalent default-off plain-text policy.

Follow-up sandbox coverage exercises invalid/empty selections, mismatched/blank passwords,
replaced and dismissed forms, explicit plain export roundtrip, removed entries and locked sessions.
Shared UI-model (141) and headless AWT (3) tests pass; Python tooling/source tests (560) pass.
Native Gradle/device/Xcode gates remain outstanding.

## OpenPGP engine selection (revised)

Both Android paths are supported: the bundled engine with user-selected key files, and an
external OpenPGP provider. New setups with no configured provider default to the in-app engine.
Explicit stored engine choices are honored; older installations with a configured provider and
no engine choice keep that provider. The XML preference intentionally has no fixed default so
opening Settings cannot overwrite this compatibility rule. In Settings, the engine selector
shows the effective choice and enables the corresponding controls.

External signing/verification controls and callbacks are restored. The provider warning now
explains the in-app alternative. Missing key files no longer incorrectly report a missing native
backend. There is no automatic fallback between engines: users select their engine and keys.
The native backend and real provider interoperability still require device testing.

The [OpenPGP client parity matrix](openpgp-client-parity.md) records the in-app baseline across
Android, Windows, macOS, Debian/Linux and iOS, optional platform integrations, packaging caveats,
and the distinction between an embedded desktop fallback library and an external program.

See the [cross-build feature/UI audit](cross-build-feature-audit.md) for the broader prioritized
feature matrix and acceptance criteria. Full functional or visual parity has not been established.

## Follow-up: LinSCP import selection

Android now uses the same `LinScpImportDialog` selection/merge-confirmation stage as desktop.
Selection starts empty; warnings and action labels are shared. Native Android file selection,
encrypted-vault password prompt and session master-key input are retained for the read stage.
This is not yet a complete unification of the read/decrypt UI or iOS presentation.

The Compose host owns staged-entry cleanup. Replacing a form, dismissing, locking or terminating
the host releases staged entries and revokes callbacks. Submission validates request identity,
Activity host/controller identity, session generation/key and selection bounds. The installer
receives owned copies, so failed persistence leaves the staged originals available for retry;
successful UI confirmation disposes the originals. Shared errors now distinguish reading a file
from saving its selected entries, rather than asking users to re-enter an already-used password.

Sandbox coverage exercises invalid selection, persistence failure/retry staging, replaced/dismissed
requests, lock and late imports. Native Gradle/device lifecycle and Xcode tests remain outstanding.

## Follow-up: auto-lock settings and accurate preference coverage

The prior audit understated iOS preference support: its existing catalog already includes sort
order as well as inactivity delay. Corrected the audit, help text and store/codec comments; no
preference format or cross-platform import policy was changed.

The iOS timeout picker now includes the current stored value even when it is not a preset (for
example a 45-second imported timeout). Desktop already retained such values. Both use equivalent
labels and preset lists, pinned by one JVM/Swift golden contract. Kotlin presentation and desktop
controller now share the preset definition; the desktop dialog is scrollable. No timeout is rounded,
no default changes, and opening the picker does not write preferences. Android retains its numeric
seconds setting and enable switch in this increment; unified settings presentation remains open.

JVM contract and source gates run in the sandbox. Swift contract/roundtrip tests are added but require
Xcode/Swift execution outside this sandbox; native picker accessibility/visual validation is pending.

## Shared UI ownership follow-up

Android's LinSCP read/decrypt prompt now uses the same shared form as desktop, including the
existing Android text master-key input. The native AlertDialog/EditText copy was removed. See
[shared UI ownership](shared-ui-ownership.md) for the actual production boundaries and the
JVM dependency work needed before iOS can consume the same Compose screens.

## Follow-up: cross-platform `:ui-foundation` transfer/settings/action dialog consolidation

Relocated the engine-free transfer and chooser dialogs (`EntryCopyDialog`, `EncryptedExportDialog`,
`EncryptedRestoreDialog`, `OpenPgpExportDialog`, `OpenPgpImportDialog`, `EmailImportDialog`,
`LinScpExportDialog`, `LinScpImportDialog`, and `UriImportDialog`) from `:ui/src/jvmHostMain` into
native-compatible `:ui-foundation/src/commonMain`, backed by `PortableInputBounds` in `:ui-contract`
(pinned to `InputPolicy` in `PortablePresentationTest`).

`PortableVaultViewController` on iOS now renders those shared `:ui-foundation` composables directly
(alongside `AboutDialog`, `AutoLockDialog`, `PasswordChangeDialog`, `BiometricEnableDialog`,
`PreferencesImportDialog`, `EntryConfirmationDialog`, `SharedEntryPinDialog`, `EntryCounterDialog`,
`TagsDialog`, and `MultiChoiceSelectionDialog`), adding confirmed encrypted-backup passwords,
Merge/Replace restore confirmation for encrypted and OpenPGP backups, multi-recipient OpenPGP
export, selective FairEmail/Thunderbird account export, interactive tag checklists, and QR-image
file import through `QrImageReader`. On Android, `IntroScreenActivity` first-run backup password
entry and `HardwareKeyActivity` confirmation/account-selection prompts now also render through
`ComposeDialogHost` views.

## Follow-up: shared credential editors, email details, QR matrix, and archive/storage dialogs

Promoted `ArchiveExportDialog`, `PasswordExportDialog`, `StorageLocationDialog`, and
`EmailChoiceField` into `:ui-foundation/src/commonMain`, and extracted native-compatible shared
implementations (`SharedPasswordEntryDialog`, `SharedEmailDetailsDialog`,
`SharedEmailAccountDialog`, and `SharedManualEntryDialog`, backed by `PortableDetailRow`,
`PortablePasswordEditDraft`, `PortableEmailAccountDraft`, and `PortableEntryDraft` in
`:ui-contract` with `PortableInputBounds` constants pinned to `BrowserPasswordCsv` and
`EmailMaterial`). `:ui` adapters (`PasswordEntryDialog`, `EmailDetailsDialog`, `EmailEntryDialog`)
and iOS (`PortableVaultViewController` + `ComposeVaultHost.swift`) now render the same Compose
implementations, while `QrCodeDialog` gains a pure-Compose `Canvas` matrix renderer fed by
`CIQRCodeGenerator` on iOS alongside `ArchiveExportDialog` (`VaultStore.exportPasswordArchive`)
and `StorageLocationDialog` in iOS Settings/Backups.

## Follow-up: shared vault chrome, hardware dialogs, password import, card layout, and detail sheets

Extracted `SharedVaultComponents` (`SharedVaultSearchField`, `SharedVaultTab`,
`SharedVaultTagChip`, `CountdownRing`, and `VaultLetterTile`) into `:ui-foundation/src/commonMain`
so both `:ui`'s `VaultComponents` (Android + Desktop) and `:ui-foundation`'s `PortableVaultScreen`
(iOS) render identical category tabs, search fields, tag pills, letter tiles, and circular OTP
countdown arcs. Relocated `HardwareEnrollmentDialog`, `HardwareUnlockDialog`,
`HardwareCopyDialog`, and `HardwareOathDialog` into `:ui-foundation/src/commonMain`, extracted
`SharedPasswordImportDialog` and `SharedCardLayoutDialog` (backed by `PortablePasswordImportMode`
and `PortableCardLayout` in `:ui-contract`), routed iOS `"passwordImportChoice"` (with two-step
entry count preview), `"cardLayout"`, `"sort"`, `"more:<index>"`, and non-email `"details:<index>"`
onto shared `:ui-foundation` dialogs, and migrated Android's `HardwareUnlockActivity` wait prompt
onto `EntryDetailsComposeView`.

## Follow-up: shared category/tag strips, iOS `Backups and links` tools dialog, and Android backup/import/settings Compose hosts

Extracted `SharedVaultCategoryBar` and `SharedVaultTagBar` into `SharedVaultComponents`
(`:ui-foundation/src/commonMain`) so Android, Desktop (`VaultComponents`), and iOS
(`PortableVaultScreen`) share the horizontal category-tab and tag-filter strips alongside the
shared scaffold, top bar, search field, sort bar, empty state, and entry cards.

Routed iOS `"backups"` (`Backups and links`) onto `SharedVaultToolsDialog` alongside `"settings"`,
removed the legacy unconfirmed password row from `backupSurface()` so encrypted exports always use
`EncryptedExportDialog` with password confirmation and minimum-length enforcement, and wired an
optional `"Import otpauth:// link"` switch on `SharedManualEntryDialog` into `UriImportDialog` across
Android (`UriImportComposeView` + `MainActivity`), Desktop (`ComposeMain`), and iOS
(`PortableVaultViewController` + `ComposeVaultHost`).

On Android, replaced all three `BackupActivity` password prompts (`doBackupCrypt`, `doRestoreCrypt`,
`promptForKeyFilePassphrase`) with `ComposeDialogHost` views (`EncryptedExportComposeView`,
`EncryptedRestorePasswordComposeView`, `BiometricEnableComposeView`), moved `MainActivity` URI and
duplicate-issuer import confirmations onto `DeleteConfirmationComposeView`, and migrated the
remaining `SettingsActivity` `UIHelper.showGenericDialog` notices onto `EntryDetailsComposeView`.



