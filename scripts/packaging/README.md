# Desktop packaging

Compose is the only desktop UI. `:desktop` supplies JVM engine/AWT services;
`:desktop-compose` supplies the launcher, native-library staging and app image.
Earlier non-iOS builds were user-reported verified; the latest dependency refresh
still needs platform validation. See [validation limits](../../docs/dependency-refresh.md).

## Outputs and requirements

Use a **full JDK 17** with `jpackage`, Python 3 and the Android SDK required by root
Gradle configuration. Dependencies resolve from configured repositories; native
OpenPGP packaging also requires the Rust build output.

```sh
./gradlew --no-daemon :desktop-compose:distZip
./gradlew --no-daemon :desktop-compose:packageDesktop
```

- `distZip`/`installDist`: launcher scripts and dependency jars, with host-specific
  Compose/Skiko native artifacts. Not a universal distribution.
- `packageDesktop`: portable **app-image**, not a `.msi`, `.deb`, `.rpm` or `.pkg`
  installer. Build on the target OS. The task has no generic `--type` override.
- macOS output: `desktop-compose/build/release/OfflineOTP.app`. CI verifies/ad-hoc
  signs it; it is not Developer ID signed or notarized. OS security policy may
  require explicit user approval or proper distribution signing after download.

The Windows/Debian workflows produce x86-64 images; macOS produces ARM64. They
upload artifacts, not GitHub releases. Windows/macOS ZIPs are downloaded directly;
Debian retains a checksum manifest and artifact wrapper. Only the separate manual
validation workflow runs the broader desktop test matrix.

## Icons and native dependencies

`scripts/branding/generate_icons.py` maintains the shared PNG/Windows ICO from
approved artwork. macOS uses `scripts/branding/generate-macos-icon.py` automatically
when packaging; `-PmacIconFile=/path/to/icon.icns` overrides it. A missing override
fails rather than silently using a Java icon. Archive macOS bundles with `ditto`
to preserve required links/metadata.

The distribution includes shared domain/UI jars and native dependencies, including
the staged OpenPGP library. PGPainless/BouncyCastle remains an unavailable-native
fallback; ZXing and YubiKit provide their respective platform integrations.
See [OpenPGP packaging](../openpgp/README.md).

## Portable data folder

Desktop data defaults to `data/secrets.dat`, `data/secrets.dat.bck`,
`data/vault.properties` and local convenience-unlock records inside the program
folder. Resolution order: `-Dofflineotp.home`/`OFFLINEOTP_HOME`, packaged launcher,
running jar, then working directory. A read-only location fails explicitly instead
of silently moving the vault to a home directory.

Use a writable folder or an explicit data directory. Move/copy only while the app
is closed. Never let two clients write one vault concurrently; keep a tested backup.
Device-bound convenience grants are not a substitute for the recovery password.

## Reproducibility and checks

```sh
REQUIRE_NATIVE_PACKAGE=1 scripts/packaging/verify-desktop-build.sh
```

Run this on the equipped Linux release host. It compares two independent `distZip`
builds with separate Gradle homes and requires jpackage when the flag is set.
Without the flag, a missing jpackage toolchain skips the native smoke check.
This script does not launch the GUI or certify Windows/macOS package behavior.

`compile-desktop.sh` and Python packaging guards provide headless/stub and source
coverage, not native package execution. `scripts/ci/desktop-ci.sh` combines broader
local gates with a real Gradle distribution build. See the
[release checklist](../../docs/shared-migration-release.md) for candidate evidence.
