# Incomplete-feature audit — 2026-11-06 (fourth pass)

The third pass searched for code that existed but no user could reach. This pass takes the five
gaps left open at the end of that summary and either closes them or says why they cannot be closed
here. Three are closed. One is **declined** — a product decision recorded so it stops reading as an
accident. The last needs hardware nobody here has.

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
| iOS | **Edit password…** in the entry menu and **Edit** in the password detail sheet (`PasswordEditSheet`, writes in place) | **Export as `<browser>` CSV…** in the entry menu | **Export browser passwords CSV…** in **Backups & Links → Browser Passwords** (`VaultStore.exportBrowserPasswordsCsv()`) |

Android writes through the Storage Access Framework: the serialised bytes are held only between the
warning dialog and the picker result, cleared on cancel and on lock (`onVaultLocked`), and the file
is removed again if the write fails or the vault is no longer current. iOS writes through the same
`fileExporter` the password *archive* export already used, with a separate message so a browser CSV
is not described as the original file.

## 3. Preferences could not leave a device — completed on all three clients

`Settings` is a large surface (twenty portable keys on Android alone) and a migration between phones
meant re-ticking every switch by hand. The gap was not plumbing but a decision, and it is the one
this pass had to make first: **what does the file contain?**

The answer chosen here is *portable JSON that may include secrets and can be re-imported*. The
reasoning, in the order the constraints bite:

- **Re-importable, not inspection-only.** A file a human can read but no client can apply answers
  "what was my theme" and nothing else; the cost is nearly identical, so export that cannot restore
  is not worth building.
- **Secrets are opt-in per export, never automatic.** The only portable secret is the backup
  password. A user syncing a file to a cloud folder must not be handed a plaintext password by
  default, so the Android dialog asks (*include the backup password* / *without*) and the file
  records `includesSecrets` so a reader can warn. The wrapped value (`pref_backup_password_enc`) is
  device-bound and is *never* written.
- **A document belongs to one client.** The clients' key sets do not correspond: Android stores
  `pref_*`, desktop `ui_sort_mode`/`ui_inactivity_lock_seconds`, iOS the same delay under another
  name. A shared catalog would have silently applied the one or two colliding keys and dropped the
  rest, so the document carries `platform` and `PreferencesExport.import` throws
  `WrongPlatformException` **before any write**. Cross-client transfer needs real per-key semantics
  (a mapping, not a rename); until that exists, a hard refusal is the honest behaviour.

`:core` `PreferencesExport` (Android-free, 13 unit tests) owns the format:
`{"format":"offlineotp.preferences","version":1,"platform",…,"includesSecrets","settings","skipped"}`,
bounded at 512 KiB, 512 keys, 64 KiB per string and 256 values per set, with `Kind` BOOLEAN /
INTEGER / STRING / STRING_SET / SECRET / DEVICE_BOUND. Device-bound keys are in a separate
`neverExported` list and are **not even named** in `skipped`: a salt or a SAF URI in the file would
tell an attacker what to look for. `Value.Text.toString()` hides its content so a logged value is
not the value.

| | export | import |
|---|---|---|
| Android | Settings → **Export preferences…** → password question → `ACTION_CREATE_DOCUMENT` | Settings → **Import preferences…** → `ACTION_OPEN_DOCUMENT` → dry-run probe → confirm → apply |
| Desktop | **Tools → Export preferences…** (JFileChooser) | **Tools → Import preferences…** (bounded read, overwrite confirm) |
| iOS | Settings → **Preferences → Export preferences…** (`fileExporter`) | **Import preferences…** (`fileImporter`, security-scoped read) |

The dry-run is the Android import's one interesting rule: the bytes are replayed into a
`SettingsPort` that refuses every write, so a document that would fail halfway is rejected while the
vault is still untouched. The secret route on Android is `Settings.setBackupPassword`, which
re-wraps through the Keystore — a plaintext password never lands in `SharedPreferences` — and a
failure there throws rather than reporting a half-applied import. After an iOS or desktop import,
both the inactivity timeout (`ui_inactivity_lock_seconds`) and the sort mode (`ui_sort_mode`) are
re-read (`VaultStore.reloadInactivityLock()` on iOS; `selectSortChoice` + `armInactivityTimer` on
the desktop), because the values they display were just replaced underneath them.

## 4. Not closed here, and why

- **Paper backups (#178)** — **declined, not postponed.** Print a sheet holding what? A QR of the
  encrypted vault duplicates a file the user can already copy; per-entry `otpauth://` QRs on paper
  turn a stolen sheet into a second factor with no password in front of it; a recovery-code sheet
  needs a format and an error-correction budget nobody here can validate without printing and
  scanning. This fork's answer is that the encrypted vault file *is* the offline backup (see the
  retention section above and `docs/backup-format.md`); paper adds an exposure surface without
  removing a dependency. Recorded as won't-do so the gap stops reading as an oversight.
- **Nitrokey native OTP (#921)** needs the Nitrokey Pro's own OTP protocol over USB with a device to
  test against. The existing FIDO2 PRF unlock is the protocol-based path and stays **PARTIAL**; there
  is no hardware in this environment to verify against, and a protocol implementation that has never
  met a device is not a completion.
- **Automatic OpenPGP backups (#531)** — the prompt question is **answered**: when this is built,
  automatic OpenPGP backups run *silently* from the saved recipients and key file, mirroring today's
  password auto-backup, with retention applied by the same `BackupRetention` rule (which already
  matches `.json.gpg`). What remains is not a decision but the work: an automatic trigger that
  reads the saved recipients instead of asking, and a failure path that cannot turn a completed
  password backup into a reported OpenPGP failure. Deliberately not half-built here — a
  "semi-automatic" variant that prompts on some saves is the one behaviour the decision rules out.

## Verification

Every gate was run locally, as the tree was changed:

| gate | result |
|---|---|
| `python3 -m unittest discover -s scripts/tests` | 446 tests, OK (2 skipped) |
| `scripts/run-jvm-tests.sh` | 358 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/run.sh` | all sandbox checks passed (incl. 3 new ones) |
| `scripts/kotlin-sandbox/compile-desktop.sh` | 191 tests, 179 passed, 0 failed, 12 skipped |
| `scripts/kotlin-sandbox/compile-core.sh` | 337 tests, 337 passed (`BackupRetentionTest`, `PasswordCsvExportTest`, `PreferencesExportTest`) |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 20 androidTest files, COMPILE OK |
| `scripts/kotlin-sandbox/generate-r-stub.py --check` | R stub up to date |

The iOS target is **CI-only** (no Xcode or `swiftc` in this sandbox), and the iOS workflow does not
run on this branch, so the Swift was reviewed by hand rather than compiled. That review found one
pre-existing break: `KeyValueSettings` never declared `remove(_:)`, which
`PasswordVault.changePassword`'s rollback and `VaultStore.setInactivityLockSeconds(0)` both call —
fixed here, defaulted on the protocol and implemented by `PropertyListSettings`, because it fails
the whole module rather than one feature.

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
- `PreferencesExportTest` (13, `:core`), `PreferencesExportTests` (6, `OfflineOTPKitTests`),
  `PreferencesExportDesktopTest` (3, desktop) and the sandbox check *portable preferences export
  reads and writes the real Android store*: the round trip through a real store and back, the type
  of an imported integer and boolean preserved, the device-bound salt neither written nor named,
  and a desktop-addressed document refused with **no write at all** (the store is compared before
  and after). The core cases cover the five secrets-free / five-with-secrets counts, a value of the
  wrong type reading as absent rather than coerced, each size bound, a truncated or foreign
  document, a `version` this build does not know, and the platform refusal.
- `scripts/tests/test_preferences_export.py` (10 guards) pins the format policy and the wiring: the
  device-bound key list, the absence of any OpenPGP secret in the file, the `platform` field and
  `WrongPlatformException` on both Kotlin and Swift, the desktop catalog matching the keys
  `VaultController` actually stores, the Android rows and the wipe-on-cancel path, the desktop menu
  and dialogs, the iOS section and port, and that the sandbox executes the port.
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
