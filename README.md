![OfflineOTP](./assets/logo.png)

# OfflineOTP

OfflineOTP is a source-available Android authenticator for time-based (TOTP), counter-based
(HOTP), Steam Guard, and mOTP one-time passwords. It supports Android 9 (API 28) and later. It
is a Kotlin rewrite of andOTP with hardened vault, backup, and IPC handling. The current build
is `versionName 0.9.0.1` (versionCode 39); the application ID remains
`org.shadowice.flocke.andotp`.

## Features

- Add accounts by scanning a QR code, importing a QR image or `otpauth://` URI, or entering details manually.
- Keep the vault encrypted with Android KeyStore-backed protection or a password/PIN.
- Create password-encrypted or OpenPGP-encrypted backups, including automatic encrypted backups.
- Generate TOTP (RFC 6238), HOTP (RFC 4226), Steam Guard, and mOTP codes; copy them or share them as keystrokes.
- Search and tag entries, and choose light, dark, or black themes.
- Stay offline: the app declares no `INTERNET` permission.

## Protecting and restoring your vault

Use the in-app backup and restore functions. Android cloud backup and device transfer are
disabled, and copying app data with third-party backup tools does not preserve the separate
KeyStore key. Export a portable backup and verify that it restores before changing devices,
reinstalling, clearing app data, or switching to a build signed with a different key. A signing
key change requires uninstalling the old build and can erase its data.

Automatic backup broadcasts are restricted to explicitly authorized companion apps signed with
the same identity; ordinary shell commands and independently signed automation apps are not
authorized. See [Vault security hardening](docs/vault-security-hardening.md) for the current
behavior and limits.

## Build

The project uses Gradle, the Android Gradle Plugin, Kotlin, and the Android SDK (JDK 17,
compile SDK 36, build-tools 36.0.0). The GitHub Actions workflows build the fdroid and play
APKs in debug and release:

```sh
./gradlew --no-daemon :app:assembleFdroidDebug :app:assembleFdroidRelease \
  :app:assemblePlayDebug :app:assemblePlayRelease
```

## Continuous integration

[android-ci.yml](.github/workflows/android-ci.yml) is the quality gate on every push and pull
request: Python source guards and build-input provenance, the Kotlin sandbox check, host JVM
tests, the instrumentation compile check, all-variant builds with Gradle unit tests and lint,
the packaged-manifest policy, and the fdroid/play × API 28/36 emulator matrix. A reproducibility
job rebuilds both flavors twice from independent source and Gradle homes and compares the
unsigned APKs and dependency inventories byte for byte on `master` and on demand.

The four `build-*-apk.yml` workflows produce per-variant APK artifacts.
[release-fdroid-debug.yml](.github/workflows/release-fdroid-debug.yml) publishes the fdroid
debug APK (with SHA-256 checksums) as a GitHub Release when a `v*` tag is pushed.
[dependency-baseline.yml](.github/workflows/dependency-baseline.yml) is a manual workflow that
uploads the resolved-dependency inventory for provenance review.

## Documentation

- [Changelog](CHANGELOG.md) — release history.
- [Vault security hardening](docs/vault-security-hardening.md) — storage, session, backup, and IPC behavior.
- [Kotlin conversion record](docs/kotlin-extracted-helpers.md) — conversion scope and verification limits.
- [OTP URI parser](docs/kotlin-otp-parser-pilot.md) and [vault/session/task contracts](docs/kotlin-vault-session-task-contracts.md) — parser and contract boundaries.
- [UI decomposition plan](docs/ui-decomposition-plan.md) — completed slices and remaining extraction work.
- [Language security assessment](docs/language-security-assessment.md) — historical baseline review.
- [Latest bug-hunt follow-up](docs/bughunt-2026-10-25-tag-drawer.md), [the earlier 2026-10-25 pass](docs/bughunt-follow-up-2026-10-25.md), [the previous pass](docs/bughunt-follow-up-2026-10-18.md) and [andOTP issue triage](docs/andotp-open-issues-triage.md) — audit and upstream-issue records.

## Contributing

Report bugs and request features through the [GitHub issue tracker](https://github.com/unsubstantiation/OfflineOTP/issues).
Search existing issues first. For bug reports, include the app version, Android version,
reproduction steps, and relevant logs; remove secrets and account data before sharing logs.

## Credits and license

Copyright (c) 2026 unsubstantiation — [http://fairuniversal.com](http://fairuniversal.com)

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
