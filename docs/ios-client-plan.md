# Native iOS client — plan

Status: **there is an app, and it does the daily job and moves data in and out** (`ios/`,
milestones M0–M4 — see [What exists today](#what-exists-today)). It opens or creates a vault, shows
the five tabs with a live search, the tag filter and the five orders, reveals an entry, generates its
code, copies it with an expiry, steps an HOTP counter, adds, edits and deletes entries, writes and
restores encrypted backups, reads a legacy plain-text backup, imports `otpauth://` links and QR
images, and shows an entry as a QR code — in the shared Dark Forest theme. It has no Face ID yet,
and it is not signed or submitted anywhere: a simulator build is the only artifact. `:core` still cannot be reused (see
[Why not Kotlin Multiplatform](#why-not-kotlin-multiplatform)). This document is the contract a
SwiftUI client has to satisfy, so that it is a fourth *reader and writer* of the same vault rather
than a fourth format.

The desktop client is a JVM Swing application and the phone client is Android; both were made to
follow one rule set in `docs/client-parity-2026-11-02.md`. An iOS client joins that set under the
same rule: **the vault bytes are the API**. Anything it cannot model it must preserve, never drop.

## Why not Kotlin Multiplatform

`:core` is portable Kotlin, not Kotlin/Native. It uses `javax.crypto.Cipher`/`SecretKeySpec`,
`java.security.SecureRandom`/`MessageDigest`/`SecretKeyFactory` (PBKDF2), `org.json`,
`java.nio.charset` with `CodingErrorAction` and `java.util.zip`. Kotlin/Native has none of these, so
the module does not compile for `iosArm64` today. Converting it would mean an `expect`/`actual`
crypto layer over CommonCrypto/CryptoKit, a different JSON library and new golden vectors — a
prerequisite project in its own right, not a build step.

The Swift client therefore reimplements the *formats* below and proves itself against the vectors
this repository already ships. Duplication is accepted here because the duplication is a format, not
a rule: the parser is pinned by fixed bytes, not by review.

## The interop contract

Every constant below is enforced by `:core` and by a Python guard
(`scripts/tests/test_ios_plan.py`), so this table cannot silently drift from the code.

### 1. The vault: `data/secrets.dat`

| Property | Value |
| --- | --- |
| Plaintext | the compact `VaultJson` document: a UTF-8 JSON array of entries |
| Key derivation (writer) | **Argon2id** v1.3 (`0x13`, RFC 9106), **512-bit** (64-byte) output, **16-byte** salt, **65536** KiB (64 MiB) memory, **3** iterations, **4** lanes |
| Legacy reader compatibility | when `auth_kdf` is unset or `pbkdf2`, reads legacy PBKDF2-HMAC-**SHA1** (**256-bit** output, **16-byte** salt; legacy writer floor **30000** `PBKDF2_MIN_AUTH_ITERATIONS`, reader ceiling 1 – 20000000) |
| AES key | the **first 32 bytes** (256-bit AES-256-GCM key) of the 64-byte Argon2id output (legacy PBKDF2: first 16 bytes / AES-128-GCM) |
| Verifier | the **last 32 bytes** (upper half, 256 bits; legacy PBKDF2: last 16 bytes), stored Base64 URL-safe |
| Cipher | AES-256-**GCM** (legacy PBKDF2: AES-128-GCM), **128-bit** tag, **96-bit** nonce |
| Framing | `nonce (12 bytes) ‖ ciphertext ‖ tag (16 bytes)`; nothing else, no header |

**Field order in the document.** The stored field order is part of the format, not an accident:
`EntryJson.serialize` appends `secret, issuer, label, digits, type, algorithm, thumbnail, last_used,
used_frequency`, then the one kind-specific field (`period`, `counter` or `pin`), then `tags`,
`details_visible` and the optional `ssh_*` fields. Android's `JSONObject` keeps insertion order and
writes exactly that. The JVM's `org.json` does *not* — it stores keys in a `HashMap`, so the desktop
client writes the same fields in a different order, which is why the existing JUnit test asserts the
field *set* rather than the text (see the comment in `EntryJsonTest`). Both spellings open
everywhere, because the reader is order-independent.

The iOS client writes the canonical order above, so a document it re-encodes is byte-identical to the
one the phone wrote — that is what `expected-vault.json` pins, and what the round-trip test compares.
Byte identity with a *desktop*-written vault is not a goal and could not be, short of reimplementing
Java's `HashMap` iteration.

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
| `secret` | Base32 for the OTP kinds; raw text for mOTP; note text for `NOTE`; framed key material for `SSH_KEY`; structured for the credential kinds |
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

A Swift test target reads those files **from the repository path** (no copies), so a format change
on either side breaks both suites at once. The corpus is the whole reason this project is feasible:
the crypto never has to be reviewed by eye.

## Milestones

| # | Scope | Exit criteria |
| --- | --- | --- |
| **M0** ✅ | SPM package (`VaultCrypto`, `Entry`, `VaultDocument`, `EncryptedBackup`, `PasswordVault`, `TokenCalculator`), test target wired to the golden fixtures, `.github/workflows/ios.yml` | Fixture tests green in CI; the package also compiles for the iOS simulator. The Xcode *app* target arrives with the UI at M3 |
| **M1** ✅ (except device interop) | Unlock `secrets.dat`, decode entries, re-encode the shared document byte for byte, create a vault, save, back up, restore, reject a wrong password | Every vault fixture round-trips exactly; a vault this client creates reopens on a second instance. A vault created on Android opening here is still proved by the golden fixture rather than by a device |
| **M1.5** ✅ | An Xcode project generated from `ios/project.yml`, an app target with a SwiftUI shell (unlock, list, codes, copy with expiry, lock), and app unit tests available for local runs | The app compiles for iOS devices on every `master` or dispatched workflow run, which publishes one unsigned `OfflineOTP-ios-unsigned.ipa` |
| **M2** ✅ | TOTP/HOTP/Steam/mOTP, countdown, copy, HOTP step, use tracking | RFC 4226/6238 vectors plus the codebase's own token vectors match digit-for-digit; `EntryUsageTests` pins the stamp, the counter and its ceiling against `EntryUsage` in `:core` |
| **M3** ✅ | List with the five categories, search, tag filter, the five sort modes, reveal, add/edit/delete an entry | Same vault shows the same order and the same tabs as the other two clients: the projection mirrors `EntryListPresentation`, and `scripts/tests/test_ios_app.py` compares the tab set, the titles, the five order names and the tag rules with `:core` |
| **M4** ✅ | Encrypted backup export/import, plain-text import, `otpauth://` and QR import, QR display | A desktop export restores on iOS and an iOS export restores on Android: the header, the merge rule, the file names, the parser bounds and the parser's error names are all pinned against `:core` by `scripts/tests/test_ios_app.py`, and `OtpUriTests`/`BackupRestoreTests` pin the round trips |
| **M5** 🔄 | Google Authenticator export import ✅, Face ID unlock ✅, privacy cover ✅, VoiceOver and target-size pass ✅, OpenPGP backups (rpgp + UniFFI, 5/5 scaffolded — see below), TestFlight | App passes review; no plaintext secret in logs, files or pasteboard beyond the expiry window |

Estimated shape: M0–M1 about a week each, M2 about a week, M3 the largest at one to two, M4 about a
week, M5 about a week. Nothing here is on the critical path of the existing clients. M4 and M5 are
what remains.

## What exists today

| Path | What it is |
| --- | --- |
| `ios/Package.swift` | The Swift package, iOS 17 / macOS 14, one library and one test target |
| `ios/Sources/OfflineOTPKit/Argon2id.swift` | Pure-Swift RFC 7693 BLAKE2b and RFC 9106 Argon2id v1.3, byte-identical to `:core` |
| `ios/Sources/OfflineOTPKit/VaultCrypto.swift` | Argon2id v1.3 + legacy PBKDF2-HMAC-SHA1 (CommonCrypto), AES-GCM through CryptoKit, the credential split, Base64 URL-safe, constant-time compare |
| `ios/Sources/OfflineOTPKit/PasswordVault.swift` | The vault engine over `AtomicByteStore` / `KeyValueSettings` ports, plus a file-backed store and a plist settings store |
| `ios/Sources/OfflineOTPKit/VaultDocument.swift`, `Entry.swift` | The entry document: parse with the shared bounds, write in the canonical field order, keep what this build cannot display |
| `ios/Sources/OfflineOTPKit/EncryptedBackup.swift` | The backup header, seal and open |
| `ios/Sources/OfflineOTPKit/TokenCalculator.swift`, `Base32.swift` | TOTP/HOTP/Steam/mOTP and the seed encoding |
| `ios/Sources/OfflineOTPKit/EntryListProjection.swift` | Tabs, live search, the tag rule and the five orders — the port of `EntryListPresentation`, `EntryList` sorting and `EntryTagFilter` |
| `ios/Sources/OfflineOTPKit/EntryUsage.swift` | The use stamp, the use counter and the HOTP step with its ceiling — the port of `EntryUsage` |
| `ios/Sources/OfflineOTPKit/EntryDraft.swift` | Turning a form into an entry: which kinds, what a blank timing field means, the shared validation — the port of `EntryDraft` |
| `ios/Sources/OfflineOTPKit/OtpUri.swift` | `otpauth://` in both directions — the port of `OtpUriParser` and `Entry.toUriString`, with the error names of `OtpParseError`, plus the payload classification of `QrImport` |
| `ios/Sources/OfflineOTPKit/ThumbnailCatalog.swift` | The persisted logo names and the issuer matching of `ThumbnailCatalog` in `:core` — generated from it by `scripts/ios/generate-thumbnail-catalog.py`, so an entry added or imported here keeps the logo the other clients would have given it |
| `ios/Sources/OfflineOTPKit/BackupRestore.swift` | Dated backup names (`BackupFileNames`), strict plain-text decoding (`PlainTextBackupDecoder`), and the merge/replace rule (`VaultRestoreMerge`) |
| `ios/Sources/OfflineOTPKit/MigrationImport.swift` | Google Authenticator exports: the bounded protobuf reader of `GoogleAuthenticatorMigration`, the session-bound `MigrationBatchCollector`, and Android's `google_import_*` wording |
| `ios/App/Sources/BiometricUnlock.swift` | The Face ID / Touch ID quick unlock: the `BiometricKeyStore` seam, the Keychain item (`kSecAccessControlBiometryCurrentSet`, `WhenUnlockedThisDeviceOnly`, not synchronizable) that holds the derived vault key, and its sentences |
| `ios/Tests/OfflineOTPKitTests/` | The fixture tests, the vault round-trip tests, the token vectors, and the projection, usage, draft, URI and restore rules |
| `ios/project.yml` | The Xcode project definition (XcodeGen). The generated `.xcodeproj` is not committed |
| `ios/App/Sources/` | The app: `OfflineOTPApp` (entry point, `scenePhase` cover), `UnlockView`, `EntryListView` (tabs, search, tag chips, sort menu, rows, row menu), `EntryEditorView`, `BackupView` (export, restore, link and image import), `MigrationImportView` (an export's progress and confirmation), `SettingsView` (the Face ID switch and the vault folder), `QrCodeView` (an entry as a QR code), `QrImageReader` (Vision), `VaultStore`, `CodeFormat`, `Clipboard`, `ForestPalette` |
| `ios/App/Tests/` | The app's unit tests: grouping and countdown arithmetic, the list rules reaching the files (tabs, search, tags, orders, reveal, counter step, add/edit/delete), the backup and restore round trips through `VaultStore`, and a real create/save/reopen cycle |
| `scripts/ios/generate-token-vectors.py` | Generates the token vectors after checking itself against the published RFC 4226/6238 values |
| `scripts/ios/generate-thumbnail-catalog.py` | Generates `ThumbnailCatalog.swift` from the Kotlin catalog; `ios.yml` and `scripts/tests/test_ios_app.py` both fail when the copy is stale |
| `scripts/tests/test_ios_package.py`, `test_ios_app.py`, `test_ios_plan.py` | Keep every restated constant, the field order, the palette, the offline promise and this document honest |

## What the iOS build action does

`ios.yml` exists now that there is a project to build — a workflow before that could only fail or sit
permanently skipped, which is indistinguishable from coverage that does not exist. It has two jobs.
`openpgp-host` runs the Rust crate's host tests and release build on `ubuntu-24.04` in parallel, so
that coverage does not spend the Apple job's budget. The Apple job runs on `runs-on: macos-15`: the
arm64 image, as `macos-14` was until GitHub retired that configuration — jobs on it are cancelled at
0 steps with *The configuration 'macos-14-arm64-us-default' is not supported* — running macOS 15.8.2
with Xcode 16.4 (`macos-15-intel` is the x86_64 variant; the app is Apple silicon only, like the
desktop build). Its hard budget is **10 minutes**:

1. `scripts/openpgp/build-ios-xcframework.sh --arm64-only`, which builds the arm64 device, arm64
   simulator, and arm64 macOS slices consumed by this Apple-silicon workflow;
2. `scripts/ios/generate-token-vectors.py` and `scripts/ios/generate-thumbnail-catalog.py`, which
   refuse a stale generated file;
3. `swift build -c release --build-tests -Xswiftc -enable-testing` and
   `swift test -c release --parallel -Xswiftc -enable-testing`, which run the format and fixture
   tests on the macOS host with the production KDF parameters but optimized code;
4. `brew install xcodegen`, `xcodegen generate`, then `xcodebuild build` for
   `-configuration Release -destination 'generic/platform=iOS'` with
   `ARCHS=arm64 CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO` — **no signing
   identity at all**. This is the step that proves the app and its package compile for iOS. There is
   no simulator build, no separate kit-only build, and no simulator boot;
5. the unsigned `.ipa`: the device `.app` is copied with `ditto` under `Payload/OfflineOTP.app`,
   zipped, then unpacked again and checked to be an `arm64` `IOS` Mach-O
   (`DTPlatformName = iphoneos`, `CFBundleExecutable = OfflineOTP`);
6. **one artifact**, `OfflineOTP-ios-unsigned`, containing only `OfflineOTP-ios-unsigned.ipa`, ready
   for sideloaders or local signing. No simulator bundle, no source archive, no checksum manifest and
   no log artifact: the `.ipa` is the deliverable, and everything else was noise beside it.

Build diagnostics are printed as run annotations by steps gated on `if: ${{ !success() }}`, so a green
run reports nothing and no log file is uploaded. The Rust step matches only real diagnostics
(`^error:`, `^error[E0308]:`, `^test result: FAILED`), not the word *error* anywhere: `cargo --verbose`
echoes `Running ... rustc ... --error-format=json ...`, and a loose grep turned ten of those command
lines into ten `error` annotations on a run that had passed. Cargo now runs without `--verbose` and
with `CARGO_TERM_COLOR=never`, and the annotation loops collect their matches before limiting them
instead of piping `grep` into `head`, which had also produced `grep: write error: Broken pipe`.

The simulator XCTest step is deliberately absent: in the cancelled 30-minute run the app compiled
in 41 seconds, then simulator app tests consumed the remaining ~17 minutes. Dropping the simulator
build too took the measured Apple job from 518 s to a projected ~470 s, inside the ten-minute budget.
The generated `OfflineOTPAppTests` target remains available for local/manual runs.

Device and TestFlight signing stay manual, exactly as releases do on the other platforms: an
**`ios-release`** environment would hold the distribution certificate (`.p12` plus password), the
provisioning profile and an App Store Connect API key (key ID, issuer ID, `.p8`), and the workflow
would stop at `xcodebuild archive` → `-exportArchive`. It would not create a release note.

## What the app does, and what it deliberately does not

Working: create a vault, unlock with the password, see the five tabs (OTP, Notes, SSH Keys,
Passwords, Email) with a count on each, search labels, issuers and tags live, filter by tags with
any/all/one and the tagless switch, order the list five ways (Unsorted, Issuer, Label, Last used, Most
used — persisted beside the vault, never inside it), reveal an entry and copy its code with a
30-second pasteboard expiry, step an HOTP counter, add a TOTP/HOTP/Steam/mOTP entry or a secure
note, edit one, delete one behind a confirmation that names it, turn on Face ID in **Settings**, lock,
and cover the content when the scene leaves the foreground.

Moving data: write an encrypted backup under a dated name (`otp_accounts_<yyyy-MM-dd_HH-mm-ss>.json.aes`,
the same shape the phone and the desktop write), restore an encrypted or a legacy plain-text backup —
merged by default, with the **Replace existing entries** switch — paste an `otpauth://` link, import
one from a QR image picked from the photo library, read a Google Authenticator export code by code
and import its accounts once the last code is in, and show an entry as a QR code for another device
to scan.

Three rules carried over verbatim from the other clients, because they are what makes the same vault
behave the same way:

- **A concealed entry shows no code and offers no copy.** `details_visible` is vault data, so a
  reveal is written and survives a reopen; a failed write leaves the entry concealed rather than
  showing details the file does not agree with. Issuer and label stay visible in either state — they
  are how a person identifies the row they are about to reveal.
- **An entry kind this build cannot display is still listed, in its own tab, and explained.** It is
  never hidden and never dropped: an email account appears under Email with "no code", an SSH key
  under SSH Keys with "no code".
- **An import asks before it adds a second account from an issuer that is already there.** The
  question is Android's, word for word — *Issuer already exists* … *Add another account from this
  issuer anyway?* with **Add anyway** — and the identical entry is refused instead of stored twice.
  `allowDuplicateIssuer` is the answer to that question and nothing else sets it.

## Face ID, and why it does not weaken the vault

The quick unlock is the one thing the iOS client has and the other two deliberately do not (Android's
own issue history has no biometric unlock; the desktop client's unlock is the key file). It was added
under a rule worth writing down, because it is easy to get wrong:

- **The vault stays password-encrypted.** Nothing about `secrets.dat`, the KDF, the salt or the
  verifier changes. What Face ID releases is the *derived session key*, so the GCM tag still decides
  whether a key opens the file, and the password opens it exactly as before. Losing the item — a
  restore to another phone, a wiped Keychain, a re-created vault — costs the shortcut and nothing else.
- **Off until turned on, and turning it on asks for the password.** An unlocked phone must not be
  enough to enrol a face that keeps access after the vault is locked again.
- **`kSecAccessControlBiometryCurrentSet`, `WhenUnlockedThisDeviceOnly`, not synchronizable.** The item
  never leaves the device, and enrolling a new face or finger invalidates it — so someone who can add
  a face to an unlocked phone still cannot use the key they added.
- **No passcode policy.** Only `.deviceOwnerAuthenticationWithBiometrics` is evaluated: the device
  passcode is a different secret, and accepting it here would quietly become "the phone's PIN opens the
  vault". A lockout, a cancel or a missing enrolment all end at the password field.
- **The switch is a client preference**, stored in `vault.properties.plist` beside `ui_sort_mode`, never
  inside the vault document — the other clients reading this vault see no change at all.

`Info.plist` declares `NSFaceIDUsageDescription`; it is the only permission the app asks for, next to
no camera, no tracking and no networking.

### Accessibility

What a screen reader gets from the list, and why:

- **The code is spoken one character at a time** (`CodeFormat.spoken`). A six-digit code read as a
  number — "two hundred eighty-two thousand, seven hundred sixty" — cannot be typed into another
  client, and Steam and mOTP codes mix letters and digits. The characters and their order are
  untouched; only the spaces VoiceOver already pauses on are added.
- **The time left is part of the code's value**, so the countdown ring is `accessibilityHidden` — a
  shape that changes every second is noise, and the value still says "12 seconds left".
- **The action is the label, the code is the value**: "Copy code", then "3 2 4 5 5 0, 12 seconds
  left". Folding the value into the label makes VoiceOver announce a changing label on a focused
  element; splitting them is what the platform expects.
- **Every control is at least 44 points** (the reveal eye was 32 points tall), and the name, label and
  kind of a row are combined into one stop instead of three fragments read out of order.
- Icon-only toolbar buttons all carry labels, and the tab strip reports `isSelected`.

That is the VoiceOver and target-size pass; a full accessibility audit on hardware (Switch Control,
Voice Control, Dynamic Type at AX5) is part of the TestFlight work, where a device is in the loop.

The other half of M5's "background blur" was settled without a blur: when the scene stops being
active, `RootView` covers the app with the theme background and a lock glyph. The snapshot iOS takes
for the app switcher therefore contains no list, no label and no code. A blur would leak more than it
hides — a large glyph stays readable through a modest radius — so the cover is opaque and complete.

Not there yet: the hardware-key features that are Android-only by design and the App Store work.
OpenPGP backups (`.json.gpg`) are implemented via the unified Rust backend (`rust/offlineotp-openpgp`,
rpgp 0.21 + UniFFI) that covers all five distributions — Android, Debian, Windows, macOS and iOS — from
one crate. The crate builds a `cdylib` for JVM targets (JNA) and a `staticlib`/`XCFramework` for Apple
targets; `RpgpOpenPgpPort` on desktop (`desktop/.../RpgpOpenPgpPort.kt`), `RpgpOpenPgpPort` on Android
(`app/.../RpgpOpenPgpPort.kt`) and `RustOpenPgpPort`/`OpenPgpBackupCodec` on iOS (`ios/Sources/OfflineOTPKit/OpenPgp.swift`)
wrap it. Generated Kotlin/Swift bindings are committed. Desktop defaults to the native engine and
keeps PGPainless only as an absent-library fallback; Android CI stages per-ABI `jniLibs`; iOS CI runs
`scripts/openpgp/build-ios-xcframework.sh`, and Package.swift enables `OFFLINEOTP_OPENPGP_NATIVE`
when the XCFramework is present. Without that artifact the Swift port fails explicitly with
`backendUnavailable` rather than pretending to encrypt.

- **OpenPGP backups** (`.json.gpg`) — implemented and packaged by CI; native/interop verification remains a CI/device gate (see Non-goals for the plan, not a non-goal).

Google Authenticator's multi-account export is now imported (M5's first item). A bounded, non-recursive
reader parses one code of the export; `MigrationBatchCollector` holds the codes of a single export
until all of them have arrived, so nothing is written for a partly scanned export, and the confirmation
writes the batch in one commit, skipping an account the vault already holds. The reader, the statuses
and every sentence are `:core`'s, and `scripts/tests/test_ios_app.py` compares them.

Plain-text export stays unimplemented on purpose: the client *reads* a legacy plain-text backup, warns
with Android's sentence before the file picker, and writes only encrypted backups.

All nine entry kinds now have full cross-client support in `OfflineOTPKit` and the iOS app:
`SshKeyMaterial` + `SshConnection` frame and edit SSH keys and destination metadata, `PasswordMaterial`
+ `BrowserPasswordCsv` + `PasswordImport` import and display browser passwords and encrypted password
archives, and `EmailMaterial` + `ThunderbirdCsv` + `ThunderbirdImport` create, view, import, and export
FairEmail and Thunderbird email accounts alongside LinSCP credential vaults. The manual form offers the
same six kinds as `:core` (`TOTP`, `HOTP`, `MOTP`, `STEAM`, `NOTE`, `SSH_KEY`), while `PASSWORD`,
`PASSWORD_ARCHIVE`, and `EMAIL` are created via their dedicated import and account sheets.

The vault lives in `Documents/data/`, the same three filenames the desktop client uses, so the folder
readable by the Files app is self-explanatory. It is therefore included in an encrypted device backup,
which is the intended behaviour: a restored phone still opens the vault with the same password, and
nothing about that backup is weaker than the vault file itself.

## Security requirements

- Vault password and keys stay in the **Keychain** with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`;
  the derived key is never written to disk, `UserDefaults`, or a log.
- Argon2id v1.3 (RFC 9106) + BLAKE2b (RFC 7693) in pure Swift, legacy PBKDF2 through CommonCrypto
  (`CCKeyDerivationPBKDF`), and AES-GCM through CryptoKit; no third-party
  crypto dependency.
- No networking code at all, matching the other clients (the app is offline by design; that is also
  why the App Store privacy answers are short).
- iOS cannot block screenshots the way Android's `FLAG_SECURE` does. Instead: blur the content when
  `scenePhase` leaves `.active`, clear the pasteboard on a timer
  (`UIPasteboard.setItems(…, options: [.expirationDate: …])`), and never put a secret in a
  notification, a widget or a snapshot-visible view.
- Unsupported entry kinds (`PASSWORD`, `PASSWORD_ARCHIVE`, `EMAIL`, and any future kind) must be
  **preserved verbatim** when a vault is saved, even though v1 cannot display them. Dropping them
  would silently destroy another client's data — the failure mode this whole repository is built to
  avoid.

## Non-goals for v1

Hardware security keys over NFC. FairEmail account import/export. The password-vault and email entry
kinds (preserved, not edited). Master-password change on device.

OpenPGP (`.json.gpg`) backups were previously a non-goal because the clients used incompatible
implementations (Android via external OpenKeychain provider, desktop via embedded PGPainless, iOS with
none). That gap is now closed by the unified Rust backend (`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI)
which provides one implementation for all five distributions. The crate, its committed Kotlin (JNA) and
Swift (XCFramework) bindings, and the `OpenPgpPort` adapters are wired: desktop defaults to the native
engine, Android CI packages `cargo ndk` `jniLibs`, Debian/Windows/macOS package the host `cdylib`, and
iOS CI builds the `XCFramework`. PGPainless remains only as a desktop fallback when the native library
is absent; OpenKeychain remains a supported Android provider path. See
`rust/offlineotp-openpgp/src/lib.rs`, `scripts/openpgp/README.md` and
`ios/Sources/OfflineOTPKit/OpenPgp.swift`.

## Risks

| Risk | Mitigation |
| --- | --- |
| Crypto diverges from the JVM implementation | Fixed-vector corpus, run on both sides, byte comparison rather than value comparison |
| A new entry kind or field appears and iOS drops it | Round-trip rule above plus a corpus fixture added whenever `:core` gains a field |
| App Store review treats an offline authenticator as needing justification | No network, no accounts, no analytics; privacy answers and a short reviewer note in M5 |
| Two codebases for one product | Accepted and bounded: formats are frozen by vectors, and the parity rules in `docs/client-parity-2026-11-02.md` apply to the iOS client as they do to the desktop one |
| Xcode/macOS runner drift | The workflow pins the runner image and the Xcode version it selects |
