# Android / desktop parity review — 2026-11-02

Both clients share `:core` for the vault, entry model, OTP calculation, parsers and backups, so the
interesting drift is in the thin shells: what each client exposes and what it does before it writes.
This pass compared every user-facing action of the two clients and fixed the four places where the
same vault behaved differently.

Environment: no Android SDK, emulator or physical device, as in earlier passes. The claims below come
from the source tree plus the gates that run here: `scripts/kotlin-sandbox/run.sh`,
`scripts/run-jvm-tests.sh`, `scripts/kotlin-sandbox/compile-desktop.sh` and the Python source guards
in `scripts/tests`. Android lint, packaging and the opt-in Swing display tests remain CI gates.

## Fixed: four behaviours that differed

| Behaviour | Android | Desktop before | Now |
| --- | --- | --- | --- |
| List order | Five-mode sort menu (Unsorted, Issuer, Label, Last used, Most used), persisted as the `sort_mode` setting | No sort control at all; always the vault order | **Sort** dropdown with the same five modes, persisted as `ui_sort_mode` in the client settings file (`data/vault.properties`), restored on unlock |
| Import timestamps | An imported entry gets `updateOTP(false)` and `setLastUsed(now)` | Imported entries kept whatever timestamps the file carried, so they sank to the bottom of a usage-ordered list | Same stamping as Android, applied before the write so the sorted order is stored |
| Entry ceiling | `EntriesCardAdapter.addEntry`/`importEntries` refuse work at `InputPolicy.MAX_ENTRIES` (2000) | No ceiling in the editor: a vault could be grown past the limit Android enforces, and Android would then refuse to extend it | `EntryEditor.add` refuses at the ceiling and `importEntries` rejects an over-large batch as a whole instead of writing it |
| Removal | `dialog_msg_confirm_delete`: *Are you sure you want to remove the account "%1$s"?* | The **Delete** button and the context-menu action removed the entry immediately | Both paths ask the same question, naming the entry (its label, or its issuer when the label is blank) |

Details worth knowing:

- The sort preference is a *client* preference, not vault data. It is written to the settings file
  next to the vault, never into `secrets.dat`, so sorting does not change the vault's bytes and an
  older desktop build still opens it. An unreadable or unknown stored value falls back to
  **Unsorted** instead of failing the unlock.
- Sorting composes with the existing filters: search and the tag dropdown keep working inside the
  chosen order, and switching back to **Unsorted** restores the vault order rather than freezing the
  previous sort.
- The import ceiling matches the vault writer: `VaultJson.entriesToString` already rejects more than
  `InputPolicy.MAX_ENTRIES`, so the editor now checks first and reports a rejected import instead of
  relying on the write to fail.

## Fixed in the follow-up pass: two more surfaces

The first pass left some differences in place; a second read showed two of them were fixable without
redesigning either client, so they are aligned too.

| Behaviour | Android | Desktop before | Now |
| --- | --- | --- | --- |
| Manual order | Dragging a card reorders the vault in the unsorted, unfiltered view (`canDrag()`) | No way to reorder; only the order in which entries were added | **Move up**/**Move down** in the row menu and Alt+↑/↓, gated by the same shared `EntryListPresentation.canDrag()` rule and persisted to the vault |
| Per-entry QR code | **Show QR code** renders the `otpauth://` URI, hidden for notes, SSH keys, password entries and email accounts | Only QR *import* existed (from an image file) | **Show QR code…** in the row menu renders the same URI with zxing and is hidden for exactly the same entry kinds |

The manual order is a vault property, not a view property: a move is written through the same
`EntryEditor` path as every other mutation, so a failed write leaves the visible order untouched, and
selecting any sort order disables the action (as Android hides the drag handle).

## Fixed in the same pass: restore semantics (plain-text restore removed)

A later read of the backup surface found two more differences, both about what a restore does to the
vault that is already open.

| Behaviour | Android | Desktop before | Now |
| --- | --- | --- | --- |
| Merge versus replace | The backup screen has a **Replace existing entries** switch (`backup_title_replace`), off by default: a restore appends the backup's new entries and keeps the current ones | Restore always replaced the whole vault, after a warning that said so | `BackupCoordinator.restoreEncrypted`/`restoreOpenPgp` take `replace`; every restore dialog asks with the same switch wording and the same default, and the result text says *Merged N new entries* rather than claiming a restore |
| Plain-text restore | **Restore plain-text backup…** imported a legacy unencrypted JSON file through `PlainTextBackupDecoder` | No plain-text path at all | Removed on all clients — only **Full Encrypted Backup** and **OpenPGP** remain; the decoder, task, and warning strings are deleted |

Merging follows Android's branch exactly: an entry the vault already holds is not duplicated and
keeps its counters, usage metadata and tags, the imported entries come first, and a backup whose
entries collide with the current ones is rejected rather than silently collapsing two accounts. In a
replace the entries the backup drops are zeroized, as a fresh unlock would do.

### One suspected difference that turned out not to exist

`EntriesCardAdapter.setCategory`/`filterByTags` call `clearFilter()`, which reads like "a tab or tag
change drops the search". It does not: `clearFilter()` only discards the in-flight `Filter` object,
while the query stays in `EntryListPresentation` and `refreshTags()` re-runs it against the new
category and tag set. The sandbox check *the adapter keeps a live search across tag and sort
refreshes* asserts exactly that. An early attempt to make the desktop drop the search on a tab or tag
change was reverted, and both clients now keep a live search across tab, tag and sort changes; the
desktop test `aLiveSearchSurvivesTabAndTagChangesLikeAndroid` pins the shared rule.

## Deliberately left alone

- **Tag drawer versus tag dropdown.** Android offers AND/OR combination of several tags, a "no tags"
  filter and the search-includes-tag preference; the desktop client offers one tag dropdown plus the
  untagged choice. Selecting one tag filters identically under AND and OR, so the single-tag case
  matches exactly; only multi-tag combination has no desktop equivalent.
- **Search fields.** Android lets you choose whether search covers labels, issuers and tags; the
  desktop client always searches all three. The preference lives in Android's settings, which the
  desktop client deliberately does not read or edit. (The live search itself, and its survival
  across tab, tag and sort changes, is identical — see above.)
- **OpenSSH-key keystroke output.** Android's SSH popup can type the key through NFC or a YubiKey in
  OTP mode via `menu_send_keystrokes`. The desktop client has no hardware keystroke path (it
  supports PC/SC OATH only), which the README lists as out of scope.
- **Settings editor, inactivity lock, automatic backups, master-password change.**
  Both are opt-in on Android and off by default (`getAuthInactivity()`, the automatic-backup keys),
  so an untouched Android install and the desktop client behave the same; only an Android user who
  turns them on gets behaviour the desktop client has no equivalent for.
  Android preferences with no desktop equivalent; documented as out of desktop v1 scope rather than
  half-built. A password change has to re-key the vault inside a transaction (`changeVaultEncryption`
  on Android); a desktop version that is simpler than that would risk leaving a vault whose stored
  credentials no longer match its key.
- **Custom per-entry thumbnails** (`menu_popup_changeImage`). Android stores user-picked images next
  to the vault; the desktop client shows the bundled issuer logos only.
- **Sort control surface.** Android keeps sort in the overflow menu, which it hides while the search
  view is open; the desktop client keeps a **Sort** dropdown in the search row, visible at all times.

## One implementation, two clients

Closing the gaps by writing the desktop side of each rule a second time would have left two copies
to drift apart again, which is how the differences in this document appeared in the first place. The
rules both clients need now live in `:core` and both shells call them:

| Shared helper (`:core`) | What it owns | Used by |
| --- | --- | --- |
| `Utilities/EntryUsage` | the last-used/frequency stamp of one use (`recordUse`), the stamp a new or imported entry gets (`markImported`), and the one-step HOTP advance with its ceiling (`advanceHotp`) | Android `EntriesCardAdapter`, `ManualEntryDialog`, `MainActivity`; desktop `EntryEditor` |
| `Database/EntryTagFilter` | the tag selection rule (AND/OR, tagless switch) and the tag-name ordering both clients list | Android `TagsAdapter`; desktop `DesktopVaultState`, `MainWindow`, `TagDialog`; `EntryList` itself |
| `Utilities/VaultRestoreMerge` | the merge-or-replace plan a restore applies: imports first, entries the vault already holds kept as they are, duplicates destroyed rather than duplicated | Android `VaultRestoreOperation`; desktop `BackupCoordinator` |
| `EntryListPresentation.categoryOf` | the entry kind → tab mapping (the projection's `matches` now uses it too) | Android `showEntryCategory`; desktop `MainWindow.addEntry` |
| `Entry.hasOtpUri()` | whether an entry has an `otpauth://` form at all | Android QR popup visibility and `showQRCode`; desktop `QrCodeImage` |
| `Database/EntryDraft` | assembling a manual-entry form into an entry: which kinds the form offers, which constructor each one needs, what a blank period means, typed digits/period/counter parsing, which text keeps its whitespace, and the usage stamp a new entry gets | Android `ManualEntryDialog`; desktop `ui/EntryDialog` |

The shells keep what is genuinely platform-specific — dialog rendering, file choosers, Android
`Filter` plumbing, the desktop's synchronous projection, the desktop-only mOTP PIN field — and
nothing else repeats a shared rule.
`scripts/tests/test_shared_client_logic.py` fails the build if a shell re-implements any of these
(tag `containsAll` predicates, `getUsedFrequency() + 1` counting, the HOTP ceiling literal, a
hand-built merge list, a per-type `Entry(...)` construction in a dialog) or stops calling the shared
helper.

The last extraction also settled two small differences the earlier passes had missed: the desktop
form kept whitespace inside a pasted secret (so a wrapped Base32 secret that the phone accepted was
rejected there), and a hand-typed entry was never stamped as used (so it sank to the bottom of a
**Last used** list while the same entry added on the phone went to the top). Both now follow from
`EntryDraft`.

## Verified identical

Email tabs and their actions, backup formats and the dated export names, QR import with the
duplicate-issuer confirmation, the password and SSH editors, entry metadata (last used, usage
frequency, details visibility) preserved by every edit, the master-password vault format
(`secrets.dat`, PBKDF2 + AES-GCM) and the reveal/copy rules. Each of these is covered by shared
`:core` code or by existing desktop tests, so the review checked the call sites rather than
re-deriving the behaviour.

## A third client

The same rules apply to a third client: the native SwiftUI iOS app in
[docs/ios-client-plan.md](ios-client-plan.md). It cannot share `:core` code (the module is JVM-only),
so it shares the *formats* instead — the vault bytes, the encrypted-backup framing and the entry
JSON, pinned by the fixed-vector corpus in `desktop/src/test/resources/golden/` — and ports the
shared *rules* one file at a time. The parity rule that matters most for it is the one this review
kept returning to: an unsupported kind or field is **preserved verbatim**, never dropped, because a
save must not silently destroy another client's data.

The iOS client now has the same surfaces as the other two, and each one is a port rather than a
reinterpretation:

| Rule | Shared source | iOS port | Pinned by |
| --- | --- | --- | --- |
| Entry kind → tab, and the tab order and titles | `EntryListPresentation.Category`/`categoryOf`, Android's `tab_*` strings, desktop's `categoryTitle` | `EntryCategory` | `EntryListProjectionTests`, `test_ios_app.py` |
| Search over labels, issuers and tags; a live search survives tab, tag and sort changes | `EntryList.getFilteredEntries`, `EntryListPresentation.refreshTags` | `EntryListProjection.matchesSearch`, `visibleIndices` | `EntryListProjectionTests` |
| Tag selection (any/all/one) and tag-name order | `EntryTagFilter.matches`/`sortedNames` | `EntryTagFilter` | `EntryListProjectionTests`, `test_ios_app.py` |
| The five orders, stable ties, `unsorted` restoring the vault order | `EntryList.sortEntries` and its comparators | `EntryListProjection.ordered` | `EntryListProjectionTests` |
| The sort preference is a client preference, stored as `ui_sort_mode` | `VaultController.SORT_MODE_KEY` | `SortMode.settingsKey` in `vault.properties.plist` | `VaultStoreTests`, `test_ios_app.py` |
| A use stamps `last_used` and adds one to `used_frequency`; an import stamps only; a step is one counter below `MAX_COUNTER` | `EntryUsage` | `EntryUsage` | `EntryUsageTests`, `test_ios_app.py` |
| A manual form's kinds, whitespace rule, wording and metadata preservation | `EntryDraft`, `EntryDialog.preserveEditMetadata` | `EntryDraft` | `EntryDraftTests`, `test_ios_app.py` |
| A concealed entry shows no code and offers no copy; a reveal is vault data | Android `privacyButton`/`EntriesCardAdapter`, desktop `EntryEditor.setDetailsVisible` | `VaultStore.code(for:)`, `setDetailsVisible` | `VaultStoreTests` |
| A removal asks by name | `dialog_msg_confirm_delete`, desktop `MainWindow` | `EntryListView` alert | `EntryListView` |
| `otpauth://` parsing, in both directions, with the same refusals | `OtpUriParser`, `ValidatedOtp.create`, `OtpParseError` | `OtpUri` | `OtpUriTests`, `test_ios_app.py` |
| An entry's QR payload and its field order (`secret`, `issuer`, `counter`, `period` when not 30, `digits` when not 6, `algorithm` when not SHA1, then `tags`), `%20` never `+` | `Entry.toUriString` | `OtpUri.uri(for:)` | `OtpUriTests`, `test_ios_app.py` |
| The logo name an entry stores is resolved from its issuer (exact, alias, whole word, default), so the other clients show the same mark | `ThumbnailCatalog.resolveName`, `Entry.initialize`, `Entry.setThumbnailFromIssuer` | `ThumbnailCatalog`, used by `EntryDraft` and `OtpUri.entry` | `ThumbnailCatalogTests`, `test_ios_app.py` |
| A QR payload is classified before it is parsed; a migration export is named, not guessed at | `QrImport.classify` | `QrPayload.classify` | `OtpUriTests` |
| A backup is dated (`otp_accounts_<yyyy-MM-dd_HH-mm-ss>.json.aes`) with ASCII digits | `BackupFileNames` | `BackupFileNames` | `BackupRestoreTests`, `test_ios_app.py` |
| A plain-text backup was decoded strictly and never written — now removed | `PlainTextBackupDecoder` (deleted) | `PlainTextBackupDecoder` (deleted) | Plain-text tests deleted; guards assert the path is gone |
| A restore merges by default (imported first, the vault's copies kept) or replaces; an empty import is refused | `VaultRestoreMerge`, `VaultRestoreOperation` | `VaultRestoreMerge` | `BackupRestoreTests`, `test_ios_app.py` |
| A second account from a known issuer asks **Issuer already exists** / **Add anyway** before it is added | Android `showDuplicateIssuerConfirmation`, `dialog_msg_duplicate_issuer`, desktop `DuplicateIssuerPolicy` | `VaultStore.importUri`/`importPayload` and `ImportOutcome` | `VaultStoreTests`, `test_ios_app.py` |
| The vault key is released by the platform after a biometric check, never derived from anything stored on the device: the file, the KDF and the password are unchanged | iOS `KeychainBiometricKeyStore`, Android `BiometricKeyStore` | `VaultStore.unlockWithBiometrics` (iOS), `AuthenticateActivity` + `BiometricKeyStore` (Android) | `BiometricUnlockTests` / `test_ios_app.py` (iOS), `test_manifest_source_policy.py` + manual enrollment + `BiometricKeyStore` instrumentation (Android) |
| Two entries are the same account when their kind, token parameters, seed, name, issuer and SSH target match — the usage stamp, counters of use, tags and reveal state are metadata | `Entry.equals`/`hashCode` | `Entry.==`/`hash(into:)` | `VaultStoreTests`, `test_ios_app.py` |
| A Google Authenticator export is read code by code and written only when every code of it has arrived; a repeated or foreign code is refused without ending it, and an identical account is skipped | `GoogleAuthenticatorMigration`, `MigrationBatchCollector`, Android's `google_import_*` strings, `EntriesCardAdapter.importEntries` | `MigrationPayload`, `MigrationBatchCollector`, `MigrationWording`, `VaultStore.addMigrationImportCode`/`confirmMigrationImport` | `MigrationImportTests`, `VaultStoreTests`, `test_ios_app.py` |

One difference was removed rather than documented: Android could unlock the vault with a four-digit
PIN, which neither the desktop nor the iOS client ever offered, and which was the weakest credential
any of the three accepted. It is gone from the enum, the wizard, the unlock screen and the settings
dialog, and the deprecated `pref_auth_pin` keys are deleted instead of migrated, so all three clients
now ask for the same thing — a password — or defer to Android's device credentials. A vault an older
release set up with the PIN login is read as a `password` vault: the bytes are unchanged, the same PIN
typed into the password field derives the same key, and
**Settings → Authentication** can give it a real password
(`AuthenticationFlowDeviceTest.aVaultStoredAsAPinStillOpensThroughThePasswordPrompt`, guarded by
`PinLoginRemovalTest`). Entry-level mOTP PINs and hardware-key PINs are data, not logins, and are
unchanged on both clients.

Two things are deliberately *not* the same, and both are recorded in the plan: the iOS client cannot
create an SSH key (its seed is `SshKeyMaterial`-framed material this build does not implement — such
entries are read, listed, revealed and written back unchanged); and it always searches all three fields,
which is what the desktop client does, since Android's narrower search preference lives in its own
settings that neither other client reads.

OpenPGP backups (`.json.gpg`) were the third difference — the clients used incompatible implementations
(Android via external OpenKeychain, desktop via embedded PGPainless, iOS with none). That is now in
progress via the unified Rust backend (`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI) which provides
one implementation for all five distributions. The crate builds a `cdylib` for JVM targets (JNA) and a
`staticlib`/`XCFramework` for Apple targets; `RpgpOpenPgpPort` on desktop and Android and
`RustOpenPgpPort`/`OpenPgpBackupCodec` on iOS wrap it. Every per-platform workflow now builds and tests
the crate on the host; per-ABI packaging and removal of PGPainless remain before the format is truly
identical, but the design gap is closed.

### Fixed: entry edits now reliably autosave

All three clients autosave on entry edits (issuer, label, secret parameters, tags, SSH
connection) — the vault file is written before the in-memory list is published, so a failed
write leaves the previous entry unchanged. On Android the edit path previously mutated the live
entry outside the vault lock and relied on a two-step callback; if the session changed between
the dialog closing and the save, the in-memory entry stayed dirty while the file was untouched.
`EntriesCardAdapter` now exposes `replaceEntry(existing,replacement)` which validates the
draft, checks for duplicates, writes the whole vault atomically under `MutationLock` and only
then swaps the entry in the list, matching `EntryEditor.replace` on the desktop and
`VaultStore.update` on iOS. `ManualEntryDialog` builds a new entry through `EntryDraft`
instead of calling `applyEdit` on the live object.

The desktop **New vault** / **Open vault** header buttons and **Vault** menu did not exist on Android. Android shows its first-run wizard or lock screen automatically; the desktop had two always-visible buttons that did the same work through a different shell path. The header buttons and menu are removed and `MainWindow` now auto-prompts (create on first launch, open while locked) like `MainActivity`/`AuthenticateActivity`, reusing the existing controller paths so an older build still opens the same `secrets.dat`/`vault.properties`.

Both mobile clients now have an optional **biometric** quick unlock — **Face ID / Touch ID** on iOS and **fingerprint or face** on Android — and the desktop has none. In both cases it is not a format change and it does not touch the vault — it releases the derived key from a platform-bound item (Keychain `kSecAccessControlBiometryCurrentSet` on iOS, KeyStore `BIOMETRIC_STRONG` on Android) that never leaves the device, so the password still opens the vault and the other clients see exactly the bytes they wrote. Android uses `USE_BIOMETRIC` and only strong biometrics; weak face is not accepted. The rules, and the reasons for them, are in [the iOS client plan](ios-client-plan.md#face-id-and-why-it-does-not-weaken-the-vault) and mirrored in `BiometricKeyStore.kt` on Android.
