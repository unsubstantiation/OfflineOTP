"""Regression guards for the 2026-10-18 UI-surface pass.

Three defects fixed in one pass, each pinned here:

1. **Editing an existing entry could never save.** ``ManualEntryDialog`` validates the typed
   secret with ``Entry.validateSecret(secret, type)`` for every entry type, but when editing,
   the secret field is display-only (GONE, empty) and the edit branches never consume the
   typed value. The strict validator rejects the empty string (``decodeBase32Secret("")``
   returns null), so ``Save`` silently no-opped for TOTP/HOTP/STEAM entries. The fix gates
   typed-secret validation (and the throwaway ``Entry`` construction) on ``isNewEntry``.

2. **The custom-period card row was never wired.** Every card layout ships a
   ``customPeriodLayout``/``customPeriod`` row (alarm icon + bold label) and every translation
   ships ``format_custom_period`` (``"%d s"``), but no code bound them: the andOTP v0.4/v0.5
   wiring (``showCustomPeriod`` for ``hasNonDefaultPeriod()``) was lost upstream in v0.6.
   ``EntryViewHolder.updateValues`` binds the row again using each entry's actual token step.

3. **PIN setup showed the password confirmation warning.** Fixed by that pass; the PIN login was
   removed afterwards, and ``PinLoginRemovalTest`` now guards that nothing of it comes back.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
CORE_KOTLIN = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"
RES_VALUES = ROOT / "app/src/main/res/values"
RES_LAYOUT = ROOT / "app/src/main/res/layout"


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


class ManualEntryEditSaveTest(unittest.TestCase):
    """The typed secret is validated and decoded only on the new-entry path."""

    DIALOG = APP_KOTLIN / "Dialogs/ManualEntryDialog.kt"

    def test_secret_validation_is_gated_on_new_entries(self):
        text = self.DIALOG.read_text(encoding="utf-8")
        self.assertIn(
            "if (isNewEntry && type != Entry.OTPType.NOTE && type != Entry.OTPType.SSH_KEY &&",
            text,
            "OTP validation must exclude non-OTP entries",
        )
        self.assertIn(
            "!Entry.validateSecret(secret, type))",
            text,
            "the typed secret must only be validated when the entry is new",
        )
        self.assertNotIn(
            "if (!Entry.validateSecret(secret, type))",
            text,
            "an ungated validateSecret call would reject every edit save with an empty secret",
        )

    def test_new_entries_are_built_through_the_shared_draft(self):
        """The dialog no longer assembles a type itself; every new branch asks ``EntryDraft``."""
        text = self.DIALOG.read_text(encoding="utf-8")
        self.assertEqual(7, text.count("EntryDraft.create(type, issuer, label, secret,"),
                         "every type branch must build its new entry through EntryDraft")
        self.assertEqual(5, text.count("newEntry = true"),
                         "a new entry must be stamped through EntryDraft, never by hand")
        self.assertNotRegex(
            text, r"val validated = Entry\(",
            "validation happens in the shared builder; a throwaway entry must not come back",
        )
        self.assertNotRegex(
            text, r"\bEntry\.sshKey\(",
            "the SSH assembly belongs to EntryDraft",
        )
        # The edit branches consume only the old entry; nothing may decode the empty secret.
        self.assertNotRegex(
            text, r"oldEntry[^\n]*setSecret",
            "editing must keep the stored secret untouched",
        )

    def test_edit_branches_keep_updating_the_old_entry(self):
        text = self.DIALOG.read_text(encoding="utf-8")
        # 0d9f5ece: edits now go through EntriesCardAdapter.replaceEntry so a failing
        # write never leaves a dirty in-memory entry. The dialog builds a replacement
        # via EntryDraft and asks the adapter to swap it under MutationLock.
        self.assertEqual(5, text.count("adapter.replaceEntry(oldEntry"),
                         "every type branch must replace through the adapter")
        self.assertEqual(5, text.count("newEntry = false"),
                         "every edit must build its replacement as an update, not a new entry")
        self.assertNotIn("oldEntry!!.applyEdit", text,
                         "edits must not mutate the live entry outside the lock")

    def test_edit_branches_go_through_the_rollback_wrapper(self):
        """A rejected edit must not stay on the live entry (see Entry.applyEdit).

        The setters do not validate, so a period/digits/counter the model refuses used to remain
        on the shared entry and made every later updateOTP()/vault write throw.
        """
        text = self.DIALOG.read_text(encoding="utf-8")
        self.assertNotRegex(
            text, r"oldEntry!!\.set\w+\(",
            "the edit branches must mutate the entry through applyEdit's rollback wrapper",
        )
        self.assertIn("fun applyEdit(mutate: (Entry) -> Unit)", (CORE_KOTLIN / "Database/Entry.kt")
                      .read_text(encoding="utf-8"))


class CustomPeriodCardLabelTest(unittest.TestCase):
    """The custom-period row on the entry cards is bound and formatted again."""

    HOLDER = APP_KOTLIN / "View/EntryViewHolder.kt"

    def test_holder_binds_and_formats_the_custom_period_row(self):
        text = self.HOLDER.read_text(encoding="utf-8")
        self.assertIn("R.id.customPeriodLayout", text)
        self.assertIn("R.id.customPeriod", text)
        self.assertIn("R.string.format_custom_period", text)
        self.assertIn("entry.hasNonDefaultTokenStep()", text,
                      "the row is shown for time-based entries whose actual step differs from 30s")
        self.assertIn("entry.getTokenStepSeconds()", text,
                      "the label must show the actual step, including mOTP's fixed 10 seconds")
        self.assertRegex(
            text,
            r"if \(entry\.hasNonDefaultTokenStep\(\)\) \{[^}]*customPeriodLayout\.visibility = View\.VISIBLE",
            "a non-default token step must reveal the custom-period row",
        )

    def test_format_custom_period_is_no_longer_an_orphan_string(self):
        """The localized format string must be referenced from Kotlin, not just shipped."""
        referenced = False
        for source in (APP_KOTLIN).rglob("*.kt"):
            if "R.string.format_custom_period" in source.read_text(encoding="utf-8"):
                referenced = True
                break
        self.assertTrue(referenced, "format_custom_period is declared in 30+ locales but unused")

    def test_format_string_declares_a_percent_d_slot(self):
        text = (RES_VALUES / "strings_main.xml").read_text(encoding="utf-8")
        match = re.search(
            r'<string name="format_custom_period">([^<]+)</string>', text)
        self.assertIsNotNone(match, "format_custom_period must stay declared in values/")
        self.assertIn("%d", match.group(1),
                      "format_custom_period must interpolate the period")

    def test_every_card_layout_ships_the_row(self):
        for layout in ("component_card_compact.xml",
                       "component_card_default.xml",
                       "component_card_full.xml"):
            text = (RES_LAYOUT / layout).read_text(encoding="utf-8")
            self.assertIn('android:id="@+id/customPeriodLayout"', text, layout)
            self.assertIn('android:id="@+id/customPeriod"', text, layout)


class PinLoginRemovalTest(unittest.TestCase):
    """The PIN login is gone: no method, no keyboard, no strings, and no crash for old vaults."""

    INTRO = APP_KOTLIN / "Activities/IntroScreenActivity.kt"
    CONSTANTS = CORE_KOTLIN / "Utilities/Constants.kt"

    def test_the_intro_offers_one_credential_form(self):
        text = self.INTRO.read_text(encoding="utf-8")
        self.assertNotIn("setupForPinInput", text)
        self.assertNotIn("AuthMethod.PIN", text)
        self.assertIn("setupForPasswordInput()", text)

    def test_the_auth_enum_has_no_pin_member(self):
        text = self.CONSTANTS.read_text(encoding="utf-8")
        self.assertIn("NONE, PASSWORD, DEVICE", text)
        self.assertNotIn("AUTH_MIN_PIN_LENGTH", text)

    def test_a_stored_method_from_an_older_release_reads_as_a_password(self):
        # The fallback has to exist: a vault set up with the removed PIN login is still encrypted
        # with that PIN, and throwing or returning NONE would leave it unopenable.
        text = self.CONSTANTS.read_text(encoding="utf-8")
        self.assertIn("fun fromStored(token: String): AuthMethod", text)
        self.assertIn("it.name.equals(token.trim(), ignoreCase = true)", text)
        self.assertIn("?: PASSWORD", text)
        settings = (APP_KOTLIN / "Utilities/Settings.kt").read_text(encoding="utf-8")
        self.assertIn("return Constants.AuthMethod.fromStored(authString)", settings)

    def test_no_login_pin_strings_survive_in_any_translation(self):
        removed = (
            "auth_hint_pin", "auth_toast_pin_missing", "settings_hint_pin",
            "settings_hint_pin_confirm", "settings_label_short_pin",
            "intro_slide3_warn_no_pin", "intro_slide3_warn_confirm_pin",
            "intro_slide3_warn_pin_mismatch", "settings_key_auth_pin",
            "settings_key_auth_pin_hash",
        )
        resources = ROOT / "app/src/main/res"
        for name in removed:
            for path in sorted(resources.glob("values*/strings_*.xml")) + [
                    resources / "values/settings.xml"]:
                self.assertNotIn(f'name="{name}"', path.read_text(encoding="utf-8"),
                                 f"{name} is still declared in {path}")

    def test_every_translation_offers_the_same_three_auth_methods(self):
        resources = ROOT / "app/src/main/res"
        for path in sorted(resources.glob("values*/strings_settings.xml")):
            text = path.read_text(encoding="utf-8")
            match = re.search(r'<string-array name="settings_entries_auth">(.*?)</string-array>',
                              text, re.DOTALL)
            self.assertIsNotNone(match, path)
            self.assertEqual(match.group(1).count("<item>"), 3, path)

    def test_the_backup_password_dialog_has_no_pin_mode(self):
        text = (APP_KOTLIN / "Preferences/PasswordEncryptedPreference.kt").read_text(encoding="utf-8")
        self.assertNotIn("Mode.PIN", text)
        self.assertNotIn("enum class Mode", text)
        self.assertIn("R.string.settings_hint_password", text)


if __name__ == "__main__":
    unittest.main()
