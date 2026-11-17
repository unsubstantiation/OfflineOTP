# LinSCP Key/Sessions interoperability

Reference reviewed: LinSCP commit `3603935c6e17373ba728317b8008fa662bd8f925`,
[interchange specification](https://github.com/unsubstantiation/LinSCP/blob/3603935c6e17373ba728317b8008fa662bd8f925/docs/offlineotp-vault-interchange.md)
and `crates/linscp-engine/src/offlineotp_vault.rs`.

## Formats and metadata

- Encrypted `LSCPVLT2` is still the default: Argon2id (64 MiB, three iterations, one lane)
  and AES-256-GCM with the version marker authenticated as AAD.
- Explicit plain-text export writes the identical credential document as a UTF-8 JSON array,
  without an envelope or password. Base32 fields are **encoding, not encryption**.
- New filenames follow `key_session_YYYY-MM-DD_HH-mm-ss.json.aes` (encrypted) and `.json`
  (plain), with UTC timestamps matching LinSCP. Import detects content, not extensions.
  Previous encrypted exports, BackupBundle JSON and sessions.ini remain supported.
- The complete `linscp_session_json` string and all `linscp_*` columns survive re-export.
  Session passwords, key passphrases, proxy credentials, jump hosts, protocol and directory
  settings are not reconstructed from a limited subset. Unknown session fields survive too.
- Tests exercise synthetic WebDAV/session records, including opaque future fields. This is
  not a claim that a native LinSCP application round trip was performed in the sandbox.

## Selection and explicit consent

All clients list individual key and session entries before committing an import or export.
Selections start empty; a nonempty selection is required to proceed. Import parses and validates
before selection, then merges only selected entries into the current vault. Unselected entries
are not installed. Existing entries and source files are not replaced.

Export offers saved Keys and Password records as session candidates; OTPs, notes, email entries
and password archives are excluded. LinSCP ultimately decides which generic session URLs it
can use. Selecting a session does **not** silently export referenced keys: select those key rows
as well when needed. The UI explains this. Filters on the vault list do not limit the candidates.

Plain text is off by default and warns:

> Warning: Exported secrets will be accessible by software on your device.

An empty password never implicitly enables plain-text export. Desktop's generic encrypted backup
controller refuses plain text unless a dedicated plain encoder was supplied; only the LinSCP path
supplies one. Desktop forces the plain filename to `.json` (stripping `.aes` from the selected
suggested name) and keeps existing-file refusal. Android/iOS suggest `.json` to native document
pickers. Native providers manage overwrite prompts. Cancel cannot undo a write already started.

## Shared code and security boundaries

Android and desktop share the core encoder/decoder, limits and metadata preservation. Desktop
uses detached worker snapshots and the existing cancellation/write ownership protocol. Import
selection destroys discarded JVM entries; dismissed/locked requests cannot adopt staged entries.
Android retains session guards and wipes staged export bytes on cancellation or completion.
iOS uses its matching OfflineOTPKit codec and native selection sheets, checks session generation
and the selected entries' full fields, and persists imports before adoption.

“Keys” is a display rename. The persisted `SSH_KEY` identifier and public/private key framing stay
unchanged for backward compatibility. SSH connection fields still describe SSH connections.

## Validation boundary

Kotlin core/desktop behavioral tests cover new plaintext metadata round trips, wrong/empty password
refusal, malformed arrays, INI/JSON discrimination, selection isolation, stale selection rejection,
plain-mode opt-in, filename separation, no-overwrite and canceled pending writes. Python checks
cover labels and all-client wiring. Swift XCTest cases were added but require Apple tooling to run.

Native Android/Compose and Xcode builds still need validation. In particular, test long selection
lists, importing both a session and its referenced main/jump key, cancel/lock while a picker is
open, exporting both modes, failed saves, and a real round trip through the updated LinSCP build.
Android and iOS decoding/export preparation remain synchronous as in their prior implementation.
