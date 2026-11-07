# Incomplete-feature audit — 2026-11-06 (fourth pass)

The third pass searched for code that existed but no user could reach. This pass takes the five
gaps left open at the end of that summary and either closes them or says why they cannot be closed
here. Two are closed; three are not, and the reasons are concrete rather than a restatement of the
gap.

## 1. Backup retention: automatic exports grew without bound — completed

Every automatic export wrote a new `otp_accounts_<uuid>.json.aes` and nothing ever removed the
previous one. A vault with **Automatic backup** on saved one file per password edit, forever, so the
folder grew until the storage provider or the user complained — upstream #925, folded into #531.
The README even said so: *completed snapshots are not deleted automatically*.

The design constraint is that a wrong guess here deletes the user's only backup. So the decision is
split from the deletion:

- **`:core` `BackupRetention`** (Android-free, 7 unit tests) owns the *policy*: only names this app
  generates for an automatic export (`otp_accounts_<36-char uuid>.json.aes` / `.json.gpg`) are
  candidates. A dated manual export (`otp_accounts_2026-01-01_00-00-00.json.aes`), the shared
  `otp_accounts.json.aes`, and every unrelated file are excluded by construction. The newest N are
  never in the plan, an unknown `lastModified` counts as newest, and ties order by name so the plan
  is deterministic.
- **`AutomaticBackupPruner`** owns the *deletion*: it runs only after a successful automatic export,
  so the fresh document is already inside the kept set. A null tree, a null listing, or a provider
  that throws on `listFiles()`/`lastModified()` deletes nothing; each `delete()` that returns false
  is counted, not retried and not reported as success; every SAF call is wrapped in
  `catch RuntimeException`. A prune can never turn a completed backup into a reported failure.
- **`GenericBackupTask.pruneOldAutomaticBackups()`** runs after `doBackup()` succeeds and only when
  `isAutomatic()`. `EncryptedBackupTask.isAutomatic()` is `mode != Mode.USER`, i.e. the
  settings-driven and broadcast retries that create the files. A backup the user asked for never
  prunes: it is theirs, and deleting anything on the strength of it would be a surprise.
- **Settings → Keep automatic backups** is a `ListPreference` (Keep all / 5 / 10 / 20 / 50) defaulting
  to **Keep all**, which is exactly the behaviour every existing vault has today. `Settings`
  reads it through `BackupRetention.normalizeKeep`, so a corrupted or out-of-range stored value
  falls back to keeping everything rather than to a smaller number.

Desk-side (`.json.gpg`) files match the same rule, so an OpenPGP-backed automatic export is covered
by the same setting — the retention half of #531 — while automatic *creation* of OpenPGP backups
remains a gap (see below).

## 2. Browser passwords could not be edited or re-exported — completed on all three clients

The individual import deliberately keeps every source column inside the encrypted payload
(`PasswordMaterial`), including a Safari `Notes`/`OTPAuth` pair and Firefox's GUIDs and timestamps.
But nothing could write them back out: `EntryDraft.offerableTypes()` excluded `PASSWORD` so the edit
dialog never offered the type, the row menu hid **Edit** for it, and no client had a browser-CSV
exporter at all. The README documented the consequence as a limitation: *individual entries cannot
yet be edited or exported as a browser CSV; delete an outdated entry and reimport to change it.*

Re-importing is a bad answer — it loses tags, the manual order, the last-used stamp, and any entry
the user renamed. `:core` `PasswordCsvExport` (with `OfflineOTPKit/PasswordCsvExport.swift` as the
exact mirror) now provides:

- `support(entry)` → `Exportable(dialect)` / `Foreign` / `NotPassword`, decided **from the stored
  columns alone**. Chrome's `name`, Safari's `title`, Firefox's `httpRealm`/`formActionOrigin`/`guid`
  identify the browser, so an entry keeps its dialect after an edit. A LinSCP session carries the same
  payload with `linscp_session_json` and no browser could read it: it is `Foreign`, and exporting it
  is a hard failure, not a file with the session JSON dropped.
- `exportCsv(entries, dialect)`: the first entry's own header is kept when every selected entry
  agrees on it (that is the file the user actually imported); otherwise the dialect's canonical
  header is used, with absent columns written empty rather than invented.
- `withEditedFields(entry, url, username, password)`: changes only those three columns — the only
  vocabulary all three browsers share — and copies notes, GUIDs, timestamps and OTPAuth through
  untouched. A blank password is refused, because an empty password cell is a login that cannot work
  anywhere and the vault cannot tell it apart from a mistake.

Wiring, with the same rules in every client:

| | edit | export one | export all |
|---|---|---|---|
| Android | **Edit password…** in the entry menu (`PasswordEntryEditDialog`, password box starts empty) | **Export as `<browser>` CSV…** | **+ → Export passwords…** (`requestPasswordCsvExport(null)`) |
| Desktop | **Edit password…** (`PasswordImportDialogs.edit`) | **Export as browser CSV…** | **Tools → Export passwords as browser CSV…** |
| iOS | **Edit** in the password detail sheet (`PasswordEditSheet`, writes in place) | **Export as `<browser>` CSV…** in the entry menu | — |

Android writes through the Storage Access Framework: the serialised bytes are held only between the
warning dialog and the picker result, cleared on cancel and on lock (`onVaultLocked`), and the file
is removed again if the write fails or the vault is no longer current. iOS writes through the same
`fileExporter` the password *archive* export already used, with a separate message so a browser CSV
is not described as the original file.

## 3. Not closed here, and why

- **Paper backups (#178)** needs a printable format and an answer to a product question this
  repository cannot answer for the user: what a printed sheet contains (a QR of the encrypted vault?
  of each `otpauth://` URI?) and how much secret material belongs on paper. A stub that prints
  something would be worse than the gap.
- **Preferences export (#648)** is a format decision (JSON schema, which keys, whether secrets such
  as the backup password or OpenPGP recipients are included) plus a cross-client restore story.
  Exporting only the non-secret keys would be a new, half-useful surface; exporting secrets needs the
  product call first.
- **Nitrokey native OTP (#921)** needs the Nitrokey Pro's own OTP protocol over USB with a device to
  test against. The existing FIDO2 PRF unlock is the protocol-based path and stays **PARTIAL**; there
  is no hardware in this environment to verify against, and a protocol implementation that has never
  met a device is not a completion.
- **Automatic OpenPGP backups (#531)** needs the export side to pick recipients without a per-save
  prompt and to keep the retention rule meaningful for `.json.gpg`. The write path exists
  (`OpenPgpKeyFileBackupTask`); wiring it into the automatic trigger is a product decision about
  prompting, not missing plumbing.

## Verification

Every gate was run locally, as the tree was changed:

| gate | result |
|---|---|
| `python3 -m unittest discover -s scripts/tests` | 436 tests, OK (2 skipped) |
| `scripts/run-jvm-tests.sh` | 347 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/run.sh` | all sandbox checks passed (incl. 2 new ones) |
| `scripts/kotlin-sandbox/compile-desktop.sh` | 188 tests, 176 passed, 0 failed, 12 skipped |
| `scripts/kotlin-sandbox/compile-core.sh` | 326 tests, 326 passed (`BackupRetentionTest`, `PasswordCsvExportTest`) |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 20 androidTest files, COMPILE OK |
| `scripts/kotlin-sandbox/generate-r-stub.py --check` | R stub up to date |

New coverage:

- `BackupRetentionTest` (7, `:core`) and the sandbox check *automatic backup retention prunes only
  this app's old automatic backups*: a fake SAF tree with 7 automatic backups, a dated export,
  `otp_accounts.json.aes`, an unrelated `holiday.jpg`, and one file the provider refuses to delete —
  asserting the plan, the kept newest three, the counted refusal, that no user file is touched, and
  that the stored setting falls back to *keep all* for `0`, `9999` and `not a number`.
- `PasswordCsvExportTest` (7, `:core`), `PasswordCsvExportTests` (5, `OfflineOTPKitTests`) and the
  sandbox check *browser passwords can be edited and re-exported without losing their columns*:
  dialect detection per browser, header preservation, the mixed-selection canonical header, the
  refusal of foreign column sets, the edit touching only the three shared columns, and the refused
  empty password.
- `scripts/tests/test_retention_and_password_export.py` (13 guards) pins the wiring on all three
  clients: the prune call site and `isAutomatic()` rule, the preference key/default/array, the
  Android menu items and dialog, the intent constant and buffer wipe, the desktop menu items and
  dialogs, the iOS sheet and per-entry export.

### Verification limits

There is still no Android SDK, Gradle, Xcode or native Rust toolchain in this environment.

- The **Android** changes are compile-verified against stubs, not run: the edit dialog, the export
  picker and the SAF deletions need a device or emulator. The sandbox drives the *policy* and the
  pruner against a fake `DocumentFile` tree, not a real provider.
- The **Swift** changes are **not compiled locally**; `ios.yml` compiles and tests them in CI. The
  Python guards and the mirrored Kotlin tests pin the rules in the meantime.
- The **desktop** changes are compiled and the headless suite runs, but the new dialogs are Swing
  modals and are not clicked by a test.
