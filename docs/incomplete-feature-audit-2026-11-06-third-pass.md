# Incomplete-feature audit — 2026-11-06 (third pass)

The first pass closed the unified OpenPGP backend and the second pass closed three unreachable
scaffolds plus the desktop's at-rest permissions. This pass asked the same question once more,
now with the toolchain bootstrapped locally so the JVM and sandbox gates could be run as the
tree was searched rather than only reasoned about: **what does the code promise that no user can
actually see?**

Three answers, each small and each a real difference between what a client does and what the same
client says it does.

## 1. Android: the removal prompt deleted the entry's name — completed

`EntriesCardAdapter.removeItem` passed `entry.getLabel()` into
`dialog_msg_confirm_delete` (*Are you sure you want to remove the account "%1$s"?*). For a scanned
or imported account the label is routinely blank — the issuer is the identity — so the question came
out as:

> Are you sure you want to remove the account ""?

The other two clients already had the fallback: the desktop's `removalConfirmationMessage` and iOS's
`VaultStore.displayName` both use the label, else the issuer. `:core` also already owned it as
`Entry.privacyTitle()` (documented as *a single short identity, preferring the label and falling
back to the issuer*) — but its only caller was… nothing. Android was the client that needed it and
the only one not using it.

The adapter now calls `dialogs.delete(entry.privacyTitle(), …)`, so all three prompts name the same
entry the same way, and `privacyTitle()` finally has a production caller.

## 2. iOS: per-tag counts existed and were never shown — completed

`VaultStore.tagCounts(for:)` was written for exactly this ("How many entries on the current tab carry
each tag; the chip shows this") and counted correctly — once per entry per tag. But `TagStrip` only
called `tags(for:)`, which uses the counts to order the names and throws them away, and rendered
`TagChip(title: tag)`.

Android's drawer rows (`TagsAdapter`, #994) and the desktop's tag dropdown and multi-select filter
panel both decorate the name — `work (3)` — and `README.md` promised per-tag counts without
qualifying the client. The iOS chip title is now built from the same counts in one helper, keeping
the Android rule that a tag with no count yet shows its bare name.

## 3. Android: the biometric wording could not be translated, and two of its resources were dead — completed

Every sentence on the biometric screens was a hard-coded `BiometricKeyStore.BiometricWording`
constant: the settings title and description, every toast, and the prompt titles. Meanwhile

* `biometric_prompt_title` and `biometric_prompt_subtitle` existed in `strings_auth.xml` and were
  read by nobody, and
* `settings_toast_biometric_stale` existed in `strings_settings.xml` and was read by nobody,

so the resources claimed a translatable surface the code did not have. Two further problems came out
of the same `when` in `AuthenticateActivity`: the unlock prompt had been built with the *settings
switch* title, and the `else` branch reported the stale-shortcut sentence for every unnamed failure
while leaving the button in whatever state the last branch set.

Now:

- `BiometricWording.localized(context, text)` maps each sentence to its resource
  (`settings_title_biometric`, `settings_desc_biometric`, `settings_toast_biometric_*`,
  `biometric_prompt_title`, `biometric_prompt_subtitle`) and falls back to the constant when the
  resource is missing or the string is not resource-backed. The constants remain the English
  originals, because `clearOnVaultChange` runs inside a transaction that holds only a
  `SharedPreferences.Editor` and has no `Context` — removing them would have meant either a
  `Context` in that path or an untranslatable sentence.
- The five sentences that had no resource at all are added: wrong password, cancelled, locked out,
  the *Enter the vault password to turn this on* prompt, and the enrollment prompt's *Confirm
  biometrics to enable quick unlock* subtitle, which had been a literal next to a localized title.
- The unlock prompt uses its own title (`biometric_prompt_title`) and subtitle again; the settings
  switch keeps the switch's title.
- `AuthenticateActivity` reports the actual error (`BiometricError.message(context)`), removes the
  shortcut for the two states where it can no longer work (missing or not enrolled), keeps it for a
  cancel or a lockout so a retry is one tap, and uses a short toast only for a cancel.
- `settings_desc_biometric` keeps the sentence the code always claimed — *a second account on this
  device cannot use it* — which had been dropped from the resource.

Also removed: `backup_toast_storage_not_accessible`, dead text left from the pre-SAF storage check
with 31 translated copies and no reader, and the unused `settings_key_cat_security` string (the
security category keeps its `pref_cat_security` key in the layout, where it is the identifier rather
than a stored preference). The three `settings_key_*` marked `<!-- Deprecated -->` are kept: they are
read by the preference migration that clears the old values, so they are not dead.

## What was checked and left alone

| Check | Result |
| --- | --- |
| `TODO`/`FIXME`/`XXX`/`HACK` in app, core, desktop, rust | None outside the deliberate sandbox stand-ins |
| Every `android:key` in `res/xml/preferences*.xml` has a reader | All 53 (the security category's is a layout identifier, not a stored key) |
| Every `Settings` getter has a production caller | Yes; the only uncalled ones are the migration helpers that run inside `Settings` itself |
| Every declared string, layout, menu id and drawable has a consumer | After this pass: none unused but the deprecated migration keys |
| Android menus (`menu_main`, `menu_fab`, `menu_popup`) | Every item has a listener and a visibility rule |
| `:core` helpers with no client caller | `privacyTitle()` was the only one; it now drives the Android prompt. (`hasNonDefaultPeriod` is documented as the legacy companion of `hasNonDefaultTokenStep` and keeps its test.) |
| iOS `VaultStore` surface with no caller | `tagCounts(for:)` was the only one; now the chip text |
| Documented "not implemented"/"left open" statements | All still accurate: backup pruning, browser-password editing/re-export, Nitrokey native OTP, paper backups, preferences export, hardware-key device verification |

## Verification

Local gates, all after the change (the toolchain was bootstrapped with
`scripts/kotlin-sandbox/bootstrap-toolchain.sh` for this pass):

| Gate | Result |
| --- | --- |
| `python3 -m unittest discover -s scripts/tests` | 423 tests, OK, 2 skipped |
| `scripts/run-jvm-tests.sh` | 333 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/run.sh` | all sandbox checks passed (incl. the new removal-prompt check) |
| `scripts/kotlin-sandbox/compile-desktop.sh` | 188 tests, 176 passed, 0 failed, 12 skipped |
| `scripts/kotlin-sandbox/compile-core.sh` | 312 passed, 0 failed, 0 skipped |
| `scripts/kotlin-sandbox/compile-android-tests.sh` | 20 androidTest files, COMPILE OK |
| `scripts/kotlin-sandbox/generate-r-stub.py --check` | R stub up to date |

New coverage: `scripts/tests/test_incomplete_feature_completion.py` (12 guards: the removal-prompt
call site and the shared rule, the iOS chip title and the count-once-per-entry rule against both
other clients' decoration, the biometric resources and the localized-helper call sites, and the
description matching its constant), plus one executed sandbox check
(*the removal prompt names an entry the way every client does*) that builds labelled, unlabelled and
anonymous entries and asserts the adapter reads `privacyTitle()`.

Mutation checks (revert, observe the failure, restore): restoring `entry.getLabel()` fails two
guards; restoring `TagChip(title: tag)` fails the iOS guard; restoring a bare-constant `.setTitle`
fails the localization guard.

### Verification limits

There is still no Android SDK, Gradle, Xcode or native Rust toolchain here.

- The **Android** changes are compile-verified against stubs, not run: the removal dialog, the
  biometric prompts and the settings screen need a device or emulator. The sandbox exercises the
  identity rule and the adapter's call site, not `AlertDialog`.
- The **Swift** change is **not compiled locally**; `ios.yml` compiles and tests it in CI. The Python
  guard pins the chip text in the meantime.
- The **iOS counts** are asserted structurally (the helper reads `tagCounts(for:)`, which counts
  `Set(entry.tags)`), not through a rendered view.
