# Portable encrypted vault container (version 1)

A full vault backup is a copy of the saved encrypted file. It contains all saved entries,
not app preferences, device credentials, hardware enrolments or automatic-export configuration.
Recovery uses the **vault recovery password**, not a separately chosen export password.
Password-encrypted and OpenPGP **exports** remain independent formats and operations.

## Cross-build portability

Android (all distribution flavors), Desktop and iOS use **one vault-file format**, not
platform-specific backup formats. A current full backup is exactly the saved vault bytes;
manual and automatic full copies do not add another password or wrapper. Copying from any
build to another preserves the recovery password in use when that file was saved. File
extensions are labels, not format identifiers. Password/OpenPGP exports are separate choices.

For manual file placement, completely close the destination app, preserve its existing vault,
and put the copied file at its configured vault path as `secrets.dat`. Use the actual data
directory, not necessarily the executable directory. Android/iOS private-directory restrictions
usually make the restore picker the practical alternative. Do not copy transaction journals,
local credential preferences or hardware/biometric enrollment from another device. This is
snapshot transfer, not live multi-writer synchronization; never replace a file while it is open.

Android recognizes portable files before first-run/setup and derives the unlock key from the
file's authenticated header rather than missing or stale local credential preferences. A
recognized but unsupported/corrupt container must fail closed, not provision a replacement vault.
Desktop and iOS password engines likewise open copied containers without credential sidecars.
Local quick-unlock enrollment is not transferable; retain the recovery password.

Independent Argon2id and PBKDF2 container vectors are consumed by JVM, Swift and Android device
tests. Both JVM and Swift writer tests check the same header layout, authenticated encryption
and recovery path. Native Swift execution and Android device execution still require their
respective toolchains/devices; Linux source/stub checks alone are not native validation.

## Binary contract

All integers are unsigned big-endian 32-bit values, constrained to the bounds below.

| Offset | Length | Value |
| --- | --- | --- |
| 0 | 8 | ASCII `OTPVLT` followed by CR LF |
| 8 | 1 | format version: 1 |
| 9 | 1 | cipher: 1 = AES-GCM, 128-bit tag |
| 10 | 1 | KDF: 1 = Argon2id v1.3; 2 = legacy vault PBKDF2-HMAC-SHA1 |
| 11 | 1 | reserved: 0 |
| 12 | 4 | Argon2 memory KiB; PBKDF2: 0 |
| 16 | 4 | iterations |
| 20 | 4 | Argon2 parallelism; PBKDF2: 0 |
| 24 | 16 | password salt |
| 40 | 12 | random AES-GCM nonce |
| 52 | variable | ciphertext followed by 16-byte authentication tag |

The **entire 40-byte header is GCM associated data**. New random nonce on each save.
The plaintext is the existing nonempty UTF-8 vault JSON document (an empty vault is `[]`).
The complete file is bounded to 16 MiB, including 68 bytes overhead. Unsupported versions,
ciphers, KDFs and reserved values fail closed; recognized containers never fall back to another
format after failure. KDF work limits are checked before deriving a key.

Argon2 uses the existing vault credential derivation: 64 bytes, first 32 for AES, remaining 32
for the historical external verifier (not stored in this file). Existing bounded Argon2 policy
applies, including total memory-pass work. PBKDF2 migration uses the historical 32-byte vault
derivation, first 16 for AES, remaining 16 for the external verifier. Iterations: 1–20,000,000.
These are **vault** KDFs, not the independently encrypted export KDFs.
New-format recovery passwords are nonempty, at most 1024 UTF-16 code units, valid Unicode.

`desktop/src/test/resources/golden/vault-container.json` contains synthetic independent vectors
produced with argon2-cffi/hashlib and cryptography AESGCM, consumed by JVM and Swift tests.
The passwords in that fixture are test data, never production secrets.

## Storage and recovery policy

- New vaults require a recovery password. Biometrics and security keys are local quick unlock,
  not a replacement for portable password recovery.
- Legacy open/save does **not** migrate the file. A device-only vault remains device-only until
  the person explicitly sets password authentication and selects password encryption.
- JVM and Swift engines expose an explicit password-verified migration: authenticate old bytes,
  create and verify the new container, preserve original bytes in `.bck`, then atomically promote.
- Explicit password rekey upgrades a legacy password vault with fresh parameters. Android stages
  the complete container with its existing transaction UUID before credential metadata commit.
  An existing container cannot be downgraded to raw/device-only ciphertext through that path.
- A legacy `.bck` is a rollback copy, **not** a newly portable recovery backup. Keep original
  credential settings until migration has been verified. Copies made before a password change
  still use the old password; changing the live vault does not revoke old copies.
- Container-aware save preserves/authenticates its header rather than trusting external settings.
  The JVM/Swift password engine can recover a container with an empty external settings store.
- Snapshot APIs reject legacy files and authenticate the file before returning exact saved bytes.
  The copy does not serialize live entries, choose a new salt/password or add encryption layers.
- Existing encrypted-restore decoders accept both containers and historical encrypted exports.
  First-run restore still creates a fresh vault under the chosen new recovery password.

## Integration / validation status

Implemented: JVM codec/password engine; Swift mirrored codec/password engine and header-based
vault identity; Android container-aware I/O and journal staging for password setup/rekey/encryption
migration; manual full-copy action in Desktop/Android/iOS backup forms, separate from exports.

The backup forms now expose **Migrate legacy vault and save full copy**, separately from a
plain copy and a new-password export. It verifies the current vault password, preserves the
original encrypted bytes in `.bck`, and migrates without changing the password or entry document.
Pending JVM/Android migration checks permission again before backup and before promotion.
Cancellation does not undo an atomic promotion or destination write already in progress.
Device-only Android vaults must first explicitly select password authentication/encryption;
the migration action refuses to mislabel a device-bound file as portable.

Automatic full copies are a distinct opt-in mode, never a reinterpretation of an existing
password/OpenPGP export configuration. Desktop/iOS use their foreground save-coalesced/periodic
scheduler and vault-encrypted v3 configuration (`mode: vault-copy`, empty password, no recipients
or signing requirement). v1 password and v2 OpenPGP configurations retain their original meaning,
including a paused signing requirement. Full copies use `.offlineotp` and a separate retention
namespace from `.aes`/`.pgp` exports on those hosts. Android's explicit full-copy source uses its
existing save-triggered durable retry/destination/retention path; detached legacy broadcast workers
refuse the new mode. No Android periodic timer or new locked/background authority is introduced.

The current automatic password/OpenPGP modes are still **exports**, not file-copy snapshots.
The full cross-platform migration is not established by source wiring; platform acceptance and
broader parity differences remain tracked separately.

Swift and Android device/runtime gates have not been executed in this sandbox. Source/stub tests
are not a substitute for Xcode/Gradle, interruption/restart, native storage-provider, biometric,
hardware-key and real-device verification. In particular verify Android pending transaction
recovery after each journal/metadata/promotion boundary with a container payload.
