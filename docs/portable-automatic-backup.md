# Shared automatic-backup decisions — chunk 8

Implemented 2026-11-20. This is a code/contract handoff, not a native-runtime or requested-OS parity certificate.

## Common responsibility and adapters

`shared-domain/commonMain` now owns:

- `BackupAutomationPolicy`: interval/retention/password bounds, public-recipient uniqueness/size, unlocked/signing resume admission, and pending-reversion decisions.
- `AutomaticBackupConfigurationCodec`: bounded, strict configuration JSON validation/encoding. Existing v1 password export, v2 OpenPGP (including `requiresSigning`), and v3 exact vault-copy modes remain distinct. Optional Apple bookmarks are opaque bounded bytes. Duplicate fields, numeric coercion/overflow, inconsistent mode/credentials, unexpected fields (including private signing material), oversized documents and malformed recipients are refused before native adoption.
- `AutomaticBackupSchedule`: generation-bound, one-at-a-time backup tickets, first-change coalescing, catch-up after an in-flight successful save, periodic delay after completion, and cancellation/stale-completion rejection. It owns no document, key, password, timer, executor or file handle.
- `AutomaticBackupRetention`: ownership-name recognition and deletion plans for existing Android/provider UUID names and desktop/Apple managed timestamp names. Password/OpenPGP/vault-copy managed families stay separate. No files are renamed.

JVM credential/configuration APIs delegate to these rules. Desktop and Apple production controllers call the common scheduler and managed retention policy; their native timers/executors still execute the decisions. Apple raw configuration is common-validated **before** Codable adoption, so duplicate/unknown fields cannot disappear before validation. Native path absoluteness, bookmarks/grants, symlink checks, provider coordination, atomic/exclusive writes and permissions remain native. ABP01 HMAC derivation and authenticated AES-GCM remain on the existing JVM/CryptoKit backends and retain the independent golden vectors. OpenPGP still uses the existing backend. Saved-vault copies remain byte-for-byte copies with no entry serialization or extra encryption.

Android remains successful-save/pending-retry driven; this chunk does not invent foreground periodic Android scheduling or promise detached jobs while locked. Native durable marker storage/claims and keyed fingerprints remain in Android adapters; reversion and prior-digest decisions now delegate to common policy. The legacy broadcast worker no longer reloads a Keystore vault key to bootstrap a locked session: it borrows the active session key, checks generation/readiness before publication, and leaves pending changes for a later unlocked retry.

## Credentials, cancellation and key changes

Desktop and Apple saved configuration remains encrypted under a domain-separated vault-key-derived key. Signing keys/passphrases never enter this record. A restored signed configuration is paused using shared resume policy until signing credentials are reloaded for the session; it cannot silently become unsigned. Encryption-only configurations may resume after unlock. Native owners cancel detached work and release credentials on lock/background/close/disable. Common scheduler tickets cannot revive disabled/reconfigured work, and desktop workers additionally bind consumption and pre-write checks to the originating generation. Configuring across a rekey is rejected.

New Android separate-password settings are written as `vault-abp1:` records using the same vault-bound credential encryption, with generation/identity checks before committing the preference. Reads require the unlocked session and recheck it before returning plaintext. Existing device-wrapped legacy records are read only while unlocked and are replaced only by explicit reconfiguration; ordinary open does not silently migrate them. Folder grants, source selection and retention settings retain their existing Android settings representation. The new encrypted password record is not a portable-preferences field or a login/PRF fallback.

After a key/password recovery or replacement, an old configuration record may no longer decrypt. It is preserved, not silently discarded or overwritten. Desktop/Apple report reconfiguration required; Android settings explain that its saved separate password must be re-entered if export cannot resume, and the existing failed-export/no-password path reports failure. Existing backups are never deleted on disable or configuration failure. Copying a vault container does not copy local destination grants or sidecar configuration.

Mutable native byte/character arrays retain explicit cleanup and common wrappers redact diagnostics. Immutable JSON/password strings, Swift copy-on-write values and crypto backend internals do not provide a physical zeroization guarantee. Cancellation prevents queued work and rechecks before writes; it is not a rollback guarantee for an OS/provider write already underway.

## Deliberate safety tightening

Retention now always keeps unknown-timestamp provider documents even if they outnumber the retention limit. Duplicate provider identities are excluded from deletion plans instead of resolving to an arbitrary document. UUID checks require ASCII hexadecimal and standard dash positions; malformed all-dash managed names are not owned backups. Android pruners skip named directories and failures to inspect them. Existing `keep = 0` provider behavior remains keep-everything, and deterministic known-date tie ordering remains intact. Native pruning still runs only after a successful authorized backup.

Configuration validation rejects unexpected fields rather than persisting unreviewed future credential types. Older valid schemas and mode choices are retained. Standalone SwiftPM's native policy oracle and toolkit-free UI input validation mirrors remain until presentation/cleanup chunks; production automatic-backup engine decisions use common rules, not a fallback after common rejection.

## Validation and remaining gates

Local gates:

| Gate | Result |
| --- | --- |
| Core | 422 passed |
| App JVM | 443 passed |
| Desktop | 479 passed, 2 skipped (481 total) |
| Shared UI stub gate | 3 passed |
| Sandbox | All checks passed |
| Android instrumentation stub compile | 29 files compiled; not executed on a device |
| Python | 759 passed |
| Shared boundaries / whitespace | Passed |

New tests cover strict configuration adoption, native secret ownership, coalescing/catch-up, stale/locked/failed/duplicate completions, no signing downgrade, unknown/duplicate/malformed retention identities, pending reversion, desktop queued/in-flight rekey suppression and rekey during configuration. Added Android vault-bound password/restart/wrong-key tests and Apple production scheduler/configuration tests still require native execution.

Outstanding equipped-runner gates: real Gradle common/JVM and both Android variant builds/instrumentation; Kotlin iOS framework/SwiftPM/Xcode app and test builds; packaged desktop smoke tests. Specifically exercise Files/SAF revocation and grant persistence, unlock/relock/background/rekey during queued and active backup work, signing reload, failed record decrypt/reconfiguration, retention failures and exact snapshot bytes on requested systems. Stub/source gates do not prove ABI compatibility, secure erasure, hardware/provider behavior or OS parity. Chunks 9 (presentation/UI) and 10 (final cleanup/release) remain separate.
