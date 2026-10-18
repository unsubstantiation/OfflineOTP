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

## Continuous integration

Besides the four APK workflows, [android-ci.yml](.github/workflows/android-ci.yml) runs the quality gate on every push and pull request: the Python source guards and build-input provenance check, the host-side test batteries (`scripts/kotlin-sandbox/run.sh`, `scripts/run-jvm-tests.sh`, the instrumentation compile check), all-variant builds with Gradle host unit tests and lint plus the packaged-manifest policy, and the fdroid/play × API 28/36 emulator instrumentation matrix (`scripts/ci/start-emulator.sh`). A reproducibility job re-builds both flavors twice from independent source and Gradle homes and compares the unsigned APKs and dependency inventories byte for byte on master and on demand. [dependency-baseline.yml](.github/workflows/dependency-baseline.yml) is a manual workflow that uploads the resolved-dependency inventory for provenance review.

## Documentation

- [Changelog](CHANGELOG.md) — published release history.
- [Vault security hardening](docs/vault-security-hardening.md) — storage, session, backup and IPC security behavior.
- [Kotlin conversion record](docs/kotlin-extracted-helpers.md) — conversion scope and verification limits.
- [OTP URI parser](docs/kotlin-otp-parser-pilot.md) and [vault/session/task contracts](docs/kotlin-vault-session-task-contracts.md) — implementation details and contract boundaries.
- [UI decomposition plan](docs/ui-decomposition-plan.md) — remaining architectural extraction work.

## Contributing

Report bugs and request features through the [GitHub issue tracker](https://github.com/unsubstantiation/OfflineOTP/issues). For bug reports, include the app version, Android version, reproduction steps, and relevant logs; remove secrets and account data before sharing logs.

## Credits and license

Copyright (c) 2026 unsubstantiation — [http://fairuniversal.com](http://fairuniversal.com)

This project is licensed under the **OfflineOTP Custom Noncommercial License with Attribution and Artificial Intelligence Restrictions, Version 1.0**. The complete, binding terms are in [LICENSE.txt](LICENSE.txt). In summary:

- **Noncommercial use only.** You may use, copy, modify, and share this software for noncommercial purposes only.
- **Attribution is mandatory.** Every copy, modification, and distribution must prominently credit "unsubstantiation" and carry the website link [http://fairuniversal.com](http://fairuniversal.com) — in source headers, in the license/notice files, and in a user-visible "About"-equivalent screen for binary distributions.
- **No AI copying or modification.** Only natural persons may copy or modify this code. Copying or modifying it by, through, or with the assistance of any artificial intelligence system — including "SuperAI", AI agents, autonomous coding agents, AI coding assistants, copilots, and similar tools — is prohibited.
- **No AI training.** This code may not be used, in whole or in part, as training, fine-tuning, or evaluation data for any AI system, including for embeddings, retrieval (RAG) corpora, synthetic-data generation, or model distillation.
- **No stripping of notices.** The copyright notice, attribution, and website link may not be removed or obscured.
- **Commercial use, AI use of any kind above, and any other prohibited use require a separate written license** — contact the author via [fairuniversal.com](http://fairuniversal.com).
- The software is provided "as is", without warranty of any kind.

### Attribution (prior work)

This project is a complete update from android 5.1+ to android 9+ rewrite in Kotlin of andOTP, originally copyright 
(C) 2017-2020 Jakob Nixdorf and (C) 2015 Bruno Bierbaumer.
