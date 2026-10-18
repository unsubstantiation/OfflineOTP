![OfflineOTP](./assets/logo.png)

# OfflineOTP

OfflineOTP is an open-source Android authenticator that generates time-based (TOTP), counter-based (HOTP), and mOTP one-time passwords. It supports Android 9 (API 28) and later. It fixes many of the issues AndOTP had due to an android 5.1 target and java issues. The current app version is 2026-10-17; the Android application ID remains `org.shadowice.flocke.andotp`.

## Features

- Add accounts by scanning an authenticator QR code or entering details manually.
- Store the vault encrypted using Android KeyStore-backed or password/PIN-based protection.
- Create portable password-encrypted or OpenPGP-encrypted backups.
- Use light, dark, or black themes; organize entries with tags.
- Generate TOTP (RFC 6238), HOTP (RFC 4226), and mOTP codes.

## Protecting and restoring your vault

Use OfflineOTP's in-app backup and restore functions. Android cloud backup and device transfer are disabled, and copying app data with third-party backup tools does not preserve the separate KeyStore key. Export a portable backup and verify that it can be restored before changing devices, reinstalling, clearing app data, or switching to a build signed with a different key. A signing-key change requires uninstalling the old build and can erase its data.

Automatic backup broadcasts are restricted to explicitly authorized companion apps signed with the same identity; ordinary shell commands and independently signed automation apps are not authorized. See [Vault security hardening](docs/vault-security-hardening.md) for the current behavior and compatibility details.

## Build

The project uses Gradle, Android Gradle Plugin, Kotlin and the Android SDK. The GitHub Actions workflows build the fdroid and play APKs (debug and release):

```sh
./gradlew --no-daemon :app:assembleFdroidDebug
./gradlew --no-daemon :app:assembleFdroidRelease
./gradlew --no-daemon :app:assemblePlayDebug
./gradlew --no-daemon :app:assemblePlayRelease
```

## Documentation

- [Changelog](CHANGELOG.md) — published release history.
- [Vault security hardening](docs/vault-security-hardening.md) — storage, session, backup and IPC security behavior.
- [Kotlin conversion record](docs/kotlin-extracted-helpers.md) — conversion scope and verification limits.
- [OTP URI parser](docs/kotlin-otp-parser-pilot.md) and [vault/session/task contracts](docs/kotlin-vault-session-task-contracts.md) — implementation details and contract boundaries.
- [UI decomposition plan](docs/ui-decomposition-plan.md) — remaining architectural extraction work.

## Contributing

Report bugs and request features through the [GitHub issue tracker](https://github.com/unsubstantiation/OfflineOTP/issues). For bug reports, include the app version, Android version, reproduction steps, and relevant logs; remove secrets and account data before sharing logs.

## Credits and license
# LICENSE

Copyright (c) 2026 unsubstantiation (fairuniversal.com)

This software is licensed under the PolyForm Noncommercial License 1.0.0. 
You may use, copy, modify, and distribute this software for noncommercial purposes only.

### Attribution Requirement
Any copying, distribution, or modification of this code or its contents strictly requires 
prominent attribution to the original author (unsubstantiation) by retaining the above 
copyright notice in all copies or substantial portions of the software.

To view the full terms, visit: https://polyformproject.org

--------------------------------------------------------------------------------

# ATTRIBUTION

This project is a complete update from android 5.1+ to android 9+ rewrite in Kotlin of andOTP, originally copyright 
(C) 2017-2020 Jakob Nixdorf and (C) 2015 Bruno Bierbaumer.
