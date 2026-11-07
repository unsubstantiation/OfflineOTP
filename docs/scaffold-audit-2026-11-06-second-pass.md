# Scaffold audit — 2026-11-06 (second pass)

The first pass ([incomplete-feature-audit](incomplete-feature-audit-2026-11-06.md)) closed the
unified OpenPGP backend. This pass asked a narrower question: **what code exists, is packaged, and
is never reached by any user-facing path?** Three genuine scaffolds turned up, plus one at-rest
security gap that the dead code had been hiding.

A scaffold here means something stricter than "a feature we could add". It means types, adapters,
tests or build output already ship, but no production caller can reach them. Optional product gaps
(paper backups, preferences export, backup pruning, Nitrokey native OTP, browser-password
re-export) are **not** scaffolds: they have no half-built code claiming to exist.

## 1. Android: the in-app OpenPGP engine had no caller — completed

`RpgpOpenPgpPort`, the UniFFI/JNA bindings and the CI-built per-ABI `jniLibs` all shipped, but
`BackupActivity` only ever drove an external OpenKeychain provider. There was no "built-in engine"
entry in `preferences.xml`, so the UI had never even expressed the intent.

Now reachable, alongside the unchanged provider flow:

- **Settings → OpenPGP engine** chooses `Separate OpenPGP app` (default) or `In this app (key files)`.
  The default keeps every existing install on its configured provider, so nothing silently switches
  engine and old files still open. The two engines' preferences are shown disjointly; stored values
  are kept so switching back restores the configuration.
- **Recipient key files** and **Secret key file** are picked through the Storage Access Framework.
  Only *read* access is requested and persisted — the app never asks to write to a user's key files.
  Only bounded `content:` URIs are accepted; a raw path is never read.
- **Export** encrypts the vault document to the selected recipient certificates through the shared
  `OpenPgpBackupCodec`, so the bounds, recipient rules and blank-output rejection are the same ones
  the password path and the desktop use. `OpenPgpKeyFileBackupTask` owns a validated entry snapshot
  and destroys it on every exit path, mirroring `EncryptedBackupTask`.
- **Restore** asks whether the secret key is passphrase-protected. An unprotected key is an explicit
  answer rather than a forced empty password. The passphrase is never stored: the task owns the
  `CharArray` and clears it, and the secret-key bytes are zeroed, on every exit path. Wrong key,
  wrong passphrase and corrupt message report the same sentence, so the toast is not an oracle.
- **Availability** gates both buttons with a specific reason (engine missing / no recipients / no
  secret key), the same disabled-buttons-plus-warning behaviour the provider mode already had. The
  native library is probed once, not on every redraw.

Because both modes emit standard OpenPGP messages, a backup written in one restores in the other.

## 2. Desktop: at-rest files were world-readable — fixed

This is the finding that mattered most, and it directly serves the requirement that the vault be
safe from other local apps **before and after** unlock.

Android gets private storage from the platform: `0700` directory, files readable only by its own
UID. The desktop never replicated it. `DesktopStorage.open` used plain `mkdirs()`, and both atomic
writers used `FileOutputStream`, so under the usual `022` umask a portable install created a `0755`
data directory holding `0644` `secrets.dat`, `secrets.dat.bck`, `vault.properties` and
`hardware-unlock.b64` — readable by every local account. The vault is encrypted, but a world-readable
ciphertext is an offline password-guessing target and leaks the KDF parameters.

`PrivateFiles` now applies the Android-equivalent modes:

- The data directory is created `0700`; staging files are created `0600` **before the first byte**,
  so a document is never briefly readable while it is being written.
- Both atomic writers restrict the promoted target, because a non-atomic promotion can leave the
  destination's older, looser mode in place.
- Opening the vault tightens the directory and the known files, so an install created by an older
  build is repaired rather than left loose.

Two deliberate limits, stated rather than hidden:

- **Tightening only ever removes access.** Group/other bits are cleared; the owner's own bits are
  left exactly as they were. A directory the user made read-only stays read-only, and the vault
  reports that it cannot write instead of chmod-ing past the user's decision or a read-only mount.
  (The first attempt granted owner-write back and broke nine fault-injection tests — those tests
  were right.)
- **Non-POSIX file systems** (Windows/NTFS) reject POSIX modes. The JDK owner-only flags are the
  closest equivalent and do not rewrite an inherited ACL, so on those systems the directory's
  inherited permissions remain the real boundary.

## 3. Desktop: `RsaKeyWrapPort` / `KeyWrapPort` — retired, not wired

The port was complete, tested and guard-pinned, but never constructed. An earlier framing in this
session called it a *backup*-key wrapper; that was wrong and is corrected here: it wraps the
**vault** key (Android's `otp.key`), which is what enables non-password unlock.

Wiring it would have meant desktop quick unlock via a software RSA key stored **beside the vault**.
Any process running as the user could then read both and unlock without the password — strictly
weaker than Android, where the wrapping key never leaves the hardware KeyStore. That contradicts
the stated requirement, so the port and its core interface are deleted and the useful part (owner-only
writes) is promoted into shared storage as item 2. A Python guard now asserts the port does not come
back, and explains why.

## 4. iOS: availability helpers had no callers — completed

`RustOpenPgpPort.isBackendAvailable` and `OpenPgpAvailability` existed but nothing consulted them,
while `BackupView.openPgpSection` always enabled both OpenPGP buttons. On a build without the
XCFramework the user could pick files and only then hit `backendUnavailable`.

`OpenPgpPort` now carries `isAvailable` (defaulted to `true` so injected in-process ports keep
working), `VaultStore` exposes `openPgpAvailability`, and the two buttons are disabled with an
explanatory footer when the engine is absent — parity with Android.

## Also corrected

`docs/client-parity-2026-11-02.md` claimed multi-tag filtering had no desktop equivalent. That was
stale: `TagDialog.promptFilter` is a multi-select `OR`/`AND`/`SINGLE` panel wired through
`MainWindow` to `DesktopVaultState.applyTagFilter`. The doc now says so and names what is actually
still Android-only (the search-includes-tag preference).

## Verification

Local gates, all after the change:

| Gate | Result |
| --- | --- |
| `python3 -m unittest discover -s scripts/tests` | 411 tests, OK, 0 skipped |
| `scripts/run-jvm-tests.sh` | 333 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/run.sh` | all sandbox checks passed |
| `scripts/kotlin-sandbox/compile-desktop.sh` | 188 tests, 176 passed, 0 failed, 12 skipped |
| `scripts/kotlin-sandbox/compile-core.sh` | 312 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 20 androidTest files, COMPILE OK |
| `scripts/kotlin-sandbox/generate-r-stub.py --check` | R stub up to date |

The Python suite reports 0 skipped here because the bootstrapped toolchain is on the environment;
without it the two end-to-end JVM-runner tests skip themselves.

New coverage: `OpenPgpKeyFilePolicyTest` (8 core tests), `PrivateFilesTest` (8 desktop tests, POSIX
mode assertions skipped rather than faked on non-POSIX hosts), `OpenPgpAvailabilityTests` (6 Swift
tests), `test_openpgp_key_file_mode.py` (8 guards), a sandbox check for key-file availability and the
unavailable-backend refusal, and executed `Settings` checks for the new accessors.

### Verification limits

There is still no Android SDK, Gradle, Xcode or native Rust toolchain in this environment.

- The **Swift** changes and `OpenPgpAvailabilityTests` are **not compiled or run locally**; they are
  CI-only. Python guards pin the wiring in the meantime.
- The **Android** UI is compile-verified against stubs, not run: resource inflation, the SAF picker
  round trip, the passphrase dialogs and the real `jniLibs` load need a device or emulator.
- The in-app engine's actual cryptography remains a CI gate (`cargo test`); locally the adapter is
  exercised on its explicit *unavailable* path, which the sandbox now asserts refuses to encrypt
  rather than silently falling back.
- POSIX permission modes are asserted on this Linux host. The Windows/NTFS fallback is documented
  above and not verified here.
