# Cross-build feature and UI audit — 2026-11-07

## Conclusion

**The builds are not yet fully feature-equivalent or visually identical.** Windows, macOS and
Debian/Linux use the same Compose desktop host; Android shares cards and several forms but retains
Activity chrome and native workflows. iOS uses separate SwiftUI forms. Matching palette values
and file formats alone does not establish UI or functional parity.

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

Five Compose imports (OTP URI/image, password file, FairEmail, OpenPGP and preferences) now use one
`ScrollableDialogBody`. Confirm/cancel buttons remain outside the scrolling body. OpenPGP merge/
replace controls are disabled during work, like the rest of its controls, so the displayed restore
mode cannot change mid-operation. Regression source tests pin these common presentation rules.

## Required acceptance gates

Each next increment should have shared behavior tests, stale-session/write-failure tests and a
separate commit. Before claiming the same UI, compare screenshots and interactions on Android,
iOS, Windows, macOS and Linux at small/large sizes and increased text scaling. Exercise screen
readers, keyboard focus, scrolling, reveal/copy/edit/delete and lock-during-dialog behavior.
Native Gradle, Xcode, release packaging and hardware tests remain necessary; sandbox source and
stub compilation do not establish those results.
