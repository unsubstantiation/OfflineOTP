# Desktop packaging and release

The desktop client is a pure-JVM Swing application. Two Gradle outputs matter for releases:

- `./gradlew :desktop:distZip` (and `installDist`) from the standard `application` plugin — a
  platform-independent distribution with launcher scripts and all dependency jars.
- `./gradlew packageDesktop` — a native, installable app image produced by the JDK's
  [`jpackage`](https://docs.oracle.com/en/java/javase/17/jpackage/packaging-tool-user-guide.pdf).

## Requirements

- A **full JDK 17 or newer**. `jpackage` is not part of a JRE and is absent from stripped
  runtimes; the `packageDesktop` task detects this and skips itself rather than failing the build.
- Platform installers are produced per operating system: run the task on Windows for `.msi`/`.exe`,
  macOS for `.pkg`/`.app`, and Linux for `.deb`/`.rpm`. The default task builds an `app-image`
  (no packager-specific toolchain required); pass `--type` through project properties when
  extending it to native installers.
- Dependencies resolve from Maven Central: `pgpainless-core` (with BouncyCastle), zxing-core and
  the yubikit PC/SC module (with raw-HID `hid4java` excluded).

## Reproducible builds

`verify-desktop-build.sh` builds `:desktop:distZip` twice from independent source trees and
Gradle homes with all caches disabled, then compares the archives byte-for-byte. The build
configures `preserveFileTimestamps = false` and `reproducibleFileOrder = true` on every archive
task, and the checked-in golden interop blobs under `desktop/src/test/resources/golden/` are
themselves deterministic. Run it on a real CI host with network access:

```
scripts/packaging/verify-desktop-build.sh
```

## Continuous integration

The Windows and Debian GitHub workflows build separate x64 jpackage app images and upload them
as workflow artifacts with SHA-256 checksums. Debian builds inside a Debian 12 container. The Android
workflow produces an F-Droid debug APK, not a signed production release. These workflows do not
publish releases or perform device tests. The images are unsigned and are not `.msi` or `.deb`
installers. `scripts/ci/desktop-ci.sh` remains available for the broader sandbox and desktop gates.

## What the no-network sandbox verifies instead

Gradle cannot run in the offline sandbox, so release packaging is structurally guarded there:

- `scripts/kotlin-sandbox/compile-desktop.sh` compiles every desktop source (including the
  jpackage/PGP/yubikit/zxing adapters) against narrow compile-only stubs and runs the headless
  tests under `-Djava.awt.headless=true`.
- `scripts/tests/test_desktop_packaging.py` pins the Gradle packaging configuration, the
  reproducibility script and the CI script so the release wiring cannot silently disappear.
