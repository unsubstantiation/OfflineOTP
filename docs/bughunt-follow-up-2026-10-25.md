# Bug hunt and fix pass, 2026-10-25

Another comprehensive pass over this checkout, after the 2026-10-17/18 bug hunts and the 2026-10-19 andOTP carry-over pass. Scope: every Kotlin source under
`app/src/main/kotlin` (94 files), both manifests, `app/src/test`, the sandbox/JVM harnesses and the
Python source guards. The 29 `res/values-*` translations and the `settings_*` arrays were checked
mechanically rather than re-read (results below).

This environment still has no Android SDK, Gradle distribution or device, so lint, packaging,
instrumentation and the emulator matrix stay CI/device gates (see `README.md`). The gates that run
here all pass, and every fix below was mutation-verified: the fix was reverted, the new test or
check was seen to fail, and the fix was restored.

## Findings fixed

### 1. The credential dialog accepted a password/PIN below the policy minimum

`Preferences/CredentialsPreference.kt` showed the "too short" warning but only recomputed the Save
button inside the *long-enough* branch, so after typing a valid credential and deleting characters
Save stayed enabled. `Settings.prepareAuthCredentials` deliberately does not enforce a minimum
(it also backs the legacy verifier upgrade used while unlocking, so a minimum there could lock out
existing short credentials), and `IntroScreenActivity` does enforce one — the settings dialog was
the hole. `Constants.AUTH_MIN_PASSWORD_LENGTH`/`AUTH_MIN_PIN_LENGTH` could therefore be bypassed
from Settings.

* Fix: the save state is now one shared predicate, `CredentialsPreference.canSaveCredentials`
  (NONE/DEVICE are saveable without a credential; a password/PIN must reach the minimum and match
  its confirmation), used by `onTextChanged`. `updateLayout()` also clears a stale warning when
  NONE/DEVICE is selected.
* Tests: `Checks.kt` covers the predicate for empty, short, mismatched, valid and NONE/DEVICE
  cases; `scripts/tests/test_kotlin_credential_gating.py` pins the UI call sites (the button state
  needs a bound `DialogPreference`, which the harness does not model).
* Mutation: restoring the old `onTextChanged` shape fails two of the new source guards.

### 2. Tag rows lost their identity once the entry counts decorated them

`TagsAdapter.getView` renders `"tag (n)"` when counts are set (#994), but `TagsDialog` derived the
clicked tag from `CheckedTextView.text`, so a counted row called `setTagState("tag (3)")`, which
matched no tag: the tick toggled visually and changed nothing. The adapter also kept two orders —
`ArrayAdapter`'s own list (what `getItem(position)` returns) was built from the unsorted `HashMap`
keys while `getView` bound the sorted `tagsOrder` — so a position was only meaningful after the
first `setTags` call. `ManualEntryDialog` constructs exactly such an adapter from the entry map, so
the two orders disagreed on a fresh dialog.

* Fix: the adapter's own list is sorted at construction (shared `sortedTagNames` helper, also used
  by `setTags`), and the dialog resolves the clicked tag by position (`tagsAdapter.getItem`), never
  by parsing display text.
* New-tag flow, same dialog: the input is capped at `InputPolicy.MAX_TAG_CHARS` (the bound
  `Entry.validate()`/`EntryJson` apply) and blank names are ignored, so a tag created here can no
  longer make the next vault write reject the entry.
* Tests: two new `Checks.kt` checks — decorated rows still toggle the right tag and `getItem` agrees
  with `getView` from construction; the new-tag dialog caps the length and drops blank/whitespace
  names.
* Mutation: reverting the adapter order fails on `getItem(0) == "zeta"`; reverting only the click
  handler fails on `the counted row did not toggle its tag`.
The same text-parsing shape existed in the tag drawer, fixed separately in finding 6.

### 3. Machine tokens were case-mapped with the user's locale

`Settings.getTagFunctionality()` did `valueOf(token.uppercase(Locale.getDefault()))`. Under Turkish,
`"single"` uppercases to `"SİNGLE"`, which is not an enum constant, so the entry/settings screens
threw `IllegalArgumentException`. `setEncryption`/`getEncryption`, `Settings.clear(keepAuth)`'s auth
token, `SettingsActivity` and `IntroScreenActivity` had the same default-locale shape (ASCII-safe
today, but the stored "pın" proves the hazard).

* Fix: every stored or parsed machine token uses `Locale.ROOT` (`Settings`, `SettingsActivity`,
  `IntroScreenActivity`).
* Same class of defect in user-facing matching: `EntryList.getFilteredEntries` and
  `ThumbnailSelectionAdapter.filter` lowercased both sides with the default locale, so under Turkish
  `"Instagram"` folded to `"ınstagram"` and searching `instagram`/`I`/`İ` missed. Both now use
  `contains(other, ignoreCase = true)`, which is locale-independent and still matches the
  dotted/dotless pair.
* Tests: `Checks.kt` seeds `tag_functionality = "single"` and the encryption preference with a
  Turkish default locale (restored in a `finally`); a second check filters the thumbnail picker
  under the same locale; `EntryListTest.searchMatchesIndependentlyOfTheDefaultLocale` covers label,
  issuer and tag searches.
* Mutation: the old uppercase throws `No enum constant ... TagFunctionality.S?NGLE`; the old filter
  returns `[]`; the old search fails 144/145.

### 4. Backup success and failure notifications replaced each other

`NotificationHelper.notify` posted every notification with the constant id `1`, so a later success
silently removed the failure it followed (and the reverse). The channels already differ.

* Fix: one stable id per channel (`BACKUP_FAILED = 1`, `BACKUP_SUCCESS = 2`) via
  `notificationId(channel)`.
* Test: a new `Checks.kt` check posts both channels through the recording notification manager and
  requires two distinct ids.
* Mutation: restoring `notificationId = 1` fails with `[1, 1]`.

### 5. Letter-tile colour index could leave the palette

`LetterBitmap.pickColor` computed `Math.abs(key.hashCode()) % NUM_OF_TILE_COLORS` against a
hard-coded count, while the palette is `R.array.letter_tile_colors`. Two defects in one:
`Math.abs` keeps `Integer.MIN_VALUE` negative (a negative index whenever the palette length does not
divide 2^31), and the constant silently decouples from the resource — the harness's one-colour
palette makes any hard-coded count an immediate `ArrayIndexOutOfBoundsException`, and a ninth colour
would never be used on device.

* Fix: `colors[Math.floorMod(key.hashCode(), colors.size)]`, with `Color.BLACK` for an empty palette.
* Test: a new `Checks.kt` check renders tiles for several keys (including `"polygenelubricants"`,
  whose hash is `Integer.MIN_VALUE`) and for the default thumbnail path.
* Mutation: the old code fails with `Index 1 out of bounds for length 1`.

### 6. The tag drawer ignored taps once the entry counts were shown

`MainActivity.setupDrawer`'s row listener derived the tag from `checkedTextView.text`, but
`TagsAdapter.getView` decorates the row with its entry count whenever `setTagCounts` ran — and
`EntriesCardAdapter.updateTagsFilter` sets the counts on every load and every save. Tapping a tag
therefore called `setTagState("Alpha (3)")`/`setTagToggle("Alpha (3)")`: no tag matched, the rebind
restored the checkbox, the filter did not change, and the preference store collected junk keys.

* Fix: the listener receives the adapter position and resolves
  `drawerAdapter.getItem(position)`, the same sorted list `getView` binds, then persists that tag.
* Test: `scripts/tests/test_kotlin_tag_drawer_click.py` pins the listener shape (the activity cannot
  be instantiated in the desktop harness); the sandbox check from finding 2 covers the adapter side.
* Mutation: parsing the row text again fails two of the new guards.

### 7. Notification channel ids were built from the user's locale

`NotificationHelper.channelId` produced `"andOTP_" + channel.name.lowercase(Locale.getDefault())`.
A channel id is a persisted machine token, so under Turkish the app created a second channel
(`andOTP_backup_faıled`) next to the existing one, with its own importance and sound settings and
no migration of the user's choices.

* Fix: `lowercase(Locale.ROOT)`.
* Test: a new `Checks.kt` check posts both channels under a Turkish default locale and requires the
  stored ASCII ids.
* Mutation: `Locale.getDefault()` again fails with `[andOTP_backup_fa?led, andOTP_backup_success]`.

### 8. Restoring an older backup rejected its own password

`Dialogs/PasswordEntryDialog` enforced `AUTH_MIN_PASSWORD_LENGTH` in both of its modes: `UPDATE`
(choose the password for a new backup) and `ENTER` (type the password of an existing backup file).
The `ENTER` minimum is wrong — the file was encrypted with whatever password it was created with,
and two supported paths produce shorter ones: backups made by older andOTP releases (no minimum
there), and backups made through Settings' own `PasswordEncryptedPreference`, which accepts any
length. Such a backup could not be restored at all: every typed password shorter than six
characters left OK disabled.

* Fix: the rules moved into `PasswordEntryDialog.canSubmit(mode, password, confirm)`. `ENTER`
  requires only a non-empty value; `UPDATE` keeps the minimum plus the confirmation check, and the
  "too short" warning is now shown only in that mode.
* Test: a new `Checks.kt` check covers both modes; the existing "password entry dialog keeps its
  enable rules" check was updated (it had pinned the wrong `ENTER` behavior).
* Mutation: restoring the shared minimum fails with `an existing backup's short password must remain
  submittable`.

### 9. A rejected manual edit stayed on the live entry

`ManualEntryDialog`'s edit path called the setters on the entry that is already part of the list and
only re-validated afterwards, through `updateOTP()`. The setters deliberately do not validate, so
when the model rejected the new period/digits/counter (10 digits, a zero period, `Long.MAX_VALUE`)
the exception was shown on a *hidden* field while the entry kept the invalid value — and the next
`updateOTP()` on that entry (card rebind, token refresh) threw out of `onBindViewHolder` and
crashed the main screen.

* Fix: `Entry.applyEdit(mutate)` applies the edit, validates it and rolls every value back when
  validation fails. The dialog's three edit branches now go through it, the rejection is reported
  with a new `error_invalid_entry` toast (the field error went to the hidden secret input), and the
  Save button uses `EntryActionDialogs.isValidCounter`, so `Long.MAX_VALUE` can no longer be typed
  into the counter dialog either (that was listed as a follow-up in the previous pass).
* Test: a new `Checks.kt` check edits a TOTP entry and an HOTP entry with invalid values and reads
  every field back; `test_ui_surface_regressions.py` pins the three branches to `applyEdit`.
* Mutation: dropping the rollback fails with `the rejected digits stayed on the entry: 12`.

### 10. `Entry.setTags` aliased the caller's list

`setTags` stored the caller's `MutableList` by reference. The edit dialog passes the tag adapter's
active list, and any later mutation of that list would silently rewrite the entry's tags. It now
stores a copy, which is also what the edit path relies on for its rollback snapshot.

* Test: the `applyEdit` check mutates the caller's list after a successful edit and requires the
  entry's tags to stay unchanged.

### 11. The app could not open the Steam/mOTP links it generates

`Entry.toUri()` (used by the QR-code export) emits `otpauth://steam/...` and `otpauth://motp/...`,
and `OtpUriParser` accepts them, but `MainActivity`'s `ACTION_VIEW` filter only listed `totp` and
`hotp`. Clicking or sharing any other kind of link never resolved to the app.

* Fix: the manifest filter lists all four hosts, and `scripts/manifest-policy.json` (which pins the
  exported data filters) was updated with them.
* Test: `test_manifest_source_policy.py` now derives the kinds from `Entry.OTPType` and requires a
  matching `otpauth` host in the exported filter.
* Mutation: deleting the steam `<data>` line fails both that guard and the policy comparison.

### 12. Dimension strings were unit-matched with the user's locale

`DimensionConverter.stringToInternalDimension` lowercased the unit suffix with
`Locale.getDefault()`. The suffix is a machine token, and Turkish lowercases `IN` to `ın` (dotless
ı), so `"2IN"`, `"12DIP"` and `"25.4MM"` threw `NumberFormatException` and
`Settings.getThumbnailSize()` silently fell back to the default size.

* Fix: `lowercase(Locale.ROOT)`, the same rule the stored settings tokens already follow.
* Test: the existing "dimension strings convert with the documented units and rounding" check now
  repeats three uppercase conversions under a Turkish default locale.
* Mutation: `Locale.getDefault()` again fails the check with a `NumberFormatException`.

### 13. The About screen's tap counter leaked a stale toast into the next burst

`Fragments/BaseAboutFragment` kept `taps`, `lastTap` and `currentToast` as static fields. Two
defects followed from never clearing `currentToast`: the seventh tap cancelled the "6" toast and
then re-showed it next to the special-features dialog, and after a slow tap (which reset the
counter) the next quick tap cancelled and re-showed the *previous* burst's toast — a single tap on
the version number repeated "7". The counter also stayed at 7 forever, so the next burst never
produced its own toasts.

* Fix: only the toast created by the current tap is shown, `currentToast` is cleared when a burst
  ends (slow tap or seventh count), and the seventh count restarts the counter at zero.
* Test: the "seven quick taps on the version enable the hidden special features" check now requires
  the first burst to toast `3,4,5,6` and finish at a zero counter, that a slow tap drops the toast,
  and that the second burst starts silently instead of repeating "7".
* Mutation: the original listener fails with `the seven taps did not finish the burst: 7`.

## Leads investigated and rejected

* **Obscured touch rejection** (`BaseActivity.dispatchTouchEvent`): deliberate tapjacking
  hardening, pinned by `Checks.kt` and `ObscuredTouchDeviceTest`
  (`docs/andotp-carryover-issue-pass-2026-10-19.md`, `docs/vault-security-hardening.md`).
* **Broadcast receiver gate order** (`enabled → location → slot → password → encryption`): the slot
  deliberately throttles the whole receiver including its failure notifications;
  `scripts/tests/test_kotlin_backup_components_conversion.py` pins the order.
* **`EncryptedBackupHeader.MAX_ITERATIONS` (1,000,000) vs the writer**: the writer draws from
  140,000–160,000 (`Constants.PBKDF2_MIN/MAX_BACKUP_ITERATIONS`), so a self-produced backup can
  never exceed the read ceiling; the ceiling intentionally admits historical counts.
* **mOTP secrets in JSON**: `EntryJson.serialize` Base32-encodes for every kind and `parse` Base32-decodes
  for every kind, so the round trip is symmetric; the raw form is only used by the mOTP token input,
  `getSecretEncoded()` and the `otpauth` export, matching upstream andOTP.
* **`Entry` setters that skip `validate()`** (`setLabel`, `setTags`, `setCounter`, `setThumbnail`):
  left as is — the vault write validates every entry (`DatabaseHelper.entriesToString` →
  `VaultException`) and a failed write reloads the list. The *edit dialog's* in-place mutation was
  the real defect and is fixed in finding 9; the setters keep their import/construction role.
* **`Settings.getTokenSplitGroupSize()` throwing on an unparsable value**: the existing sandbox
  check pins that `NumberFormatException` deliberately (the value is only ever written by the app
  itself), so it was left unchanged.
* **`preferences.xml` naming `OpenPgpAppPreference`/`OpenPgpKeyPreference` that are "missing"**:
  both come from the `com.github.open-keychain.open-keychain:openpgp-api` dependency
  (`app/build.gradle:96`), not from this source tree. The only in-tree OpenPGP code is the
  `OpenPgpPolicy`/`OpenPgpRecipientIds` pair, which is covered by JVM tests.
* **`PasswordEncryptedPreference` enabling Save when both fields are empty**: saving an empty value
  is the only way to clear the stored backup password, and an empty password only disables
  automatic encrypted backups (`BackupHelper.autoBackupType` → `UNAVAILABLE`) — it never writes an
  unencrypted backup. Left as is. A KeyStore failure still only logs and closes the dialog, which
  is recorded as a residual risk below.
* **`NumberPickerPreference.setValue()` persisting on `onSetInitialValue`**: that is the framework
  contract (`persistInt` on first attach) and it keeps `getValue()` and the summary in sync; the
  dialog path still goes through `callChangeListener` first.
* **Static `MainActivity.animatorDuration`, per-bind `AppCompatDelegate.setCompatVectorFromResourcesEnabled`,
  `LocaleContextHelper.createLocalizedContext`'s `Locale.setDefault`**: no functional defect found;
  the default-locale mutation is what makes `Collator`-based sorting follow the in-app language, so
  it is left in place and the token/matching sites above were fixed instead.
* **Translations and arrays**: a script comparing every `values-*` string's format specifiers with
  the base locale, plus the `settings_entries_*`/`settings_values_*` lengths, reports zero
  mismatches; the `settings_*` value arrays still line up with their enums
  (`AuthMethod`, `EncryptionType`, `CardLayouts`, `TapMode`, `LabelDisplay`, `AutoBackup`,
  `TagFunctionality`).

## Follow-ups (not fixed here)

* The gates that need a real SDK (all-variant Gradle builds + lint, packaged-manifest policy,
  the fdroid/play × API 28/36 matrix, reproducibility) still run only in CI; nothing in this pass
  changed build inputs, and `generate-r-stub.py --check` confirms the sandbox R stand-in is current.
* **Intro wizard after a configuration change**: fixed in the 2026-10-26 pass.
  `IntroFinishedFragment` now persists its own completed flag, and the activity adopts the
  fragments the pager actually shows instead of the fresh instances it handed to `addSlide`.
* **`PasswordEncryptedPreference.encryptAndPersist` failure path**: fixed in the 2026-10-26 pass.
  A KeyStore or preference-store failure shows `settings_toast_encryption_no_key` and leaves the
  dialog open. Empty values still clear the stored password when the write succeeds.

## Verification

| Gate | Result |
| --- | --- |
| `scripts/tests` (Python guards) + build-input provenance | 128 tests, OK (9 skipped: toolchain-gated) |
| `scripts/kotlin-sandbox/run.sh` | 113 checks, all pass |
| `scripts/run-jvm-tests.sh` | 145 tests, 145 passed |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 12 files, COMPILE OK |
| `generate-r-stub.py --check` | up to date |

New coverage added by this pass: new or extended checks in `Checks.kt` for every finding above,
1 new JVM test, and 3 new or extended Python guard files. Each fix was reverted once to confirm
that its check fails, and the body of this report records that failure.
