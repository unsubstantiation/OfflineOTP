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

## Deliberately left alone

- **Filters across tab changes.** Android's `setCategory`/`filterByTags` call `clearFilter()`, so
  switching tab drops an active search; the desktop client keeps search and tag filter when you
  change tabs (covered by `DesktopVaultStateTest.tagSearchAndCategoryComposeAcrossMutations`). The
  desktop behaviour is the more useful one and is tested, so it stays — changing it would be a
  behaviour removal, not a fix.
- **Tag drawer versus tag dropdown.** Android offers AND/OR combination, a "no tags" filter and the
  search-includes-tag preference; the desktop client offers one tag dropdown plus the untagged
  choice. Same reach for the common case, fewer settings to migrate.
- **OpenSSH-key keystroke output.** Android's SSH popup can type the key through NFC or a YubiKey in
  OTP mode via `menu_send_keystrokes`. The desktop client has no hardware keystroke path (it
  supports PC/SC OATH only), which the README lists as out of scope.
- **Settings editor, inactivity lock, automatic backups, biometrics.** Android preferences with no
  desktop equivalent; documented as out of desktop v1 scope rather than half-built.

## Verified identical

Email tabs and their actions, backup formats and the dated export names, QR import with the
duplicate-issuer confirmation, the password and SSH editors, entry metadata (last used, usage
frequency, details visibility) preserved by every edit, the master-password vault format
(`secrets.dat`, PBKDF2 + AES-GCM) and the reveal/copy rules. Each of these is covered by shared
`:core` code or by existing desktop tests, so the review checked the call sites rather than
re-deriving the behaviour.
