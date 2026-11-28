# Automatic vault backups and encrypted exports

Shared configuration, retention and scheduling decisions are implemented in chunk 8; see the
[shared automatic-backup handoff](portable-automatic-backup.md) for boundaries, compatibility
tightening, evidence and outstanding native gates.

## Full saved-vault copies

The automatic-backup form has a **full vault file copies** mode, independent of the existing
password/OpenPGP export choices. It copies the saved authenticated portable container byte for
byte; it never serializes live entries, derives an export key or wraps the file in more encryption.
Recovery uses the vault recovery password at the time each copy was made. New users see this
choice first; previously saved export configurations retain their original mode, including paused
session-signing requirements. Switching modes requires an explicit configuration action.

Desktop/iOS remember this as vault-encrypted configuration v3 (`mode: vault-copy`), containing
folder/interval/retention but no export credentials. Full-copy scheduling uses the same guarded
foreground save/periodic/manual machinery as exports. A legacy vault fails portability preflight
before configuration is saved; use **Migrate legacy vault and save full copy** in Backups first.
Full copies use `.offlineotp`; retention is scoped to that extension and does not delete password
or OpenPGP exports. All three kinds preserve existing files on disable.

Android exposes **Full saved vault file copies** in Settings → backup password/source. It uses
the existing configured destination/retention and successful-save retry path, works after local
quick unlock without a separate login-derived export key, and refuses locked-vault/detached worker
execution. Android does not gain desktop/iOS periodic scheduling in this change. Its existing
managed UUID backup filenames remain `.json.aes`, recognized by the container-aware restore path;
the extension alone is not a format discriminator. Source choice is explicit; previously selected
password export modes are not changed automatically or deleted when selecting file copies.

See [portable vault containers](portable-vault-container.md) for the authenticated file contract,
password recovery and legacy migration/rollback guarantees. The sections below describe the
retained **encrypted-export modes** unless explicitly stated otherwise.

## Active routes

- Desktop Compose: Tools → Backups → Automatic encrypted backups.
- iOS Compose: Backups and links → Automatic encrypted backups. The common Tools grouping also
  exposes this backup row. An iOS native folder picker grants Files access without importing a
  folder copy; the callback is tied to the originating coordinator and vault generation.

The desktop form and iOS portable surface collect matching separate backup passwords, an interval
of 1–1440 minutes (default 15), and retention of 1–100 files (default 10). Enable immediately takes
a detached snapshot and schedules the next backup only after the preceding write succeeds. Successful
vault saves additionally trigger a coalesced backup after one second. Changes during an in-flight
backup request one catch-up run; repeated changes cannot postpone the first pending run indefinitely.
Back up now and Disable are explicit actions. Both hosts now also expose an **unsigned OpenPGP**
mode with snapshotted public recipient certificates and optional session-only signing. No plaintext automation mode is exposed.

## Credential and scheduling policy

Desktop and iOS persist the selected folder, separate backup password, interval and retention in
`automatic-backup.credentials`, encrypted with a domain-separated key derived from the unlocked
vault key. The local ABP01 record uses AES-GCM with an authenticated header and random nonce;
it is not part of portable preferences. iOS includes the Files bookmark inside the ciphertext.
Scheduling resumes only after vault unlock (and foreground entry on iOS); no backups run while
locked. Lock/background/close cancels session work and releases credentials. Disable forgets the
saved configuration, not existing backups. A vault-key change/recovery cannot decrypt an old record:
this visibly requires reconfiguration, rather than silently discarding it or pretending backup works.
Desktop minimizes trigger cancellation only when the relock policy requests it.

New Android separate-password settings are also vault-key encrypted (`vault-abp1:`) and readable
only while unlocked. Existing device-wrapped passwords remain legacy until explicit reconfiguration;
ordinary open does not rewrap them. The legacy broadcast worker may retry a pending change only
with the current unlocked session key—it cannot bootstrap a locked session from the Keystore.
Android retains save-triggered retries rather than gaining a periodic foreground timer here.
After vault-key replacement/recovery, an unreadable saved password is preserved and must be
re-entered; settings describe that requirement. Local settings/grants are not copied with a vault file.

Save notifications come from successful persistence, not search/reveal UI refreshes or failed writes.
iOS queued notifications carry their vault generation and are admitted only in the foreground.
Desktop's guarded transaction workflow still pauses automation until the required re-unlock; its
initial resumed backup then contains the saved change. Neither host schedules locked-vault jobs.
The form displays the restored destination, periodic interval and retention; passwords are never
preloaded into fields.

Swift/JVM immutable strings and crypto internals cannot promise physical memory zeroization;
references are released on disable. Queued work now owns a revocable input: cancellation clears its
plaintext/password references before the queued closure runs, and wipes the queued JVM password
array. A worker that already took ownership still performs cancellation checks before writing. Desktop input
character arrays and encrypted output bytes are wiped; iOS encrypted output Data is reset after
writing. Work already in encryption may finish computing after cancellation, but checks suppress a
not-yet-started write. A file write already in progress may finish. Stale completions cannot re-arm
a disabled/replaced session. No backup failure rolls back or reports failure of the primary vault
save: these are independent backup/export files, not `secrets.dat.bck` recovery-file replacement.

## Destination and retention

Password exports use the existing portable password-encrypted document format with fresh crypto parameters; full copies preserve the vault container.
They are created exclusively in `OfflineOTP-automatic` under an existing selected directory, with
unique `auto-<16-digit-millisecond-time>-<UUID>.aes` (password) or `.pgp` (OpenPGP) filenames. A new export never overwrites an old
one. Managed folders/files use owner-only permissions where the host filesystem supports them;
iOS requests complete file protection. External provider support/ACLs must be tested, not assumed.
The iOS adapter retains security-scoped access through any in-flight worker, coordinates directory
writes with the Files provider, and releases access after disable and worker completion. The encrypted
bookmark is resolved and checked for staleness when restoring configuration; stale grants require
folder reselection.

Only after a successful new write does retention remove older regular, non-symlink files matching
the exact automatic filename pattern in that subfolder. Manual exports/unrelated names, symlinks
and outside paths are excluded. **Choosing this directory and retention authorizes deletion of
matching files from previous sessions too.** A failure disables scheduling, forgets the session
password and reports common-form status. Partial output or retention failure may leave extra files;
a failed write never triggers pruning. Disabling keeps existing files. Keep a separate verified
backup outside the managed retention directory.

## Verification and remaining parity

Desktop queue/timer tests cover detached snapshot handoff, exclusive ciphertext creation, input
consumption, invalid bounds, encryption failure, cancellation before/after encryption, late UI
delivery, one-at-a-time work, timer re-arm, retention exclusions and symlinks. Swift tests have been
added for invalid/stale configuration, nil-session snapshots, retention and unrelated/symlink
files. Python checks enforce source/config wiring. Swift/Xcode tests have **not run in Linux**.

Still not implemented here: Android-style OS background jobs (locked-vault backups are explicitly
out of scope under the selected credential policy), in-app OpenPGP signer-identity verification on restore, and uniform settings import/export semantics. External-provider bookmark
recovery needs native verification. This is not closure of the comprehensive backup-parity gap.

Independent synthetic ABP01 vectors cover 16/24/32-byte vault keys in JVM and Swift test sources.
Executed JVM tests additionally cover encrypted persistence after store reconstruction, wrong-key
record preservation, byte tampering, oversized files, symlink rejection and explicit forgetting.
Swift tests are added but have not run in this environment.

Failures are also surfaced as persistent, dismissible status in the main shared vault even when the
configuration form is closed; inspecting/re-enabling the form is not required to discover failure.
`automatic-backup-options.json` supplies canonical defaults/boundaries to both JVM and Swift policy
tests, including matching UTF-16-unit password length. This fixture covers this new workflow only,
not the audit's entire setting/entry field-level semantics matrix.

## Latest lifecycle and test coverage

JVM tests cover save-trigger coalescing, changes during work, fresh catch-up snapshots, retired timer
inertness after replacement/disable, revocation and wiping before queued work runs, and admission
loss inside the persistence hook. Controller tests verify only successful ordinary/detached writes
advance the save revision. Swift has injected queue/seal/timer tests for analogous paths, not yet
compiled/executed here. The shared options fixture now also pins the one-second change delay.

## Automatic OpenPGP encryption and optional session signing

The shared desktop form and iOS Compose surface offer OpenPGP instead of the backup password.
Select 1–64 distinct public-certificate byte blocks totaling **at most 32 KiB**. This automatic-mode
limit is smaller than manual export so certificates plus configuration fit inside the existing
64 KiB vault-encrypted credential record. Larger recipient sets remain available through manual
export. Filenames are not verified fingerprints: independently verify recipients and test a restore.
ANY matching private key can decrypt the entire backup; this is not threshold protection.

The encrypted ABP01 envelope is unchanged. Its password-mode plaintext schema remains version 1;
OpenPGP mode uses version 2 with `mode: openpgp`, an empty password, and `recipientKeys` containing
base64 public-certificate snapshots. iOS may additionally store its destination bookmark. No key-file
path/grant is retained: replacing a selected file later does not silently change recipients. Change
recipients by disabling/reconfiguring explicitly. Old builds that do not understand version 2 must
refuse it, not silently use a password or plaintext fallback. The record may also contain `requiresSigning: true`, but never a private signing/decryption key or
signing passphrase. Signing credentials are imported separately for the current session only.

Both modes use successful-save coalescing, periodic/manual triggers, revocable queued inputs and
one-at-a-time work. The existing OpenPGP codec/port validates recipients during encryption; an
invalid/expired/revoked/unusable certificate or unavailable engine fails visibly without a backup
write. The complete selected public set is supplied on every run. Retention is **mode-scoped**:
OpenPGP pruning never removes `.aes` backups and password pruning never removes `.pgp` backups.
Switching modes keeps prior-mode files, so manual space management may be required.

Executed JVM tests cover recipient bounds/duplicates, detached key ownership, queued revocation,
worker-owned buffer wiping, encoder failure, `.pgp` output naming, mode-separated retention,
encrypted configuration disk reload, exact schema parsing and malformed numbers/types/base64.
The shared schema fixture uses synthetic bytes, not OpenPGP certificates. Worker tests inject the
encoder; these do not establish native cryptographic/provider interoperability. Native Swift
configuration/queue/write/retention/admission tests are added but remain uncompiled/unexecuted here.
Unsigned remains the default; signed mode is explicit and requires session credentials.

### Session-only signing policy

The user selected **session-only signing credentials**, not vault-encrypted persistent signing keys.
Desktop/iOS offer an explicit signing requirement for OpenPGP backups. The imported private key and
passphrase are held by the session/queued worker input, not preferences, bookmark records or the
persistent backup configuration. iOS opens signing key documents in place (`asCopy: false`) and
releases the read grant, avoiding an app-owned document-picker copy of the imported private key.
The user's original key file is neither deleted nor modified.

Only `requiresSigning` is remembered with the public recipient snapshots. On unlock/foreground,
a signed configuration pauses with a visible reload-credentials status; it never silently produces
an unsigned backup. Turning signing off is an explicit form action. Lock/background/session close
revokes queued inputs and releases live signing credentials. JVM-owned key/password arrays and
worker snapshots are wiped. Immutable strings, Swift Data/COW and crypto internals cannot promise
physical zeroization; an already-running signer may finish computing, but publication is fenced.

The Rust backend signs *inside* the encrypted message using SHA-256, selecting a signing-capable,
unrevoked/unexpired bound secret subkey or an eligible primary signing key. Missing/invalid signing
keys, wrong passphrases, unusable signing capabilities, or absent/old native libraries fail without
an unsigned retry. The JVM signed route intentionally does not fall back to PGPainless. UniFFI
bindings were regenerated with pinned **0.28.3**, and the generation drift check passed. Rebuild the
native binaries/XCFramework alongside the bindings before native testing.

**In-app restore does not currently verify signer identity.** Signing is not a claim of trusted
sender verification in this UI. Verify signatures with a trusted OpenPGP tool and an independently
verified signing certificate. Both forms state this limitation. Added Rust signed-encryption/
signature-verification/wrong-passphrase tests have not run here; neither has Swift native execution.
Executed JVM tests cover session input ownership, revision fencing, input consumption/wiping,
no-unsigned-fallback worker failure and persistence of the requirement without secret fields.

### Reconfiguration, worker setup and native file-read lifecycle

Desktop setup now merges newly selected public certificates with the saved public snapshots rather
than replacing them implicitly. Combined count/byte/duplicate admission runs on that complete set.
Successful reconfiguration replaces the remembered snapshot with exactly the committed set; choosing
password mode or forgetting configuration clears the public selection. Clearing recipients is the
explicit way to start a new set.

Desktop public/private key file reads now run through a cancellable worker setup controller, not
on the Compose/AWT thread. Caller passphrase arrays are consumed immediately, queued input is
revocable, prepared credentials are scoped to the apply callback and wiped afterwards, and retired
worker/UI deliveries cannot claim a new request. An already-started filesystem read may finish;
its private buffers are released/wiped afterwards and cannot re-enable a retired session. The form
is read-only while setup is pending, with cancellation available by closing it.

iOS retains public recipient snapshots when the settings form closes, but clears unsubmitted private
key/passphrase drafts both on closing and when navigating to another surface. Changing encryption/
signing modes, clearing recipients or disabling configuration retires native selection authority.
Resume no longer consults stale form selections. Background/vault close still releases public and
private session inputs; signed resume reloads only the persistent public snapshots and signing
requirement, never the private credentials.

iOS selected-file reads now run through NativeFileReader on a worker queue. It acquires the temporary
security-scoped grant before the picker callback returns, transfers ownership to the active read,
and revokes queued callbacks/results on replacement/background/session change. Already-started
provider IO can finish, but root generation/foreground/selection fences and coordinator surface
fences prevent stale delivery. Swift file/provider tests are authored, not executed in Linux.
