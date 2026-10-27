# OfflineOTP bug hunt — historical findings

> This is the historical audit snapshot of the earlier tree/branch named below. For the latest
> follow-up, see [`bughunt-follow-up-2026-10-25.md`](bughunt-follow-up-2026-10-25.md); the
> earlier [`bughunt-follow-up-2026-10-18.md`](bughunt-follow-up-2026-10-18.md) and
> [`bughunt-follow-up-2026-10-17.md`](bughunt-follow-up-2026-10-17.md) are also available.

Audit of `nuipona/OfflineOTP` at `c3618ade` (branch `arena/b9338704-offlineotp`), Kotlin
conversion of andOTP with the vault-security hardening from PR #23.

## 1. Confirmed defect — "Establish PIN" is unreachable from the card popup

**File:** `app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntriesCardAdapter.kt`
(`showPopupMenu`), **resource:** `app/src/main/res/menu/menu_popup.xml`

`menu_popup.xml` declares six items in this order:

| index | id |
| --- | --- |
| 0 | `menu_popup_edit` |
| 1 | `menu_popup_changeImage` |
| 2 | `menu_popup_show_qr_code` |
| 3 | `menu_popup_establishPin` (`android:visible="false"`) |
| 4 | `menu_popup_remove` |
| 5 | `menu_send_keystrokes` |

`showPopupMenu` revealed the mOTP PIN row with

```kotlin
val item = popup.getMenu().getItem(ESTABLISH_PIN_MENU_INDEX)   // == 4
item.setVisible(true)
```

`ESTABLISH_PIN_MENU_INDEX` was `4`, i.e. `menu_popup_remove`. Consequences:

* `menu_popup_remove` was set visible (a no-op — it is already visible), and
* `menu_popup_establishPin` stayed `android:visible="false"`, so the **"Establish PIN" action
  never appears in the per-entry popup menu**, even for mOTP entries.

`EntriesCardAdapter.establishPIN(position)` is therefore only reachable through
`establishPinIfNeeded(position)`, which fires from the single/double-tap handlers. An mOTP entry
whose PIN has never been set can still be revealed/copied (the code shows the `PINREQ`
placeholder), but the user cannot set the PIN from the menu that is supposed to offer it.

**Fix applied:** look the item up by id instead of by position, so the behaviour no longer depends
on the menu's item order:

```kotlin
popup.getMenu().findItem(R.id.menu_popup_establishPin)?.setVisible(true)
```

`ESTABLISH_PIN_MENU_INDEX` was removed (it had no other reference).

> Provenance note: the identical constant (`= 4`) and the identical `menu_popup.xml` exist in
> upstream andOTP master, so this is an **inherited** bug rather than one introduced by this fork's
> Historical audit note: this defect was fixed in the audited tree. Re-check the current source before treating this finding as open.

**Regression guard added:** `scripts/tests/test_popup_menu_item_lookup.py` (3 tests). It fails if a
positional `getMenu().getItem(...)` lookup or a `*_MENU_INDEX` constant reappears, if any
`R.id.menu_*` referenced from Kotlin stops being declared in `res/menu/`, or if
`menu_popup_establishPin` stops being revealed by id for mOTP entries. Verified by reintroducing the
original bug: 2 of the 3 tests fail.

> Follow-up (fixed in a later commit): the mOTP PIN was **not persisted** — it was absent from
> `EntryJson`, the vault JSON and the `otpauth://motp` URI, so it was session-only and had to be
> re-entered after each app restart. It is now stored as a `pin` field, written only for an mOTP
> entry that actually has one, so every other document stays byte-identical to what the Java
> serializer produced. See section 3a below.

## 2. Confirmed defect — stray `%` in a string resource

**File:** `app/src/main/res/values/strings_backup.xml`

```xml
<string name="backup_toast_export_failed">Export to external storage failed</string>%
```

A `%` sits as character data between elements inside `<resources>`. It is not part of any string
value and is not referenced anywhere; it is leftover junk (present in upstream as well). Removed.
The same scan (`</tag>` followed by non-whitespace, non-`<` text) found no other occurrence in any
`values*/` file, including the translations.

## 3. Audited and cleared

No further functional or security defect was found. Everything below was read in full and compared
against upstream andOTP master (`/tmp/up/andOTP-master`) and/or the repo's own documented deltas:

* **Every app source file** (87 Kotlin files; the sandbox compiles 86 of them, all but the
  4-line `play` `AboutFragment`). Full read + upstream Java comparison of the crypto core
  (`EncryptionHelper`, `KeyStoreHelper`, `SecretKeyWrapper`), the vault layer (`DatabaseHelper`,
  `VaultSession`, `SessionContracts`, `VaultContracts`, `EntryPersistence`,
  `VaultRestoreOperation`), the model (`Entry`, `EntryList`, `EntryJson`, `EntryJsonResult`), the
  parsers (`OtpUriParser`, `OtpParseResult`, `InputPolicy`), the settings facade (`Settings`), the
  tasks and receivers, all six activities plus the adapters/dialogs/preferences/fragment, and the
  helpers (`Tools`, `UIHelper`, `NotificationHelper`, `FileHelper`, `StorageAccessHelper`,
  `BackupHelper`, `EntryThumbnail`, `LetterBitmap`, `TokenCalculator`, `DimensionConverter`,
  `EditorActionHelper`, `BoundedInputReader`, `BoundedOutput`, `EncryptedBackupHeader`,
  `ConfirmedPasswordTransformationHelper`, `ScreenTimerController`, `HandlerTimerScheduler`,
  `BackupAgent`, `AutoFillableTextInputEditText`, `ItemTouchHelper/*`).
* **`Constants.kt`** — every constant and every enum member matches upstream exactly (only the
  plain-text `BackupType.PLAIN_TEXT`, the two plain-text intent ids and the plain-text filename/
  mimetype constants are gone, which matches `BackupType = ENCRYPTED, OPEN_PGP, UNAVAILABLE` and
  the removed plain-text backup feature).
* **`EntryThumbnail.EntryThumbnails`** — all 350 enum constants match upstream by name *and* by
  drawable resource; no entry declares `AssetType.Bitmap` in either tree.
* **Resource tree** — `diff -rq` against upstream shows the only content change in `layout/`,
  `menu/`, `values/` and the `values-*` translations is `content_backup.xml` swapping
  `backup_label_warning_plain` for `backup_label_crypt` (plain-text backups were removed). The
  remaining differences are intentional: the rebrand (andOTP → OfflineOTP), the "Dark Forest"
  theme, the AboutLibraries 14.x attributes/colors, the `perf_theme_mode` → `pref_theme_mode`
  key spelling fix (with the documented `Settings.migrateDeprecatedSettings` migration), and the
  removed `plain` entry of `settings_values_backup_broadcasts`.
* **Dangling resource references** — every `R.<type>.<name>` reference in `app/src/main/kotlin`
  resolves against `app/src/main/res` (748 references; the 15 "unresolved" hits are all
  `android.R.*` framework resources or build-time generated values).
* **Numeric-literal diff** — a per-file multiset comparison of integer literals between upstream
  Java and the fork's Kotlin found no unexplained change (all differences map to documented
  hardening: the 20 000 000 / 2 000 000 PBKDF2 bounds, the 36/28/64 transaction framing, the
  8192-byte write chunk, the 60 000 ms broadcast throttle, the 16/24/32-byte AES key lengths, and
  constants moved into extracted helpers).
* **Automated gates** — all green after the fix:
  * `scripts/kotlin-sandbox/run.sh` → "All sandbox checks passed." (86 app sources compiled +
    ~90 behavioural checks on a desktop JVM).
  * `python3 -m unittest discover -s scripts/tests` → 166 tests, OK (2 skipped).
  * `app/src/test` JVM unit tests → 76 test methods, all passing (re-run locally with a
    hand-written JUnit-4 shim and reflective runner, since no Gradle/JUnit is available).
  * A token-level and operator-level diff of every method pair that exists in both trees found
    only syntax/type-name noise.

### Defects deliberately **not** reported as new

* `docs/vault-security-hardening.md` states that "Password/seed widgets do not automatically save
  their text into Android view state". No layout sets `android:saveEnabled="false"` /
  `android:freezesText="false"` and no activity overrides `onSaveInstanceState` for them, so the
  claim was stronger than the implementation at that snapshot. Subsequent changes disabled saved
  view state for these fields; see the [current security record](vault-security-hardening.md).
* The audit also characterized `\p{Cntrl}` as Unicode `Cc`. Correction: on a desktop JVM without
  `UNICODE_CHARACTER_CLASS`, this POSIX class matches ASCII controls; Unicode mode includes C1
  controls such as U+0085. Android regex behavior should be verified separately rather than
  inferred from the unchanged pattern alone.
* `Settings.getAndroidBackupServiceEnabled()` returning a hard `false` and
  `setAndroidBackupServiceEnabled()` ignoring its argument are the documented platform-backup
  kill switch, not a defect.

## 3a. Follow-up fix: the mOTP PIN is now persisted

`EntryJson` now carries a `pin` field, written only for an mOTP entry that actually has one - the
same conditional rule `period`/`counter` already follow. So:

* every TOTP/HOTP/Steam document, every PIN-less mOTP document and every document written by an
  older version stays **byte-identical** to what the Java serializer produced (`STORED_JSON`, the
  round-trip tests and the `security/{current,legacy}.aes` fixtures are unchanged);
* a missing key reads back as an empty PIN, a non-string PIN is `INVALID_TEXT` and an oversized one
  `TEXT_TOO_LONG` (`InputPolicy.MAX_TAG_CHARS`, the bound `Entry.validate` already applied);
* the PIN now survives a vault save/load and travels with an encrypted backup and its restore.

`Entry.setPin` enforces that bound (previously a long PIN would have made
`DatabaseHelper.entriesToString` throw `VaultException(CORRUPT)` and break every save), the PIN
dialog caps what can be typed at the same bound, and `EntriesCardAdapter.establishPIN` writes the
vault after setting it so a reload or a failed-save revert cannot drop it again.

The PIN is still **not** part of the `otpauth://motp` URI, so a QR code never carries it.

## 4. Residual risk / what could not be verified here

* No Android SDK, Gradle, JUnit or emulator is available in this environment, so lint, the
  packaging/manifest/resource inspection, the instrumentation suites and both-flavor builds were
  not executed. The Kotlin sandbox and the JVM unit-test run are non-authoritative stand-ins.
* The 26 `app/src/androidTest` behavioural suites were read but not compiled or run; in particular
  `IpcAuthorizationSecurityTest`, `VaultFailureSecurityTest`,
  `CredentialLifecycleSecurityTest`, `BackgroundTaskCancellationSecurityTest`,
  `EncryptedRestoreSecurityTest` and `VaultRestoreOperationTest` encode expectations that match
  the source, but only a device run can confirm them.
* Concurrency (`VaultSession.MutationLock`, `DatabaseHelper.DatabaseFileLock`) cannot be exercised
  without an Android runtime; the guards were reviewed by reading only.
* The two local root commits (`1c6804c` = `master`, `c3618ad` = this branch's base) differ only in
  `LICENSE.txt`, so the fork's own history offers no pre-change oracle either.
