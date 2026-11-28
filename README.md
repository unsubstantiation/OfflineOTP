![OfflineOTP](./assets/logo.png)

# OfflineOTP

OfflineOTP is an offline authenticator and encrypted credential vault for Android,
Debian, Windows, macOS and iOS. It derives from andOTP and uses shared Kotlin domain
logic and Compose Multiplatform UI. The iOS engine/host conversion is still in progress.

## Status and platforms

- **Android:** Android 9/API 28 minimum; compile SDK 36, target SDK 35; F-Droid and Play flavors.
- **Desktop:** Compose/JVM; Debian and Windows artifacts target x86-64, macOS targets ARM64.
- **iOS:** iOS 17 deployment minimum; shared Compose UI with remaining Swift production
  dependencies. Only a thin YubiKit SDK bridge is intended to remain in Swift.
- The user reports all non-iOS builds and platform checks verified. **The iOS build has
  failed; fixes and the Kotlin cutover still require a successful native run.** This is
  not a claim of an independent security audit or evidence for every future build.

See the [documentation index](docs/README.md), [current migration status](docs/shared-ui-migration-status.md)
and [iOS cutover inventory](docs/ios-kotlin-cutover.md). Do not run two clients against
one active vault. Keep a tested backup before upgrading or migrating.

## Features

- TOTP, HOTP, Steam Guard and mOTP; QR scanning/images, manual entry and `otpauth://` import.
- Google Authenticator multipart export import with review before installation.
- Separate OTP, Notes, SSH Keys, Passwords and Email tabs; search, tags and ordering.
- Password-recoverable encrypted vaults, exact saved-vault copies, password-encrypted
  exports and OpenPGP exports. Legacy format support is described in the format docs.
- Optional local biometric/security-key unlock and automatic backups while unlocked.
- Bundled issuer artwork. Brand names/logos identify their owners and imply no endorsement.
- The UI uses the Dark Forest theme, with amber one-time codes.

Android declares no `INTERNET` permission. OS/provider services and explicitly opened
external links have their own behavior; offline operation does not make a compromised device safe.

### Secure notes

Reveal a note to read its contents in full. Concealed cards do not expose protected
content or copy actions. A note is encrypted with the vault, not stored as a separate file.

### Passwords and Email tabs

Import browser CSV credentials, LinSCP keys/sessions, FairEmail backups or Thunderbird
login credentials through the relevant import action. CSV and QR exports can contain
plaintext secrets: transfer them privately and remove temporary copies when no longer needed.
See [LinSCP interoperability](docs/linscp-key-session-interchange.md) and
[email compatibility](docs/email-parity-audit.md).

### Email accounts (FairEmail)

Use **+ → Add email account** on Android or **Add entry → Add email account…** on desktop;
**Tools → Import FairEmail backup…** imports existing accounts. **Import Thunderbird**
imports login credentials, not a complete mail profile. **View connection details** exposes
stored account/connection fields; OfflineOTP is not a mail client.

## Vaults, backups and recovery

New portable vaults require a recovery password. Biometric and security-key unlock
are local conveniences, not replacements for that password. Legacy device-only vaults
need explicit password migration before portable recovery is available.

- **Full backup:** an exact copy of the saved encrypted vault file. It recovers saved
  entries with the vault recovery password, not local device grants or all preferences.
- **Password export:** a separate `.json.aes` document using its export password.
- **OpenPGP export:** uses recipient keys; signing support depends on the selected engine/provider.
  Do not assume a successful decrypt authenticates a sender or verifies a signature.
- **Preferences export:** separate, platform-specific settings; not a vault backup.
- **Automatic backup:** choose the source, destination and retention explicitly. Desktop/iOS
  use session scheduling; Android uses save-triggered work. No locked-vault backups and
  no persisted private signing keys/passphrases. Key changes can require reconfiguration.

Restore previews and confirmation do not synchronize independently edited vaults. HOTP
counters on different copies advance independently. Hardware-held accounts are not in
vault backups. Test recovery before uninstalling, clearing data or changing signing keys.
Use a strong recovery passphrase: stolen encrypted files permit offline guessing.

Details: [portable vault format](docs/portable-vault-container.md),
[backup/restore](docs/full-backup-restore.md), [automatic backups](docs/automatic-backups.md),
and [security boundaries](docs/vault-security-hardening.md).

## Hardware keys

Compatible OATH keys support account access/provisioning; compatible FIDO2 PRF keys
can provide an **alternative**, not a mandatory second factor, to password unlock.
NFC and USB-C are separate transport capabilities; protocol/firmware support must also match.
The Nitrokey Pro native OTP protocol is not implemented. Copying an app account to a key
sends its seed to that key, and app/key HOTP counters then advance independently.

Non-iOS verification is user-reported; iOS physical-key checks remain pending.
See [iOS hardware](docs/ios-hardware.md) for SDK and transport limitations.

## Build and run

Use JDK 17 and Android SDK platform 36/build-tools 36.0.0. Gradle configures Android
modules even for desktop/Apple tasks. Dependency resolution requires network access.

```sh
./gradlew --no-daemon :app:assembleFdroidDebug :app:assemblePlayDebug
./gradlew --no-daemon :desktop-compose:run
./gradlew --no-daemon :desktop-compose:distZip
./gradlew --no-daemon :desktop-compose:packageDesktop
```

Desktop `distZip` includes host-specific native dependencies, not a universal ZIP.
`packageDesktop` produces an app image, not an installer; run it on the target OS.
The old `:desktop:run` alias forwards to Compose; no Swing UI fallback remains.

Desktop vault/settings live in `data` **inside the program folder** by default.
Use `-Dofflineotp.home=<folder>` or `OFFLINEOTP_HOME` for a different writable location.
See [desktop packaging](scripts/packaging/README.md).

The native backend in `rust/offlineotp-openpgp` uses rpgp 0.21 + UniFFI and is the
**default desktop engine**. PGPainless (`org.pgpainless:pgpainless-core:2.0.4`) remains a
fallback when the native library is absent, not a retry after native crypto rejection.
See [backend packaging](scripts/openpgp/README.md).

Build iOS on macOS with Xcode/Swift 6.1+, following [ios/README.md](ios/README.md).
The [iOS contract](docs/ios-client-plan.md) distinguishes portable formats from legacy files.

## Tests and workflows

```sh
python3 -m unittest discover -s scripts/tests
python3 scripts/check_build_inputs.py
python3 scripts/check_shared_boundaries.py
./gradlew --no-daemon :core:test :ui-model:desktopTest :desktop:test :desktop-compose:test
./gradlew --no-daemon :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest
# Requires a connected disposable Android device/emulator:
./gradlew --no-daemon :app:connectedFdroidDebugAndroidTest :app:connectedPlayDebugAndroidTest
```

Python tests require PyYAML. [Sandbox checks](scripts/kotlin-sandbox/README.md) use
substitute dependencies; they do not prove Compose rendering or native integration.
Generate the dependency inventory with `./gradlew writeDependencyInventory`.

GitHub Actions builds platform artifacts, not releases. Android/Windows/Debian have
push/manual triggers; macOS/iOS and the separate desktop validation workflow are manual.
Android signed release APKs require configured signing and the permitted branch;
missing credentials do not produce a replacement debug release. The manual Android
migration gate runs 51 selected tests across both flavors; it is not the full device suite.

Artifacts include separate Android APKs, Windows x64 ZIP, Debian x64 archive and
checksums, macOS ARM64 ZIP and `OfflineOTP-ios-unsigned.ipa`. The iOS workflow checks
shared Kotlin tests, XCFrameworks and the unsigned device app; it does not run the
retired standalone SwiftPM suite or simulator XCTest. A successful build is not a
physical-device, signature-trust or App Store acceptance test.

See [release signing](docs/release-signing.md) and
[release checklist](docs/shared-migration-release.md). Signing/distribution for iOS
remains separate; macOS CI uses ad-hoc signing, not Developer ID notarization.

## Versioning

Displayed versions use the build timestamp in fixed AKST (UTC−09:00):
`VYYYY-MM-DD-HH-MM`. Set `SOURCE_DATE_EPOCH` for repeatable timestamps; do not reuse
an older timestamp for an update requiring a higher numeric version code.
This does not change vault or interchange format versions.

## Contributing

Report reproducible issues through the [issue tracker](https://github.com/unsubstantiation/OfflineOTP/issues).
Include platform/OS, artifact or commit, steps and redacted logs. Never include vaults,
passwords, recovery material or secret-bearing QR codes. Inherited Fastlane screenshots
are not reliable depictions of the current UI.

## Credits and license

Copyright (c) 2026 unsubstantiation — [http://fairuniversal.com](http://fairuniversal.com)
and [releases](https://github.com/unsubstantiation/OfflineOTP/releases).
The in-app About screen contains attribution and project/license links.

This project is licensed under the **OfflineOTP Custom Noncommercial License with Attribution
and Artificial Intelligence Restrictions, Version 1.0**. The complete, binding terms are in
[LICENSE.txt](LICENSE.txt). It is **not** an OSI-approved open-source license. In summary:

- **Noncommercial use only.** Commercial use requires a separate written license.
- **Attribution is mandatory.** Every copy, modification, and distribution must credit
  "unsubstantiation" and carry [http://fairuniversal.com](http://fairuniversal.com) in source
  headers, license/notice files, and a user-visible About-equivalent screen for binaries.
- **No AI copying or modification.** Only natural persons may copy or modify this code; doing so
  by, through, or with the assistance of any artificial intelligence system is prohibited.
- **No AI training.** The code may not be used as training, fine-tuning, or evaluation data, or
  in embeddings, retrieval corpora, synthetic-data generation, or model distillation.
- **Keep the notices.** The copyright notice, attribution, license text, and website link may
  not be removed or obscured. The software is provided "as is", without warranty of any kind.

### Attribution (prior work)

This project is a Kotlin update of andOTP for Android 9+, originally copyright (C) 2017-2020
Jakob Nixdorf and (C) 2015 Bruno Bierbaumer.
