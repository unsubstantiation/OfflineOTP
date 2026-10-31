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

## Application icons

The Swing window loads its icon from `desktop/src/main/resources/offlineotp.png`, which is also
used by jpackage on Linux. Windows jpackage uses `desktop/packaging/offlineotp.ico`, containing
multiple sizes for Explorer and the launcher. Both are generated from the same approved artwork
as the Android icons; regenerate them with `python scripts/branding/generate_icons.py` (Pillow
required). The app images are portable archives, not system-installed desktop entries; Linux
menu or dock integration depends on the desktop environment and how the app is launched.

## Portable data folder

Both clients' desktop builds keep their data beside the program: `data/secrets.dat`, `data/
secrets.dat.bck`, `data/vault.properties` and the security-key unlock record live in a `data`
folder inside the app image (or the `installDist`/`distZip` folder). Nothing is written to the
user's home directory, so the whole folder can be copied to a USB stick or another machine and the
vault comes with it. The folder is resolved from `-Dofflineotp.home`/`OFFLINEOTP_HOME` first, then
from the jpackage launcher (`jpackage.app-path`), then from the running jar's location, and finally
from the working directory. Because an installation under `/usr/lib`, `/opt` or `Program Files` is
normally read-only, run the app image from a writable folder (which is what the CI artifacts are:
unpacked archives) or point `-Dofflineotp.home` at one.

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
