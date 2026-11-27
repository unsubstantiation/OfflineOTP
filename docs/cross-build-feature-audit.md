# Cross-build feature and UI audit — 2026-11-07

> Historical report: current routes and remaining differences are audited in
> [the 2026-11-20 source audit](cross-platform-parity-audit-2026-11-20.md). Do not use the old native
> About/SwiftUI/navigation descriptions below as current completion evidence.

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
| P2 | OpenPGP recipient limits | Unified across Android, Desktop, iOS and the Rust `rpgp` backend at `64` distinct recipients (`OpenPgpKeyFilePolicy.MAX_RECIPIENTS` / `PortableInputBounds.MAX_OPENPGP_RECIPIENTS` / `OpenPgpBackupCodec.maxRecipients`) and a `16 MiB` (`BoundedInputReader.MAX_DOCUMENT_BYTES`) aggregate recipient-key byte budget. | Retain shared `64`-recipient and `16 MiB` aggregate key-block bounds across all clients and codecs; exercise multi-recipient encrypt/decrypt on native toolchains. |
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

- **Shared `:ui-foundation` artwork contract, global 30s rollover bar, reveal auto-hide timeout,
  OTP/SSH `View details`, Android inline `Connection details`, and token-usage sort notice parity**:
  Consolidated `VaultArtwork`, `SharedIssuerArtwork`, and `SharedCatalogArtworkIcon` into
  `:ui-foundation`; added `SharedVaultGlobalProgressBar`, `PortableCardDisplaySelection`,
  `ui_hide_global_timeout`, and `ui_tap_to_reveal_timeout` across `:ui-contract`, `:ui-foundation`,
  `:ui-model`, `:ui`, Desktop (`PreferencesExport.kt`, `VaultController.kt`, `DesktopLayoutSettings.kt`,
  `ComposeMain.kt`), and iOS (`PreferencesExport.swift`, `VaultStore.swift`,
  `PortableVaultViewController.kt`, `ComposeVaultHost.swift`), expanding both Desktop and iOS
  portable preference catalogs to 17 mirrored keys; added `EntryMetadataDetails` (`:core`) and
  `onViewDetails` (`View details` -> `EntryDetailsDialog`) to `SharedManualEntryDialog` /
  `ManualEntryDialog` for revealed OTP and SSH-key entries across Android, Desktop, and iOS; wired
  inline `onViewEmailDetails` on Android `VaultComposeView`; and added one-time `Token usage`
  sort notices (`ui_used_tokens_shown`) on Desktop and iOS when selecting `Last used` or `Most used`
  sort.

- **Shared iOS vector brand artwork (`thumb_*.xml`), Desktop unlocked `About`, custom thumbnail
  sizing, `Focus search on start`, background/minimize relock, and optional backup filename
  timestamps**: Added generated `PortableThumbnailAssets` (`scripts/branding/generate_thumbnail_assets.py`)
  and pure-Kotlin `PortableVectorDocument` in `:ui-contract`, plus `BundledVectorVaultArtwork` /
  `SharedVectorBadge` / `SharedVectorCanvas` in `:ui-foundation`, and bundled `../app/src/main/res/drawable`
  (`thumb_*.xml`) into `ios/project.yml` so iOS renders all 466 shared brand vector logos on vault
  cards and thumbnail-bearing dialogs (`PortableVaultViewController` + `ComposeVaultHost.swift`
  `registerArtworkXmlLoader`) while concealing brand hints when `Hide issuer` is enabled on
  unrevealed cards (`PortableCard.visibleMark`); wired unlocked `About` (`VaultTool.ABOUT` ->
  `AboutDialog`) on Compose desktop (`ComposeMain.kt`); and added `ui_thumbnail_size_dp`
  (`Thumbnail size`: `Default` or `24..64 dp`), `ui_focus_search_on_start` (`Focus search on start`),
  `ui_relock_background` (`Lock immediately when backgrounded or minimized`), and
  `ui_backup_append_date_time` (`Timestamp backup filenames`) across `:ui-contract`, `:ui-foundation`,
  `:ui-model`, `:ui`, `:core` (`BackupFileNames.appendTimestamp`), `:app` (`AndroidVaultController`),
  Desktop (`VaultController`, `DesktopLayoutSettings`, `DesktopAutoLockSettings`, `DesktopSavePicker`,
  `ComposeMain` `windowIconified`), and iOS (`VaultStore`, `ComposeVaultHost`,
  `AppRootViewController` `UIScene.didEnterBackgroundNotification`), expanding both Desktop and iOS
  portable preference catalogs to 21 mirrored keys.

- **Unified 64-recipient / 16 MiB OpenPGP ceiling, multi-identity SMTP creation & primary
  reassignment, persisted tag selections, and Desktop `Minimize window after copying`**: Unified the
  OpenPGP recipient limit (`64`) and aggregate `16 MiB` recipient-key byte budget across
  `PortableInputBounds.MAX_OPENPGP_RECIPIENTS`, `OpenPgpExportDialog` (`:ui-foundation`),
  `OpenPgpBackupCodec.seal` (`:core`), Android `OpenPgpKeyFileBackupTask`, Desktop
  `DesktopOpenPgpExport.MAX_RECIPIENT_FILES`, and iOS `OpenPgpBackupCodec` / `ComposeVaultHost`;
  extended `PortableEmailSettingsDraft` (`PortableAddedEmailIdentityDraft`, `primaryIdentityIndex`,
  `withoutSecrets()`), `SharedEmailSettingsDialog`, `EmailSettingsInput` (`AddedEmailIdentityInput`),
  `PortableVaultViewController`, and Swift `EmailSettingsInput` / `ComposeVaultHost` so Android,
  Desktop, and iOS can create additional SMTP identities with masked credentials and reassign the
  primary sender identity (promoting the chosen primary to `identities[0]` and scrubbing added
  passwords from UI/draft state on submit); persisted active tag chip selections
  (`ui_selected_tags`, `ui_untagged_selected`) reconciled against live vault tags across Desktop
  (`VaultPresenter`, `VaultController`, `ComposeMain`, `PreferencesExport.desktopCatalog`) and iOS
  (`VaultStore`, `ComposeVaultHost`, `PreferencesExport.catalog`); and added Desktop
  `Minimize window after copying` (`ui_minimize_on_copy` in `SharedCardLayoutDialog`,
  `DesktopLayoutSettings`, `VaultController`, `ComposeMain`, and `PreferencesExport.desktopCatalog`).

- **Unified `SharedVaultToolsBar` on iOS, `SharedVaultEntryDialog` delegation, cross-build
  `HideableDialog` used-tokens notice parity, Dark Forest two-tab `AboutDialog` (`About` |
  `Libraries`), and per-card accessible action descriptions**: Extended `PortableVaultState`
  (`:ui-contract`) with `toolGroups` and wired `SharedVaultToolsBar` (`+ Add` + `Tools` ->
  `SharedVaultToolsDialog`) in `PortableVaultScreen.kt` (`:ui-foundation`) and
  `ComposeVaultHost.swift` so iOS and Desktop share the same top-bar chrome (`Lock` in
  `SharedVaultTopBar` + grouped secondary tools modal closed before dispatch); migrated
  `VaultEntryDialog` (`:ui`) onto `SharedVaultEntryDialog` (`:ui-foundation`) over
  `PortableEntryDialogKind` (`:ui-contract`); unified Desktop (`ComposeMain.kt`) and iOS
  (`PortableVaultViewController.kt` + `ComposeVaultHost.swift`) token-usage sort notices onto
  `HideableDialog` (`:ui-foundation`) so `usedTokensDialogShown` is persisted only when `"Don't
  show this message again"` is checked (matching Android); upgraded `AboutDialog` (`:ui-foundation`)
  with Dark Forest styling and two-tab `About` / `Libraries` open-source license notices over
  `PortableLibraryCatalog.bundled` (`:ui-contract`); and added privacy-preserving per-entry
  `contentDescription` labels across `VaultComponents.kt` (`:ui`) and `VaultToolsBar.kt`.


- **Unified website password creation, tab-aware multi-type entry creation, email creation artwork
  selection, and Desktop empty-state CTA parity**: Extended `SharedPasswordEntryDialog`
  (`:ui-foundation`) and `PortablePasswordEditDraft` (`:ui-contract`) with creation mode
  (`editing = false`), masked password entry, optional notes, artwork picker, and bidirectional
  mode switching (`OTP, note, or key`, `Website password`, `Email account`, `Import otpauth://
  link`) alongside `SharedManualEntryDialog` and `SharedEmailAccountDialog`; added
  `PasswordEntryInput.buildNew()` (`:ui-model`) and `PasswordCsvExport.create` (`OfflineOTPKit`)
  encoding the canonical Chrome-exportable `["name", "url", "username", "password", "note"]`
  `PasswordMaterial` schema; wired tab-aware `+ Add` and password creation across Compose desktop
  (`DesktopEntryCreation.open(category)` / `preparePassword`, `ComposeMain`), Android
  (`AndroidVaultController.openPasswordCreation` / `openSharedCreation`, `VaultComposeView`), and
  iOS (`ComposeVaultHost.openPasswordCreator` / `savePasswordCreate`,
  `PortableVaultViewController`); added custom brand-artwork selection to `SharedEmailAccountDialog`
  (`PortableEmailAccountDraft.thumbnail`, `EmailEntryInput.thumbnailName`, `VaultStore.addEmailAccount`);
  and wired `VaultEmptyState` (`VaultScreen.kt` / `VaultComponents.kt`) to `actions.onAddEntry` when
  no search or tag filter is active, matching `PortableVaultScreen`.

- **Shared `CardDisplayComposeView`, `AutoLockComposeView`, and `Search fields` on Android plus
  `PortableVaultScreen` empty-state & filter-clear parity**: Added `CardDisplayComposeView`
  (`CardLayoutDialog` -> `SharedCardLayoutDialog`, `allowDefaultGrouping = false`) and
  `AutoLockComposeView` (`AutoLockDialog` + `AutoLockOptions.seconds` + `relockOnBackground`) in `ComposeDialogViews.kt`,
  typed `SharedPreferences` readers/writers in `AndroidPreferencesPort.kt` (`currentCardDisplaySelection`,
  `applyCardDisplaySelection`, `currentAutoLockSeconds`, `applyAutoLockSettings`, `setSearchValues`,
  preserving specialized Android `COPY_BACKGROUND` / `SEND_KEYSTROKES` tap actions when `COPY` is
  kept), zero-timeout reveal support (`tapToRevealTimeoutSeconds == 0` stays visible until hidden)
  in `AndroidVaultController`, and unlocked vault menu actions (`Card display`, `Search fields` via
  `MultiChoiceSelectionComposeView`, and `Auto-lock` gated on non-`NONE` authentication) in
  `MainActivity` with live Compose vault refresh, immediate inactivity timer re-arming, and lock /
  destroy dismissal; and aligned `PortableVaultState` (`:ui-contract`) and `PortableVaultScreen`
  (`:ui-foundation`) with `VaultProjection` / `VaultScreen` for `hasActiveFilter`, `emptyTitle` /
  `emptyMessage` (`No entries yet` / `Nothing in this tab` / `No matches`), category-aware empty
  CTA labels (`Add email account` / `Add password` / `Add an entry`), and tag-bar `Clear` visibility
  when either search or tag filters are active.


## Required acceptance gates

Each next increment should have shared behavior tests, stale-session/write-failure tests and a
separate commit. Before claiming the same UI, compare screenshots and interactions on Android,
iOS, Windows, macOS and Linux at small/large sizes and increased text scaling. Exercise screen
readers, keyboard focus, scrolling, reveal/copy/edit/delete and lock-during-dialog behavior.
Native Gradle, Xcode, release packaging and hardware tests remain necessary; sandbox source and
stub compilation do not establish those results.
