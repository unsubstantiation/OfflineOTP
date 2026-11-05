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
| Key derivation | PBKDF2-HMAC-**SHA1**, **256-bit** output, **16-byte** salt, iteration count from the settings file |
| Written iteration count | at least **30000** (`PBKDF2_MIN_AUTH_ITERATIONS`); a reader accepts 1 – 20000000 |
| AES key | the **first 16 bytes** of that 32-byte output (lower half) |
| Verifier | the **last 16 bytes** (upper half), stored Base64 URL-safe |
| Cipher | AES-**GCM**, **128-bit** tag, **96-bit** nonce |
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

Settings beside the vault (Android `SharedPreferences`, desktop `data/vault.properties`) hold
`auth_method=password`, `encryption=password`, `auth_salt` (Base64 URL-safe, 16 bytes),
`auth_iterations` (int) and `auth_credentials` (Base64 URL-safe verifier, compared in constant
time). The verifier is a fast pre-check; the GCM tag is the real gate.

### 2. Encrypted backup: `otp_accounts_<timestamp>.json.aes`

| Property | Value |
| --- | --- |
| Framing | `4-byte big-endian iterations ‖ 12-byte salt ‖ 12-byte nonce ‖ ciphertext ‖ 16-byte tag` |
| Key | PBKDF2-HMAC-SHA1, **all 32 bytes** (no split — unlike the vault key) |
| Iterations | writer picks 140000 – 160000; reader accepts 1 – 1000000 |
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
| `secrets.dat` + `vault.properties` | unlock, decode, and re-encode to exactly `expected-vault.json` |
| `expected-vault.json` | compare bytes after a round-trip, not just parsed values |
| `otp_accounts.json.aes` | decrypt to the same document, then re-seal deterministically |
| `desktop-note-export.json.aes`, `desktop-ssh-export.json.aes`, `desktop-privacy-export.json.aes` | open a real backup containing a Unicode note, a >5 KiB SSH key and hidden/revealed entries |

A Swift test target reads those files **from the repository path** (no copies), so a format change
on either side breaks both suites at once. The corpus is the whole reason this project is feasible:
the crypto never has to be reviewed by eye.

## Milestones

| # | Scope | Exit criteria |
| --- | --- | --- |
| **M0** ✅ | SPM package (`VaultCrypto`, `Entry`, `VaultDocument`, `EncryptedBackup`, `PasswordVault`, `TokenCalculator`), test target wired to the golden fixtures, `.github/workflows/ios.yml` | Fixture tests green in CI; the package also compiles for the iOS simulator. The Xcode *app* target arrives with the UI at M3 |
| **M1** ✅ (except device interop) | Unlock `secrets.dat`, decode entries, re-encode the shared document byte for byte, create a vault, save, back up, restore, reject a wrong password | Every vault fixture round-trips exactly; a vault this client creates reopens on a second instance. A vault created on Android opening here is still proved by the golden fixture rather than by a device |
| **M1.5** ✅ | An Xcode project generated from `ios/project.yml`, an app target with a SwiftUI shell (unlock, list, codes, copy with expiry, lock), and app unit tests that `ios.yml` runs on a simulator | An unsigned simulator build and an `OfflineOTP-ios-simulator` artifact exist on every pull request |
| **M2** ✅ | TOTP/HOTP/Steam/mOTP, countdown, copy, HOTP step, use tracking | RFC 4226/6238 vectors plus the codebase's own token vectors match digit-for-digit; `EntryUsageTests` pins the stamp, the counter and its ceiling against `EntryUsage` in `:core` |
| **M3** ✅ | List with the five categories, search, tag filter, the five sort modes, reveal, add/edit/delete an entry | Same vault shows the same order and the same tabs as the other two clients: the projection mirrors `EntryListPresentation`, and `scripts/tests/test_ios_app.py` compares the tab set, the titles, the five order names and the tag rules with `:core` |
| **M4** ✅ | Encrypted backup export/import, plain-text import, `otpauth://` and QR import, QR display | A desktop export restores on iOS and an iOS export restores on Android: the header, the merge rule, the file names, the parser bounds and the parser's error names are all pinned against `:core` by `scripts/tests/test_ios_app.py`, and `OtpUriTests`/`BackupRestoreTests` pin the round trips |
| **M5** | Face ID lock, background blur, accessibility, TestFlight | App passes review; no plaintext secret in logs, files or pasteboard beyond the expiry window |

Estimated shape: M0–M1 about a week each, M2 about a week, M3 the largest at one to two, M4 about a
week, M5 about a week. Nothing here is on the critical path of the existing clients. M4 and M5 are
what remains.

## What exists today

| Path | What it is |
| --- | --- |
| `ios/Package.swift` | The Swift package, iOS 17 / macOS 14, one library and one test target |
| `ios/Sources/OfflineOTPKit/VaultCrypto.swift` | PBKDF2-HMAC-SHA1 through CommonCrypto, AES-GCM through CryptoKit, the credential split, Base64 URL-safe, constant-time compare |
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
| `ios/Tests/OfflineOTPKitTests/` | The fixture tests, the vault round-trip tests, the token vectors, and the projection, usage, draft, URI and restore rules |
| `ios/project.yml` | The Xcode project definition (XcodeGen). The generated `.xcodeproj` is not committed |
| `ios/App/Sources/` | The app: `OfflineOTPApp` (entry point, `scenePhase` cover), `UnlockView`, `EntryListView` (tabs, search, tag chips, sort menu, rows, row menu), `EntryEditorView`, `BackupView` (export, restore, link and image import), `QrCodeView` (an entry as a QR code), `QrImageReader` (Vision), `VaultStore`, `CodeFormat`, `Clipboard`, `ForestPalette` |
| `ios/App/Tests/` | The app's unit tests: grouping and countdown arithmetic, the list rules reaching the files (tabs, search, tags, orders, reveal, counter step, add/edit/delete), the backup and restore round trips through `VaultStore`, and a real create/save/reopen cycle |
| `scripts/ios/generate-token-vectors.py` | Generates the token vectors after checking itself against the published RFC 4226/6238 values |
| `scripts/ios/generate-thumbnail-catalog.py` | Generates `ThumbnailCatalog.swift` from the Kotlin catalog; `ios.yml` and `scripts/tests/test_ios_app.py` both fail when the copy is stale |
| `scripts/tests/test_ios_package.py`, `test_ios_app.py`, `test_ios_plan.py` | Keep every restated constant, the field order, the palette, the offline promise and this document honest |

## What the iOS build action does

`ios.yml` exists now that there is a project to build — a workflow before that could only fail or sit
permanently skipped, which is indistinguishable from coverage that does not exist. It follows the same
per-platform shape as the other four, on `runs-on: macos-14`:

1. `swift build --build-tests` and `swift test`, which run the format and fixture tests on the macOS
   host;
2. `scripts/ios/generate-token-vectors.py` and `scripts/ios/generate-thumbnail-catalog.py`, which
   refuse a stale generated file;
3. `xcodebuild -scheme OfflineOTPKit -destination 'generic/platform=iOS Simulator'`, which proves the
   format layer compiles for iOS itself;
4. `brew install xcodegen`, `xcodegen generate`, then `xcodebuild build` for
   `generic/platform=iOS Simulator` with `CODE_SIGNING_ALLOWED=NO` — **no signing identity at all**;
5. `xcodebuild test` on the newest available iPhone simulator (picked with `simctl`, so an image that
   renames its devices does not break the job), which runs the app's unit tests;
6. two checksummed artifacts: `OfflineOTP-ios-simulator` (the `.app`, packed with `ditto` and
   re-extracted to prove the bundle survived) and `OfflineOTP-ios-kit-source` (the package plus the
   fixtures it was verified against).

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
note, edit one, delete one behind a confirmation that names it, lock, and cover the content when the
scene leaves the foreground.

Moving data: write an encrypted backup under a dated name (`otp_accounts_<yyyy-MM-dd_HH-mm-ss>.json.aes`,
the same shape the phone and the desktop write), restore an encrypted or a legacy plain-text backup —
merged by default, with the **Replace existing entries** switch — paste an `otpauth://` link, import
one from a QR image picked from the photo library, and show an entry as a QR code for another device
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

Not there yet: Face ID, the hardware-key features that are Android-only by design, and two import
formats the other clients have. Those are M5 above, and the two formats are:

- **Google Authenticator's multi-account export** (`otpauth-migration://`) needs a protobuf decoder
  and its own error vocabulary. This client recognises the payload and says so by name rather than
  reporting a broken link, so a person is told to use another route while that work is outstanding.
- **OpenPGP backups** (`.json.gpg`) need a whole OpenPGP implementation; see the non-goals below.

Plain-text export stays unimplemented on purpose: the client *reads* a legacy plain-text backup, warns
with Android's sentence before the file picker, and writes only encrypted backups.

One iOS-specific limit, and it is a limit rather than a parity gap: **an SSH key cannot be created
here**. Its seed is `SshKeyMaterial`-framed — a public key and a private key with length prefixes —
which this build does not implement, so the form would have to write a frame it cannot reproduce.
Such an entry is still read, listed, revealed and written back unchanged. Writing an unframed blob
into that field would produce an entry the other clients cannot decode, which is worse than a form
with one option missing. The password and email kinds are excluded for the shared reason: only an
import can produce them.

The vault lives in `Documents/data/`, the same three filenames the desktop client uses, so the folder
readable by the Files app is self-explanatory. It is therefore included in an encrypted device backup,
which is the intended behaviour: a restored phone still opens the vault with the same password, and
nothing about that backup is weaker than the vault file itself.

## Security requirements

- Vault password and keys stay in the **Keychain** with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`;
  the derived key is never written to disk, `UserDefaults`, or a log.
- PBKDF2 through CommonCrypto (`CCKeyDerivationPBKDF`), AES-GCM through CryptoKit; no third-party
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

OpenPGP (`.json.gpg`) backups — they need an OpenPGP implementation, and the existing clients use
incompatible ones (Android talks to an external provider, the desktop embeds PGPainless). Hardware
security keys over NFC. FairEmail account import/export. The password-vault and email entry kinds
(preserved, not edited). Master-password change on device.

## Risks

| Risk | Mitigation |
| --- | --- |
| Crypto diverges from the JVM implementation | Fixed-vector corpus, run on both sides, byte comparison rather than value comparison |
| A new entry kind or field appears and iOS drops it | Round-trip rule above plus a corpus fixture added whenever `:core` gains a field |
| App Store review treats an offline authenticator as needing justification | No network, no accounts, no analytics; privacy answers and a short reviewer note in M5 |
| Two codebases for one product | Accepted and bounded: formats are frozen by vectors, and the parity rules in `docs/client-parity-2026-11-02.md` apply to the iOS client as they do to the desktop one |
| Xcode/macOS runner drift | The workflow pins the runner image and the Xcode version it selects |
