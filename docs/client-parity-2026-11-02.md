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

## Fixed in the same pass: restore semantics and plain-text restore

A later read of the backup surface found two more differences, both about what a restore does to the
vault that is already open.

| Behaviour | Android | Desktop before | Now |
| --- | --- | --- | --- |
| Merge versus replace | The backup screen has a **Replace existing entries** switch (`backup_title_replace`), off by default: a restore appends the backup's new entries and keeps the current ones | Restore always replaced the whole vault, after a warning that said so | `BackupCoordinator.restoreEncrypted`/`restoreOpenPgp` take `replace`; every restore dialog asks with the same switch wording and the same default, and the result text says *Merged N new entries* rather than claiming a restore |
| Plain-text restore | **Restore plain-text backup…** imports a legacy unencrypted JSON file through `PlainTextBackupDecoder`, behind `backup_plaintext_warning_message` | No plain-text path at all | **Tools → Restore plain-text backup…** uses the same shared decoder and the same warning text, and still never *writes* an unencrypted backup |

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
- **Settings editor, inactivity lock, automatic backups, biometrics, master-password change.**
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
hand-built merge list) or stops calling the shared helper.

## Verified identical

Email tabs and their actions, backup formats and the dated export names, QR import with the
duplicate-issuer confirmation, the password and SSH editors, entry metadata (last used, usage
frequency, details visibility) preserved by every edit, the master-password vault format
(`secrets.dat`, PBKDF2 + AES-GCM) and the reveal/copy rules. Each of these is covered by shared
`:core` code or by existing desktop tests, so the review checked the call sites rather than
re-deriving the behaviour.
