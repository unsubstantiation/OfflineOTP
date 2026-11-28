# LinSCP Key/Sessions interoperability

Reference reviewed: LinSCP commit `942381eec6f49bc9307f02d255a7785874d3c113`,
[interchange specification](https://github.com/unsubstantiation/LinSCP/blob/942381eec6f49bc9307f02d255a7785874d3c113/docs/offlineotp-vault-interchange.md)
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

## Expanded session metadata

The current export also carries these opaque `PWD1` columns unchanged:

- `linscp_ca_cert_b64`, `linscp_tls_cert_b64`, `linscp_tls_key_b64`: Base64 TLS
  CA/certificate/private-key bytes, up to 384 KiB raw (512 KiB encoded) each.
- `linscp_cached_hostkeys_b64`: cached host keys scoped by LinSCP to the session.
- `linscp_transfer_preset_b64`: the session's custom transfer-preset section.

The complete session JSON retains folders, local/remote directories, alternate
hosts, proxy/jump-host credentials, key passphrases, protocol options and unknown
fields. Public-only SSH keys retain their existing public/private payload framing
and session label references. Selecting a session alone does not include its
separate SSH key rows.

Password material accepts at most **512 KiB UTF-8 bytes per name/value**, 64 columns
and 4 MiB per encoded entry. The 16 MiB file limit remains unchanged. Oversized or
invalid material rejects the import instead of truncating it. Browser CSV parsing
and editing retain their separate 64 KiB character limit.

Android and desktop use the shared Kotlin field codec. Production iOS document
validation uses that same codec; its LinSCP wrapper now reuses the app's password
material framing rather than a duplicate 64 KiB implementation. The metadata is
stored inside the encrypted vault and passed back to LinSCP without decoding,
trusting host keys, loading certificates, or following paths from the document.
Plain exports expose all of these secrets, including TLS private keys.

Legacy `sessions.ini`/BackupBundle import still reads saved sessions; it is not a
full LinSCP configuration/embedded-file restore. Missing material cannot be
recovered from an old export that never included it.

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

Kotlin core/desktop behavioral tests cover plaintext and encrypted metadata round trips, wrong/empty password
refusal, malformed arrays, INI/JSON discrimination, selection isolation, stale selection rejection,
plain-mode opt-in, filename separation, no-overwrite and canceled pending writes. Python checks
cover labels and all-client wiring. Swift XCTest cases were added but require Apple tooling to run.

The expanded-metadata test constructs PWD1 directly from the upstream specification,
uses three maximum-sized TLS columns plus host-key/preset/future columns, and checks
import, vault-document reload and both export modes. Boundary tests cover UTF-8
bytes, oversized wire input and the unchanged aggregate payload limit. These are
synthetic compatibility tests, not an executed round trip through LinSCP.

Native Android/Compose and Xcode builds of these changes still need validation. In particular, test long selection
lists, importing both a session and its referenced main/jump key, cancel/lock while a picker is
open, exporting both modes, failed saves, and a real round trip through the updated LinSCP build.
Android and iOS decoding/export preparation remain synchronous as in their prior implementation.
