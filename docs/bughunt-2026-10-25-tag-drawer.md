# Bug hunt pass, 2026-10-25 (tag drawer and per-tag counts)

Second pass of 2026-10-25, after [`bughunt-follow-up-2026-10-25.md`](bughunt-follow-up-2026-10-25.md)
(13 findings) and the earlier 2026-10-17/18/19 passes. Nothing from this pass duplicates those
reports; the fixes here are in the tag-counter feature added for #994 and in the deletion path.

Scope: every Kotlin source under `app/src/main/kotlin` was re-read against the existing tests and
sandbox checks, plus mechanical audits (below) instead of further reading of the 29 translations.
All three findings are in the #994 tag-counter feature and the paths that feed it.
This environment still has no Android SDK, Gradle distribution or device, so lint, packaging,
instrumentation and the emulator matrix remain CI/device gates. All gates that run here pass, and
each fix was mutation-verified: the fix was reverted, its new test was seen to fail, and the fix was
restored.

## Findings fixed

### 1. Deleting an entry left its tag in the drawer

`View/EntriesCardAdapter.removeItem` finished with a bare `saveEntries(...)` call. Every other
mutation path that can change the set of tags in use — `addEntry`, the edit dialog, the import
reload — goes through `saveAndRefresh(...)`, which reconciles the tag drawer *and* re-filters the
displayed list. Deletion therefore persisted the vault but never touched `TagsAdapter` (or the
activity's copy of it, which is the same instance the drawer displays).

Symptom, deleting the last entry carrying a tag: the drawer keeps the tag row — now with a count
for entries that no longer exist — and the row stays selectable, filtering the list down to nothing
until an unrelated add/edit calls `MainActivity.refreshTags()` or the app is restarted. The stale
selection also reaches `Settings.setTagToggle`, so the vanished tag keeps a remembered state.

* Fix: the deletion callback uses `saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled())`,
  the same reconcile-then-save path as every other tag-changing mutation. The seed wipe stays in
  the `finally` block, so it still runs when the save fails.
* Tests: `scripts/tests/test_tag_drawer_refresh.py` pins the call site (the callback needs a live
  session key, a persisted vault and a shown dialog, so the desktop harness cannot drive it — the
  guard shape follows `test_kotlin_tag_drawer_click.py`); `EntryListTest` covers the count source
  behaviourally (finding 2 below).
* Mutation: restoring `saveEntries(...)` fails two of the three new guards
  (`deleting an entry must reconcile the tag drawer…`, `a bare saveEntries() after deletion leaves
  the deleted tag in the drawer`).

### 2. A repeated tag was counted twice for one entry

`Database/EntryList.getTagCounts` (the source of the `"tag (n)"` decoration) incremented once per
*tag occurrence*. `Entry` accepts a repeated tag — the URI parser deliberately keeps one `tags=`
parameter per occurrence, and imported documents may repeat a name — so a single entry holding
`["work", "work"]` made the drawer claim two entries carry `work`. The docstring's contract is
"how many entries carry each tag".

* Fix: `getTagCounts` iterates `HashSet(entry.getTags())`, so each entry contributes at most one
  to a tag's count. `getAllTags` already de-duplicated through a `HashSet`.
* Test: `EntryListTest.tagCountsCountEveryEntryOnceEvenWhenATagRepeats` (two entries, one with a
  repeated tag) expects `work = 2`, `cloud = 1` and two distinct tags.
* Mutation: reverting to `entry.getTags()` fails that test (145/146 JVM tests pass).

### 3. The per-tag counts were missing until the first entry mutation

`TagsAdapter` renders `"tag (n)"` from a separate map pushed by `setTagCounts`; `setTags` only
replaces the rows. The only `setTagCounts` call was in `EntriesCardAdapter.updateTagsFilter`, which
runs from `saveAndRefresh` — that is, from an add, an edit, a PIN/thumbnail change or (after
finding 1) a delete. `MainActivity.populateAdapter` (first start, unlock, configuration change) and
`MainActivity.refreshTags` (rebuild after an import reload) called `setTags` alone, so a normal
launch showed undecorated rows, and after restoring a backup the drawer kept the *previous* entry
set's counts for tags that still existed and showed no count for the tags the import introduced.

* Fix: `MainActivity` pushes `EntriesCardAdapter.getTagCounts()` (new accessor over
  `EntryList.getTagCounts`) from both drawer-rebuilding paths, next to the existing `setTags`.
* Tests: `scripts/tests/test_tag_drawer_refresh.py` pins both call sites and the accessor shape.
  The decoration itself is already covered by the sandbox check "tag rows keep their identity when
  the display text carries the entry counts" and by `EntryListTest`.
* Mutation: removing either push fails the matching guard
  (`test_the_initial_load_pushes_counts` / `test_rebuilding_the_drawer_pushes_counts`).

## Verification performed (no defect found)

* **Serialization fuzz, 6,000 random entries** (all four kinds, random labels/issuers/tags from a
  punctuation+non-ASCII alphabet, periods 1–86,400, digits, algorithms, counters) round-tripped
  through `Entry.toJSON()` → `Entry(JSONObject)` and `Entry.toUri()` → `Entry(String)`:
  * JSON: 0 mismatches, 0 failures.
  * URI: 0 failures; after excluding the two known shapes below, 231/9,000 earlier samples differed
    only by label leading/trailing whitespace (see rejected leads).
  * HOTP `period` is the only other asymmetry: a legacy document that stores a period for an HOTP
    entry keeps it in the URI export and loses it to `30` on import/JSON write. Inert — nothing
    reads an HOTP period (`TokenCalculator.HOTP` ignores it, `getTokenStepSeconds()` is only
    consulted for time-based entries, `EntryJson` does not write it for HOTP).
* **Resource references**: a script resolving every `R.<kind>.<name>` in `app/src/main/kotlin`
  against `res/` found no missing id/string/layout/menu/style/array/styleable reference.
* **Settings defaults**: all 14 `ListPreference`/`MultiSelectListPreference` entries resolve to
  value arrays of equal length, every `settings_default_*` value is a member of its value array,
  and every `Settings` getter's fallback matches the XML default (the `preferences.xml` keys are
  the `settings_key_*` strings, `translatable="false"`, so no locale can change a stored token).
* **Compiler warnings** (`kotlinc` over the app sources): reviewed the 70+ warnings; the only
  non-stub ones are redundant casts/non-null assertions/`?:` on non-nullable platform strings
  (`MainActivity.importPreviewText`, `TagsDialog`, `VaultSession.takeDerivedKey`,
  `ManualEntryDialog`'s `oldEntry!!`) and Android-API deprecations. No defect.
* **Issue #39 checklist, #591** ("initial setup screen forces portrait"): already fixed before this
  pass — `AndroidManifest.xml` has no `screenOrientation` for `IntroScreenActivity` and says so in a
  comment. The `#39` line marked `❌ APPLIES` is stale.

## Leads investigated and rejected

* **mOTP PIN is not in the `otpauth://` export**: `Entry.toUri()` writes the seed and issuer for
  mOTP but not the PIN, so an entry's own QR code re-imports without it. This is deliberate and
  already pinned by `EntryDocumentDeviceTest.motpUriRoundTripKeepsTheSeedButNotThePin`; the format
  has no PIN parameter, third-party importers would ignore an invented one, the PIN *is* preserved
  in documents/JSON (`EntryJson`), and the UI handles a PIN-less mOTP entry (`PINREQ` placeholder
  plus the "Establish PIN" menu item). Not changed.
* **URI import trims leading/trailing label whitespace**: `OtpUriParser` trims the label after
  stripping the issuer prefix (the pilot deliberately normalizes externally scanned labels), so the
  app's own export of a label that begins or ends with a space does not come back byte-identical.
  Changing it would alter every scanned URI's label handling; recorded as an accepted asymmetry.
* **Empty tag selection is asymmetric** (`OR` + no tags selected shows only tagless entries when the
  "no tags" toggle is on; `AND` matches everything): this is exactly conjunction/disjunction over
  the selected set, and "show everything" is the drawer's own `all tags` toggle. No change.
* **`Entry.destroy()` changes `equals`/`hashCode`**: `destroy()` is terminal (after removal or
  locking), entries are not used as map/set keys, and the null seed is handled by the nullable
  `contentEquals` overload. No change.
* **`EntryList.updateEntries` replaces an equal entry without destroying the old one**: the replaced
  instance is only referenced from a presentation list that is refreshed with
  `notifyDataSetChanged`; wiping is explicitly best-effort (`docs/vault-security-hardening.md`).
* **`Entry.getTags()` returns the internal list** while `setTags` copies: every caller only reads it
  (`EntryList`, `EntryViewHolder`, `EntryJson`, `TagsAdapter`, `ManualEntryDialog`), so no aliasing
  defect exists today.
* **`BaseActivity.registerReceiver` without `RECEIVER_NOT_EXPORTED`**: the only filter is
  `ACTION_SCREEN_OFF`, a protected system broadcast that is exempt from the API 34 flag rule.
* **`BackupActivity` PGP restore calls `restoreEntries(text, true)`**: the second parameter is
  `finish`, not `replace`; the replace decision still comes from the `swReplace` switch inside
  `restoreEntries`. Looked like a data-loss bug on first read, is not one.
* **`EntryList.getEntriesFilteredByTags` with `SINGLE`**: treated like `OR`; with at most one
  selected tag the two coincide.

## Follow-ups (not fixed here)

Still open from `bughunt-follow-up-2026-10-25.md`, not re-investigated in this pass:

* `IntroFinishedFragment` completed state across a rotation (needs a device/`FragmentPagerAdapter`
  re-attachment run).
* `PasswordEncryptedPreference.encryptAndPersist` swallowing KeyStore failures.
* Everything the hardening document lists as a device/CI acceptance gate (rotation, process death,
  TalkBack, OEM document providers, both-flavor Gradle builds, API 28/36 emulator runs).

## Verification

| Gate | Before | After |
| --- | --- | --- |
| `python3 -m unittest discover -s scripts/tests` | 128 OK (9 skipped: PyYAML) | 134 OK (9 skipped: PyYAML) |
| `scripts/kotlin-sandbox/run.sh` | 128 checks, pass | 128 checks, pass |
| `scripts/run-jvm-tests.sh` | 145/145 | 146/146 |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 12 files, COMPILE OK | 12 files, COMPILE OK |

New coverage: 1 JVM test (`EntryListTest`) and 1 new Python guard file (6 tests). Every fix was
reverted once to confirm its test/guards fail (`FAIL … tagCountsCountEveryEntryOnceEvenWhenATagRepeats`;
`FAILED (failures=2)` for the deletion path; one failure each for the two count pushes) and then
restored. `generate-r-stub.py --check` reports the sandbox R stand-in is up to date.
