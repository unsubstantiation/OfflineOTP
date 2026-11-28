# iOS architecture and compatibility contract

The app has shared Kotlin/Compose presentation but still uses substantial Swift engine/host
code. The [Kotlin cutover](ios-kotlin-cutover.md) is incomplete. Only a narrow bridge to the
official YubiKit SDK is intended to remain Swift. The last reported iOS build failed;
subsequent changes await native validation. Minimum deployment target: **iOS 17**.

`:core` uses JVM APIs and cannot simply be linked into Kotlin/Native. Portable logic is
being extracted to `shared-domain`; platform crypto/storage services need Kotlin/Native
adapters. This is not a reason to maintain a second Swift application engine permanently.
Unsupported/future entry fields must be **preserved verbatim** where the codec contract
requires it, or rejected explicitly; never silently discarded.

## The interop contract

The parameters below describe existing legacy compatibility and exported documents. New
self-contained vaults use the [portable container](portable-vault-container.md), not the
headerless legacy file described in section 1. Source guards cover selected constants;
they are not a complete format proof or crypto audit.

### 1. Legacy settings-based vault: `data/secrets.dat`

| Property | Value |
| --- | --- |
| Plaintext | the compact `VaultJson` document: a UTF-8 JSON array of entries |
| Key derivation (writer) | **Argon2id** v1.3 (`0x13`, RFC 9106), **512-bit** (64-byte) output, **16-byte** salt, **65536** KiB (64 MiB) memory, **3** iterations, **4** lanes |
| Legacy reader compatibility | when `auth_kdf` is unset or `pbkdf2`, reads legacy PBKDF2-HMAC-**SHA1** (**256-bit** output, **16-byte** salt; legacy writer floor **30000** `PBKDF2_MIN_AUTH_ITERATIONS`, reader ceiling 1 – 20000000) |
| AES key | the **first 32 bytes** (256-bit AES-256-GCM key) of the 64-byte Argon2id output (legacy PBKDF2: first 16 bytes / AES-128-GCM) |
| Verifier | the **last 32 bytes** (upper half, 256 bits; legacy PBKDF2: last 16 bytes), stored Base64 URL-safe |
| Cipher | AES-256-**GCM** (legacy PBKDF2: AES-128-GCM), **128-bit** tag, **96-bit** nonce |
| Framing | `nonce (12 bytes) ‖ ciphertext ‖ tag (16 bytes)`; nothing else, no header |

JSON readers are order-independent. Preserve supported fields and opaque extensions; do not
promise byte-identical JSON serialization across platform libraries. Exact full backups copy
the saved encrypted file rather than reserializing it.

Settings beside the vault (Android `SharedPreferences`, desktop `data/vault.properties`, iOS
`data/vault.plist`) hold `auth_method=password`, `encryption=password`, `auth_kdf=argon2id`
(omitted or `pbkdf2` on legacy vaults), `auth_salt` (Base64 URL-safe, 16 bytes),
`auth_memory_kib` (65536), `auth_iterations` (int: 3 for Argon2id, ≥ 30000 for legacy PBKDF2),
`auth_parallelism` (4) and `auth_credentials` (Base64 URL-safe verifier, compared in constant
time). The verifier is a fast pre-check; the GCM tag is the real gate.

### 2. Encrypted backup: `otp_accounts_<timestamp>.json.aes`

| Property | Value |
| --- | --- |
| Current framing (Argon2id v1) | `"OOTP" (4 bytes) ‖ 0x01 ‖ 4-byte big-endian memoryKiB ‖ 4-byte big-endian iterations ‖ 1-byte parallelism ‖ 16-byte salt ‖ 12-byte nonce ‖ ciphertext ‖ 16-byte tag` (30-byte KDF header) |
| Current key | **Argon2id** v1.3 (`65536` KiB, `t = 3`, `p = 4`), **all 32 bytes** (no split — unlike the vault key) |
| Legacy framing (read-only) | `4-byte big-endian iterations ‖ 12-byte salt ‖ 12-byte nonce ‖ ciphertext ‖ 16-byte tag` (PBKDF2-HMAC-SHA1, all 32 bytes; legacy writer range 140000 – 160000, reader accepts 1 – 1000000) |
| Plaintext | the same UTF-8 `VaultJson` document |

### 3. Entry JSON

| Field | Notes |
| --- | --- |
| `type` | `TOTP`, `HOTP`, `MOTP`, `STEAM`, `NOTE`, `SSH_KEY`, `PASSWORD`, `PASSWORD_ARCHIVE`, `EMAIL` |
| `secret` | Kind-specific encoded payload; entry/material codecs define its representation |
| `issuer`, `label`, `digits`, `algorithm`, `thumbnail` | `algorithm` is `SHA1`, `SHA256` or `SHA512` |
| `period` (TOTP/Steam), `counter` (HOTP), `pin` (mOTP) | written only for the kinds that use them |
| `last_used`, `used_frequency` | usage ordering |
| `tags` | array of strings |
| `details_visible`, `ssh_host`, `ssh_port`, `ssh_username`, `ssh_password` | optional; omitted when unset, so an untouched entry round-trips byte-for-byte |

### 4. Bounds a reader must enforce

| Bound | Value | Source |
| --- | --- | --- |
| Entries per vault | 2000 | `InputPolicy.MAX_ENTRIES` |
| Note length | 1 – 200 Unicode code points | `InputPolicy.MAX_NOTE_CHARACTERS` |
| OTP secret | ≤ 2048 bytes | `InputPolicy.MAX_SECRET_BYTES` |
| SSH key material | ≤ 65544 bytes (`2 * 32 KiB + 8`) | `SshKeyMaterial.MAX_ENCODED_BYTES` |
| Password archive material | ≤ 4 MiB | `PasswordMaterial.MAX_BYTES` |
| Email account material | ≤ 128 KiB | `EmailMaterial.MAX_BYTES` |
| Free text (issuer, label) | ≤ 1024 characters | `InputPolicy.MAX_TEXT_CHARS` |
| Tags | ≤ 32 tags, ≤ 128 characters each | `InputPolicy.MAX_TAGS`, `MAX_TAG_CHARS` |
| Digits | 1 – 9 | `InputPolicy.tokenParameters` |
| Period | 1 – 86400 seconds | `InputPolicy.MAX_PERIOD_SECONDS` |
| HOTP counter | ≥ 0 and never `Long.MAX_VALUE` | `InputPolicy.tokenParameters` |
| Steam digits | exactly 5 | `TokenCalculator.STEAM_DEFAULT_DIGITS` |

### 5. Tokens

- **TOTP/HOTP**: RFC 4226 dynamic truncation over HMAC-SHA1/256/512 of the 8-byte big-endian time
  step or counter; `offset = last byte & 0x0f`; 31-bit result; `code = result % 10^digits`.
- **Steam**: the same TOTP value, but each digit is `alphabet[value % 26]` over the 26-character
  alphabet `23456789BCDFGHJKMNPQRTVWXY` (no `L`, no vowels), with `value /= 26` per digit and the
  least significant character produced first.
- **mOTP**: `MD5("<epoch/10 + offset><secret><pin>")` in hex, first 6 characters; a 10-second step
  regardless of the stored period, and `"PINREQ"` is shown instead of a code while the PIN is empty.

## Conformance corpus

`desktop/src/test/resources/golden/` already contains fixed-vector blobs with the password
`golden-interop-password`:

| File | What the iOS test must do with it |
| --- | --- |
| `argon2id-secrets.dat` + `argon2id-vault.properties` | unlock Argon2id vault (`65536` KiB, `t = 3`, `p = 4`), decode, and verify verifier parity |
| `argon2id-otp_accounts.json.aes` | decrypt Argon2id v1 backup to `expected-vault.json` and re-seal deterministically |
| `secrets.dat` + `vault.properties` | unlock legacy PBKDF2 vault, decode, and re-encode to exactly `expected-vault.json` |
| `expected-vault.json` | compare bytes after a round-trip, not just parsed values |
| `otp_accounts.json.aes` | decrypt legacy PBKDF2 backup to the same document, then re-seal deterministically |
| `desktop-note-export.json.aes`, `desktop-ssh-export.json.aes`, `desktop-privacy-export.json.aes` | open a real backup containing a Unicode note, a >5 KiB SSH key and hidden/revealed entries |

Legacy Swift tests and shared/JVM tests consume these fixtures. The standalone Swift suite
is no longer an iOS CI gate. Golden vectors cover known cases; they neither prove all inputs
safe nor replace implementation review or real target execution.


## Build and validation

The manual workflow builds `rust/offlineotp-openpgp` (rpgp 0.21 + UniFFI), the Compose
XCFramework and unsigned device app. Package resolution must see the native framework;
`OFFLINEOTP_OPENPGP_NATIVE` otherwise remains unset and the Swift port can report
`backendUnavailable`. IPA checks reject a release missing native OpenPGP evidence.

The shared Rust backend replaces the earlier split providers, but packaging a common engine
does not prove every platform's integration. See [OpenPGP behavior](openpgp-client-parity.md).
Standalone legacy SwiftPM tests are not run by the workflow; shared Kotlin tests and the
actual Xcode dependency graph are the current build gates. See [build commands](../ios/README.md).

## Face ID, and why it does not weaken the vault

Biometric unlock is an optional local shortcut, not a different vault encryption format.
The recovery password still opens the portable vault. Enrollment requires password
confirmation. The Keychain item uses `kSecAccessControlBiometryCurrentSet` with
`WhenUnlockedThisDeviceOnly`, is not synchronized, and is invalidated by enrollment changes.
Cancellation/lockout returns to password entry, not a silent alternate authorization path.
Android also supports optional local biometrics; desktop has no equivalent native shortcut.

The app also declares camera/NFC usage descriptions; Face ID is not its only native permission.
These source rules still need native lifecycle, cancellation and device verification on iOS.

## Security and platform boundaries

- Use bounded parsing and KDF limits before expensive work. Cryptographic authentication,
  not a verifier shortcut alone, authorizes decrypted content.
- Keep filesystem/provider failures explicit; do not overwrite the only recoverable file.
- Reject stale callbacks after lock, background, cancellation or generation replacement.
- Backups exclude hardware-held accounts and device-bound convenience unlock grants.
- Test copy-based recovery, legacy restore, unknown fields and each entry kind on the
  actual target. Do not infer parity from JVM/stub compilation.

## Non-goals for v1

CI does not sign or publish to the App Store/TestFlight. Shared UI does not add hardware
protocol support a device/SDK lacks. Zeroization is best-effort; no claim is made that
immutable strings, native copies or flash/COW storage can be physically erased.
