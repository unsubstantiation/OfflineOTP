# Incomplete-feature audit — 2026-11-01

This pass looked for features that are **half-present**: a type the engine stores but a client
cannot show, a documented action that no code backs, a preference with no consumer, a dialog whose
rows are built twice and drifted apart. It complements the bug hunts (behaviour that is wrong) and
the upstream triage (issues inherited from andOTP).

Environment: no Android SDK, Gradle distribution, emulator or physical device, as before. Every
claim below comes from the source tree plus the gates that run here: the Android sandbox check
(`scripts/kotlin-sandbox/run.sh`), the JVM unit suite (`scripts/run-jvm-tests.sh`), the pure-JVM
desktop boundary gate (`scripts/kotlin-sandbox/compile-desktop.sh`) and the Python source guards
(`scripts/tests`). Lint, packaging, instrumentation and real-device behaviour remain CI gates.

## How the tree was searched

| Check | Result |
| --- | --- |
| `TODO`/`FIXME`/`XXX`/`HACK`/stub markers in app, core, desktop, resources, manifests | None. Matches are limited to the deliberate sandbox stand-ins under `scripts/kotlin-sandbox/stubs` and its documentation. |
| Every `android:key` in `res/xml/preferences*.xml` has a reader | All 49 keys resolve to a `settings_key_*` string that the Kotlin sources read. |
| Every `Settings` getter has a production caller | Yes. The only uncalled helpers are the deprecated-preference migrations and the private raw accessors, which run inside `Settings` itself. |
| Declared string resources | Two have no consumer: `backup_toast_storage_not_accessible` (a leftover from the pre-SAF storage check; every current backup path reports `backup_toast_location_access_failed`) and `settings_lang_sys_default` (an `@string/` item inside `settings_entries_lang`, i.e. used by the array rather than the code). Neither hides a missing feature; the first is dead text and was left in place this pass. |
| Android menus (`menu_main`, `menu_fab`, `menu_popup`) | Every item has a listener and a visibility rule. |
| Layouts and menus referenced from Kotlin | No dangling `R.layout`/`R.menu`/`R.id`. |
| Core entry kinds vs. client surfaces | `Entry.OTPType` has nine kinds and `EntryListPresentation.Category` five categories. Android maps all nine onto its five tabs. **Desktop did not**: see below. |
| Documented "not yet"/"not implemented"/"gap" statements | Enumerated in the last section. |
| Client parity for each user action | Compared the Android `+`/popup menus with the desktop **Add entry**/context menus. Email was the only kind with an Android action set and no desktop counterpart. |

## Completed: email accounts existed only on Android

`:core` stores FairEmail-compatible mail credentials as `EMAIL` entries (`Utilities/EmailMaterial`,
`Entry.email`, `Entry.getEmailAccount`, `EntryJson`), and Android had the whole surface: a fifth
**Email** tab, **Add email account**, **Import/Export FairEmail backup**, and a *View connection
details* dialog. Three things were unfinished around it:

1. **The desktop client could not see the entries at all.** `MainScreen` built its tab strip from a
   hard-coded `listOf("OTP", "Notes", "SSH Keys", "Passwords")` while `MainWindow` maps the selected
   index onto `EntryListPresentation.Category.entries[index]`. `Category.EMAILS` therefore had no
   tab: an email entry restored from a shared backup was filtered into a category that could never
   be selected, so it was invisible (and un-deletable) on Debian and Windows. `EntryDialog` even
   justified refusing to edit an email entry by pointing at "the FairEmail-compatible import/export
   flow" — a flow the desktop did not have.
2. **The connection-details rows were duplicated** in `EntriesCardAdapter` with hand-built strings
   that printed FairEmail's raw integer codes (`Encryption: 0`, `Auth type: 1`), so a second client
   would have drifted from the first.
3. **The feature was undocumented**: `README.md`, `CHANGELOG.md` and `docs/` did not mention email
   entries, FairEmail backups or the fifth tab at all.

What was added:

* `desktop/control/EmailAccounts.kt` — headless support: list the email entries, build the manual
  entry through `EmailMaterial.create`, map a FairEmail backup to entries (wiping anything built
  when the file turns out to be invalid), export the selection, and read the detail rows.
* `desktop/ui/EmailAccountForm.kt` — the manual form with the same fifteen fields and boundaries as
  Android's dialog; validation stays in `:core`, and both password widgets are cleared when the
  dialog ends.
* `desktop/ui/EmailDialogs.kt` — add/details/import/export dialogs. Details start masked behind a
  **Show passwords** toggle; the import reads at most 8 MiB, prompts for a password only when the
  plain/gzip parse failed (three attempts maximum), imports as one batch, and wipes the bytes. The
  export warns that the file is plaintext, writes nothing if the user cancels, deletes a partly
  written file, and wipes the buffer. Untrusted host names and labels are neutralized before they
  reach a Swing label, so a value such as `<html>…` cannot switch a label into HTML mode.
* `MainScreen` derives the tab strip from `EntryListPresentation.Category.entries` (with a
  `categoryTitle` mapping), so a category added to `:core` can never be unreachable again; the
  desktop empty state explains the FairEmail path on the new tab.
* `MainWindow` wires **Add email account…**, **Tools → Import FairEmail backup…**,
  **Tools → Export FairEmail backup…** and the row action **View email details…**, and selects the
  Email tab after an add or import.
* `:core` now owns the detail rows (`EmailMaterial.Detail`, `EmailMaterial.details`,
  `SECRET_MASK`) and both clients render them: the Android dialog and the desktop dialog can no
  longer describe the same stored account differently, and encryption and protocol are named (SSL,
  STARTTLS, none; IMAP, POP) instead of shown as raw integers.

## Verification

| Gate | Before | After |
| --- | --- | --- |
| `python3 -m unittest discover -s scripts/tests` | 283 OK | 291 OK |
| `scripts/kotlin-sandbox/run.sh` | 153 checks, all pass | 153 checks, all pass |
| `scripts/run-jvm-tests.sh` | 273 tests, 273 pass | 274 tests, 274 pass |
| `scripts/kotlin-sandbox/compile-desktop.sh` | 135 tests, 125 pass, 10 skipped | 153 tests, 143 pass, 10 skipped |
| `python3 scripts/check_build_inputs.py` | pass | pass |

New coverage: 1 core JVM test (`EmailMaterialTest.connectionDetailsMaskPasswordsAndNameTheProtocols`),
18 desktop JVM tests (`EmailAccountsTest`, `EmailAccountFormTest`, `MainScreenTabsTest`) and one new
Python guard file (`scripts/tests/test_desktop_email_support.py`, 8 tests) that keeps the tab strip
derived from the projection, both clients' tab orders aligned, the actions wired, the control layer
headless, the rows shared, and the README/CHANGELOG statements true.

Mutation checks (revert, observe the failure, restore):

* Restoring the fixed four-label tab strip fails all three `MainScreenTabsTest` cases
  (`everyProjectionCategoryHasOneTabInTheSameOrder`, `theEmailTabIsPresentAndNamedLikeTheAndroidTab`,
  `theEmptyEmailTabPointsAtTheFairEmailFlow`) and the Python tab guard.
* Returning the raw password from `EmailMaterial.details` instead of the mask fails
  `EmailMaterialTest.connectionDetailsMaskPasswordsAndNameTheProtocols`.

The display tests for the new dialogs are not included: `MainWindowDisplayTest` is opt-in and needs
a real display, which this environment does not have, so an unverified heavyweight test was not
shipped. The form, its validation, the detail rows, the tab strip, the import/export mapping and
every string guard are covered headlessly.

## Left open, and why

* **Configurable pruning of automatic backups** — still not implemented; completed backups are
  retained. It needs a new setting, SAF tree enumeration/deletion of files this app wrote, and a
  policy for the copy that is currently the only recovery path. That is destructive behaviour that
  cannot be exercised without a document provider, so it stays a product decision rather than
  something to finish blind.
* **Editing an imported browser password, or re-exporting it as a browser CSV** — documented as not
  yet supported. The entry is a frozen copy of one CSV row (`PasswordMaterial` keeps the source
  column names, not a browser profile), and both clients exclude the kind from the OTP editor;
  deleting the outdated entry and reimporting remains the documented path. Completing it means a
  password editor on both clients and a chosen export dialect (Chrome, Firefox and Safari use
  different headers), which is a feature of its own.
* **Nitrokey Pro's native OTP protocol**, **printable/paper backups** and **preferences export** —
  unchanged from the triage document: hardware/protocol scope and optional upstream requests, not
  defects.
* **Device and CI gates** — rotation, process death, TalkBack, OEM document providers, both-flavor
  Gradle builds, lint, the API 28/36 emulator matrix and packaging. Nothing in this pass changed
  build inputs, and `generate-r-stub.py --check` still reports the sandbox R stand-in as current.
  The new desktop dialogs therefore join the existing list of surfaces that need a display run.

## Files touched

* `core/…/Utilities/EmailMaterial.kt` (shared detail rows), `EntryJson`/`Entry` unchanged.
* `app/…/View/EntriesCardAdapter.kt` (uses the shared rows instead of its own builder).
* `desktop/…/control/EmailAccounts.kt`, `desktop/…/ui/EmailAccountForm.kt`,
  `desktop/…/ui/EmailDialogs.kt` (new), `desktop/…/ui/MainScreen.kt`, `desktop/…/ui/MainWindow.kt`.
* Tests: `core/src/test/…/EmailMaterialTest.kt`, `desktop/src/test/…/EmailAccountsTest.kt`,
  `EmailAccountFormTest.kt`, `MainScreenTabsTest.kt`, `scripts/tests/test_desktop_email_support.py`.
* Documentation: `README.md` (feature bullets, desktop list, new *Email accounts (FairEmail)*
  section), `CHANGELOG.md`.
