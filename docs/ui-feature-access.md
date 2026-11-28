# Feature access in the UI

This is a source-route inventory, not a claim that every platform build or device
scenario passes. Earlier non-iOS verification is user-reported; the dependency
refresh and iOS fixes still need native validation. Use a disposable vault for
[device acceptance](shared-ui-device-checklist.md).

## Where to find features

Android and desktop share `VaultToolsBar`/`VaultTool`; iOS builds its shared Tools
menu from `backupSurface()` and `settingsSurface()` in `ComposeVaultHost.swift`.
Some operations intentionally live in nested dialogs rather than the main menu.

| Feature | UI route |
| --- | --- |
| Vault create/unlock/restore | Startup/unlock screens; password recovery remains available alongside supported local quick unlock |
| OTP, notes, SSH keys, passwords and email | Category tabs → Add; revealed card → Edit/actions; type-specific forms expose supported fields |
| Reveal/copy, HOTP advance, mOTP PIN, delete | Card controls/actions; protected operations remain subject to reveal/PIN/session checks |
| Tags, thumbnail and entry details | Revealed card's actions/editor; email exposes connection/settings/password editors |
| Search, sort, tag rules and reorder | Vault filter header and card move controls/drag when manual ordering permits |
| OTP URI, QR images and Google multipart export import | Tools → OTP URI/QR import; scan/image controls and batch review. iOS also lists camera and image import directly |
| Desktop camera | URI import dialog → camera capture; requires local FFmpeg, a supported camera and permission |
| Browser password CSV and password archives | Tools → password import/export; archive export is separate from CSV and requires eligible entries |
| FairEmail, Thunderbird and LinSCP | Tools → named import/export actions, then format-specific review/selection |
| Exact saved-vault copy and legacy migration | Tools → encrypted backup/export → full file copy or explicit migration; distinct from separately encrypted exports |
| Password/OpenPGP export and restore | Tools → encrypted/OpenPGP backup actions → credentials/keys, preview and confirmation |
| Automatic backups | Desktop/iOS: Tools → automatic backups; Android: Settings → backup controls. Mode, credentials, folder and retention remain explicit |
| Password change, auto-lock, display/search options | Tools → named settings; Android's Settings page also exposes authentication/encryption, privacy, input, tap, language, provider and special-feature policies |
| Preferences and filename timestamps | Tools → import/export preferences and backup filename timestamp setting; preferences remain platform-specific |
| Security-key accounts and vault-to-key copy | Android: Tools → OATH security key; desktop/iOS: Tools → OATH accounts or copy OTP to key; compatible accounts/keys only |
| Local hardware unlock | Desktop/iOS: Tools → enroll/remove hardware unlock; Android: Tools → OATH security key → enrollment/removal. Never a substitute for recovery password |
| About, component licenses and links | Tools → About; Android flavor-specific links remain in About |

## Boundaries

- Android's alternate toolbar Tools dialog now includes Settings and OATH-key
  management, matching the main Compose menu's secondary capabilities.
- Informational tool rows display label, description and value without becoming
  clickable. Disabled/informational capability notices are not operational features.
- Platform-specific services are not universal features: Android's external OpenPGP
  provider, screenshot/IME policies and panic controls do not imply desktop/iOS
  equivalents. Desktop system biometry and hardware OpenPGP agent integration are
  not implemented. See [iOS hardware limits](ios-hardware.md) for unsupported key
  management and NFC/USB-C acceptance requirements.
- Automatic signing uses session-only private credentials. Restore does not verify
  signer identity. Concealed secrets and unsupported operations are not exposed
  merely to make menus look complete.

## Regression coverage

`test_ui_feature_reachability.py` checks optional callback/catalog coverage, Android
Tools parity and Settings dispatch, desktop presenter wiring, iOS top-level menu
handlers, nested backup/hardware/camera routes and informational descriptions.
`VaultToolsTest` executes all tool dispatches, unavailable-action filtering and
terminal-session guards. These checks cannot prove visual reachability at every
window/font size, keyboard/screen-reader behavior or successful native operations.
