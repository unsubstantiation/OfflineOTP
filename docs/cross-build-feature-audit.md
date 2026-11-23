# Cross-build feature and UI audit — 2026-11-07

## Conclusion

**The builds are not yet fully feature-equivalent or visually identical.** Windows, macOS and
Debian/Linux use the same Compose desktop host; Android shares cards and several forms but retains
Activity chrome and native workflows. iOS now renders the vault and its dialogs with shared Compose
through `ComposeVaultHost`, with native file/keychain adapters and SwiftUI screen sources excluded
from the Xcode target. Matching renderers and file formats alone does not establish UI or
functional parity; native accessibility and visual checks remain outstanding.

This is a source audit, not a visual/device test. Locations below identify the implementation
reviewed. Absence from a settings screen is not proof that no supporting engine code exists.

## Confirmed differences and work order

| Priority | Area | Evidence / difference | Completion criterion |
| --- | --- | --- | --- |
| P1 | Settings and portable preferences | iOS `SettingsView.swift` exposes biometric unlock, inactivity delay, password change, vault location, preferences and About. `VaultStore.PreferencesPort` carries sort order and inactivity delay (the earlier audit understated this). Desktop `PreferencesImportDialog` lists sort, layout and auto-lock. Android `res/xml/preferences.xml` exposes a broader set including automatic-backup credentials and retention. | Inventory each setting's semantics/defaults and expose applicable controls in equivalent groups; share portable setting definitions without exporting device credentials. |
| P1 | QR capture | Android `MainActivity.scanQRCode` supports camera capture and QR-image import. iOS `BackupView.imageSection` / `QrImageReader` use image files without camera access. Desktop host supports URI/image import and explicitly lists camera capture as missing. | Add native camera adapters where supported, with the same scan/review/error/cancel flow; keep image/URI alternatives. |
| P1 | LinSCP import | Android `MainActivity.importLinScpDocument` retains native password/master-key prompts. Its selection/confirmation now uses the same `LinScpImportDialog` as desktop; iOS uses `LinScpTransferViews`. Export is also shared on Android/desktop. | Share Android/desktop import presentation without dropping Android master-key handling, bounded reads, selection ownership or lock cleanup; align Swift behavior. |
| P1 | Backup workflow | Android `BackupActivity` and settings, desktop Compose import/export dialogs and iOS `BackupView` remain different surfaces. In-app OpenPGP exists on all; Android also has provider settings. | Common backup type/merge/replace/key-selection terminology and warning order, with native storage adapters. Track automatic-backup configuration separately; do not equate manual export with automatic backup. |
| P1 | Entry-edit completeness | Common password form deliberately edits URL/username/password only; it preserves additional columns and declines conflicting aliases. OTP/note/key and email editors have separate Swift implementations. | Field-by-field view/edit/support matrix and shared roundtrip fixtures, including metadata and failed-write behavior. Do not call preservation alone “fully editable.” |
| P2 | Navigation | Android `AndroidVaultController` publishes `showControls = false`, retaining Activity controls. Desktop uses shared Compose controls. iOS uses SwiftUI navigation/list/toolbars. | Same action names, ordering, categories and confirmation rules in responsive phone/window layouts; preserve native back/keyboard/accessibility behavior. |
| P2 | Dialog sizing | Five shared import dialogs used non-scrollable columns, unlike LinSCP import/export and SwiftUI Form-based restore. | Shared scrollable body and small-window/large-text device checks. Source fix included in this increment. |
| P2 | OpenPGP recipient limits | Desktop `DesktopOpenPgpExport.MAX_RECIPIENT_FILES` is 8; Android `OpenPgpKeyFilePolicy.MAX_RECIPIENTS` is 64. | Decide a common user-facing limit and aggregate byte budget, then test it on all clients. Do not simply raise limits without reviewing memory/crypto bounds. |
| P2 | About | Shared content is generated, but Android Activity/library notices, desktop dialog and iOS settings section differ. | Equivalent sections/version/links/notices with native link handling. |
| P2 | Hardware/quick unlock | Android hardware integration, desktop PC/SC FIDO PRF, and iOS Keychain/biometrics are different adapters/capabilities. Desktop explicitly lacks OpenPGP hardware/agent integration. | Consistent capability-driven controls and recovery wording. Add missing adapters where feasible; never remove working platform features merely to make the menus identical. |

## Already aligned—retain while consolidating

- Shared Compose desktop release host across Windows/macOS/Linux; shared Android/desktop vault
  cards and manual/email/password edit forms.
- Generated Dark Forest colors across Compose, Android XML and SwiftUI.
- In-app OpenPGP path on every client, with optional Android provider integration; see
  [OpenPGP parity](openpgp-client-parity.md) for availability and packaging caveats.
- Expiring clipboard implementations use platform-appropriate adapters; iOS uses pasteboard
  expiry rather than a background timer. Different implementation is not itself a missing feature.
- Native document pickers, biometric prompts and hardware transports should remain native.

## Changes in this increment

- **Desktop Compose feature parity**: Restored Thunderbird CSV credential import and selective
  plaintext CSV export (`DesktopThunderbirdImport`, `DesktopThunderbirdExport`, `VaultToolId`
  routing, and `MultiChoiceSelectionDialog` in `ComposeMain.kt`); wired per-entry account actions
  (`DesktopEntryActions`) using the shared `EntryCopyDialog`, `EntryDetailsDialog`, `QrCodeDialog`,
  `TagsDialog`, `ThumbnailPickerDialog`, and `EntryCounterDialog` forms; and added persisted
  tag-filter rule cycling (`Any tag` / `All tags` / `One tag`) in `VaultTagChips`,
  `VaultController`, and `PreferencesExport.desktopCatalog`.
- **iOS & portable manual-entry parity**: Extended `PortableEntryDraft` and
  `SharedManualEntryDialog` with TOTP/HOTP hash-algorithm selection (`SHA1`/`SHA256`/`SHA512`) and
  explicit secret-removal toggles (`clearHiddenLabel`, `clearPin`, `clearSshPassword`); persisted
  `ui_card_layout` and `ui_hide_issuer` in `VaultStore` and `PreferencesExport.swift`; added
  `PreferencesExport.inspect` so iOS `PreferencesImportDialog` previews exact setting counts before
  applying; and preserved HOTP hash algorithms when editing counters on iOS.
- **Android shared Compose dialog host**: Added `PreferencesImportComposeView`
  (`PreferencesImportDialog`) and `ComposeDialogHost.setOnDismissListener` so Android preferences
  import previews setting counts through the same shared Compose dialog as desktop and iOS while
  wiping read bytes on dismiss.
- **Credential copy, artwork preservation, password CSV export & desktop `Hide issuer` parity**:
  Added incoming/outgoing password copy for revealed `EMAIL` entries and password/username copy
  selection for `PASSWORD` entries across Android, Compose desktop, and iOS via `EntryCopyDialog`,
  plus inline concealed-note copy and revealed-card `Delete` on iOS; added native-compatible
  `SharedThumbnailPickerDialog` in `:ui-foundation` and preserved custom brand-logo names across
  iOS edits (`EntryDraft.preservingMetadata`); fixed iOS browser password CSV export to check
  session reveal (`VaultStore.isRevealed`) and wired dialect-grouped export to the shared
  `PasswordExportDialog`; added preview confirmation and `ThunderbirdImport.isExportable` parity to
  iOS Thunderbird CSV import/export; and exposed persisted `ui_hide_issuer` in Compose desktop's
  `SharedCardLayoutDialog` and `PreferencesExport.desktopCatalog`.

- **Selective FairEmail export, per-entry password/archive exports, and display & search settings
  parity**: Added selective FairEmail account export (`DesktopEmailExport.openSelective` +
  `MultiChoiceSelectionDialog`) and per-entry browser password CSV / raw archive export actions
  (`DesktopEntryActions`, `DesktopPasswordExport.openSingle`, `DesktopArchiveExport.openSingle`) on
  Compose desktop, matching Android and iOS; added `ThunderbirdImport.isExportable` to `:core` so
  Android and desktop Thunderbird selective export cover both `EMAIL` accounts and legacy
  URL-bearing `PASSWORD` logins; extended `SharedCardLayoutDialog` in `:ui-foundation` across
  desktop and iOS with token digit grouping (`ui_split_group_size`), `Show previous code`
  (`ui_show_prev_token`), and `Hide issuer` (`ui_hide_issuer`); added configurable vault search
  fields (`ui_search_includes`: `Label`, `Issuer`, `Tags`) on desktop and iOS via the shared
  `MultiChoiceSelectionDialog`; and persisted `ui_tag_functionality`, `ui_show_prev_token`,
  `ui_split_group_size`, and `ui_search_includes` in both `PreferencesExport.desktopCatalog` and
  iOS `PreferencesExport.catalog`.

- **Shared multi-identity email editing, drag-and-drop card reordering & individual countdown
  parity**: Migrated `SharedEmailEditDialog`, `SharedEmailPasswordDialog`, and
  `SharedEmailSettingsDialog` into `:ui-foundation` over toolkit-free DTOs in `:ui-contract`
  (`PortableEmailField`, `PortableEmailEditDraft`, `PortableEmailPasswordDraft`,
  `PortableEmailConnectionDraft`, `PortableEmailIdentityDraft`, `PortableEmailSettingsDraft`) so
  Android, Compose desktop, and iOS share one production implementation for comprehensive
  multi-identity FairEmail editing (`Issuer`, `Label`, `Hidden Label`, newline-separated `Tags`,
  `Artwork`, incoming fields, and all `1..N` SMTP identities), masked credential-only rotation,
  and non-secret connection/sender settings with staged secondary SMTP identity (`#2..#N`)
  removal; wired live `EmailSettingsDialog` and `EmailPasswordDialog` dispatch from
  `EmailDetailsDialog` on both Compose desktop (`ComposeMain.kt`) and Android
  (`EmailDetailsComposeView` / `EmailSettingsComposeView` / `EmailPasswordComposeView` plus inline
  `Connection details` card action); shared `portableEntryDragDirection` in `:ui-contract` so iOS
  `PortableVaultScreen` supports press-and-hold drag-and-drop card reordering with lifted card
  previews and edge auto-scrolling alongside masked OTP countdown rings; and added persisted
  `ui_show_individual_timeouts` (`Show individual countdown timers`) in `SharedCardLayoutDialog`,
  `PreferencesExport.desktopCatalog`, and iOS `VaultStore` / `PreferencesExport.catalog`.

- **Shared password import/export choosers, Thunderbird import confirmation & iOS lockscreen
  `About` parity**: Added `PasswordImportComposeView` (`PasswordImportDialog` ->
  `SharedPasswordImportDialog`), `PasswordExportComposeView` (`PasswordExportDialog`), and
  `ArchiveExportComposeView` (`ArchiveExportDialog`) in `ComposeDialogViews.kt` and wired
  `MainActivity` so Android previews browser/archive password imports, selects dialect/column CSV
  snapshot groups or stored password archives, and confirms Thunderbird CSV credential imports
  through the same shared `:ui-foundation` dialogs as Desktop and iOS; wrapped iOS
  `UnlockPresentation` (`UnlockViewController.kt`) in `AboutNavigation` and wired
  `AboutComposeViewController` (`AboutViewController.kt` + `AboutInfo` link allowlist) in
  `ComposeUnlockHost.swift` so pre-unlock `About` matches Desktop; and removed dead legacy Android
  `ManualEntryDialog.show` fallback and unused `AlertDialog` imports.

- **Shared `HardwareCopyDialog`, `OpenPgpExportDialog`, and `StorageLocationDialog` across Android,
  Desktop, and iOS**: Extended `HardwareCopyDialog` (`:ui-foundation`) with configurable transport
  warnings and explicit `requireTouch` confirmation so Android `HardwareKeyActivity`
  (`HardwareCopyComposeView`) shares the same two-stage seed-export selection/confirmation form as
  Compose desktop while destroying unselected `Entry` secrets and zeroizing OATH PIN buffers on
  cancel or lock; extended `OpenPgpExportDialog` (`:ui-foundation`) and wired
  `OpenPgpExportComposeView` in Android `BackupActivity` so Android confirms configured SAF
  recipient key filenames and multi-recipient security warnings through the same dialog as Desktop
  and iOS before encrypting; and parameterized `StorageLocationDialog` (`:ui-foundation`) with
  platform-accurate private container guidance across Desktop, iOS (`PortableVaultViewController`),
  and Android `SettingsActivity` (`StorageLocationComposeView`).

- **Shared vault drag primitives, iOS inline `SharedVaultSortBar`, and cross-build `Manage tags` /
  `Password details` editor parity**: Consolidated `sharedVaultViewportScroll` and
  `SharedVaultDraggedItemBox` in `SharedVaultScreen.kt` (`:ui-foundation`) across both
  `VaultScreen.kt` (`:ui`) and `PortableVaultScreen.kt` (`:ui-foundation`); extended
  `PortableVaultState` (`:ui-contract`) with `sortOptions` and wired `SharedVaultSortBar` in
  `PortableVaultScreen.kt` and `ComposeVaultHost.swift` so iOS renders inline sort chips alongside
  Desktop; added `onManageTags` (`Manage tags` -> `TagsDialog`) across `SharedManualEntryDialog`,
  `SharedEmailEditDialog`, and `SharedPasswordEntryDialog` on Android (`VaultComposeView` +
  `AndroidVaultController`), Desktop (`ComposeMain` + `DesktopEntryActions.openTags`), and iOS
  (`PortableVaultViewController` + `ComposeVaultHost.swift`); and added `onViewDetails`
  (`View details` -> `EntryDetailsDialog`) on `SharedPasswordEntryDialog` across Android, Desktop
  (`DesktopEntryActions.openPasswordDetails`), and iOS.

- **Shared `PortableCardLayout` geometry, `PortableCard` countdown/counter/email parity,
  `HardwareOathDialog` clipboard copy, and `BusyProgressDialog` Android cutover**: Consolidated card
  layout geometry (`cardVerticalPaddingDp`, `codeBelowIdentity`, `truncatedLabelMaxLines`,
  `thumbnailSizeDp`) on `PortableCardLayout` (`:ui-contract`) and delegated `VaultAppearance`
  (`:ui-model`) and `PortableVaultScreen` (`:ui-foundation`) to it; extended `PortableCard`
  (`:ui-contract`) with `periodSeconds`, `showCountdown`, `counterLabel`, and `isEmail` and wired
  `ComposeVaultHost.swift` + `PortableVaultScreen.kt` so iOS renders accurate custom-period
  countdown rings, numeric remaining-seconds indicators, expiry highlights when individual timers
  are hidden, HOTP counter labels in the code area, and inline `Connection details` actions on
  revealed email cards; added `onCopyCode` on `HardwareOathDialog` (`:ui-foundation`) wired to
  `ExpiringClipboard` in `ComposeMain.kt`; and introduced `BusyProgressDialog` (`:ui-foundation`) +
  `BusyProgressComposeView` (`ComposeDialogViews.kt`), retiring the remaining `AlertDialog` builders
  from Android `SettingsActivity` and `HardwareKeyActivity`.

- **Shared `SharedVaultFilterHeader`, cross-build `PortableLabelDisplay` & `showThumbnails` card
  appearance parity, and Android `HardwareEnrollmentComposeView` / `HardwareUnlockActivity`
  unification**: Extracted `SharedVaultFilterHeader` in `SharedVaultComponents.kt` (`:ui-foundation`)
  and unified filter-header ordering and spacing across both `VaultScreen.kt` (`:ui`, Android and
  Desktop) and `PortableVaultScreen.kt` (`:ui-foundation`, iOS); added `PortableLabelDisplay`
  (`TRUNCATE`, `SCROLL`, `MULTILINE`) and `showThumbnails` to `:ui-contract` and delegated
  `VaultAppearance` (`:ui-model`), `PortableVaultScreen`, `SharedCardLayoutDialog` (`:ui-foundation`),
  `CardLayoutDialog` (`:ui`), Desktop (`PreferencesExport.kt`, `VaultController.kt`,
  `DesktopLayoutSettings.kt`, `ComposeMain.kt`), and iOS (`PreferencesExport.swift`,
  `VaultStore.swift`, `PortableVaultViewController.kt`, `ComposeVaultHost.swift`) so card label
  overflow and thumbnail visibility are configurable, persisted, and round-tripped in portable
  preferences across all builds; wired `HardwareEnrollmentComposeView` (`HardwareEnrollmentDialog`)
  in Android `HardwareKeyActivity` and `BusyProgressComposeView` in `HardwareUnlockActivity`; and
  retired the remaining `Dialogs.*` imports/fallbacks from `AndroidVaultController`.

- **Cross-build label font size, expiring-token highlight toggle, single/double-tap card actions,
  custom-period label & `UriImportDialog` camera-scan parity**: Added `PortableTapMode`
  (`NOTHING`, `REVEAL`, `COPY`, `LABEL_SIZE_OPTIONS`, `normalizeLabelSizeSp`),
  `PortableCard.customPeriodLabel`, and `PortableVaultState` display/tap fields (`labelSizeSp`,
  `highlightExpiringTokens`, `tapSingleName`, `tapDoubleName`) in `:ui-contract`; wired custom
  label sizing, optional expiring-token highlighting, single/double-tap gesture dispatch, and
  non-default TOTP/Steam `Period: Xs` labels across `PortableVaultScreen`, `SharedCardLayoutDialog`
  (`:ui-foundation`), `CardLayoutDialog` (`:ui`), `VaultPresenter` (`:ui-model`), Desktop
  (`PreferencesExport.kt`, `VaultController.kt`, `DesktopLayoutSettings.kt`, `ComposeMain.kt`), and
  iOS (`PreferencesExport.swift`, `VaultStore.swift`, `PortableVaultViewController.kt`,
  `ComposeVaultHost.swift`), expanding both Desktop and iOS portable preference catalogs to 15
  mirrored keys; and added `onScanCamera` (`Scan QR code…`) to `UriImportDialog` across Android
  (`UriImportComposeView` + `MainActivity.openUriImportDialog()`) and iOS (`ComposeVaultHost.swift`
  + `PortableVaultViewController.kt`), preserving active multi-part `otpauth-migration://` batches
  across camera and image handoffs while still canceling staged batches on user dismiss or vault
  lock.

## Required acceptance gates

Each next increment should have shared behavior tests, stale-session/write-failure tests and a
separate commit. Before claiming the same UI, compare screenshots and interactions on Android,
iOS, Windows, macOS and Linux at small/large sizes and increased text scaling. Exercise screen
readers, keyboard focus, scrolling, reveal/copy/edit/delete and lock-during-dialog behavior.
Native Gradle, Xcode, release packaging and hardware tests remain necessary; sandbox source and
stub compilation do not establish those results.
