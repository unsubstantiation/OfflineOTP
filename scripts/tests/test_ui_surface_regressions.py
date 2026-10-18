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
   ``EntryViewHolder.updateValues`` binds the row again.

3. **PIN setup showed the password confirmation warning.** ``IntroScreenActivity
   setupForPinInput`` assigned ``intro_slide3_warn_confirm_password`` instead of the PIN
   variant ``intro_slide3_warn_confirm_pin`` (present in all translations, unused until the
   fix).
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
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
            "if (isNewEntry && !Entry.validateSecret(secret, type))",
            text,
            "the typed secret must only be validated when the entry is new",
        )
        self.assertNotIn(
            "if (!Entry.validateSecret(secret, type))",
            text,
            "an ungated validateSecret call would reject every edit save with an empty secret",
        )

    def test_entry_construction_stays_inside_the_new_entry_branches(self):
        """Each type branch may only build ``Entry(type, secret, ...)`` under ``isNewEntry``."""
        text = self.DIALOG.read_text(encoding="utf-8")
        constructions = list(re.finditer(r"val validated = Entry\(", text))
        self.assertEqual(3, len(constructions),
                         "expected one throwaway validation entry per entry type")
        for match in constructions:
            before = text[max(0, match.start() - 120):match.start()]
            self.assertRegex(
                before, r"if \(isNewEntry\) \{\s*$",
                "Entry(type, secret, ...) construction must sit inside if (isNewEntry)",
            )
        # The edit branches consume only the old entry; nothing may decode the empty secret.
        self.assertNotRegex(
            text, r"oldEntry[^\n]*setSecret",
            "editing must keep the stored secret untouched",
        )

    def test_edit_branches_keep_updating_the_old_entry(self):
        text = self.DIALOG.read_text(encoding="utf-8")
        self.assertEqual(3, text.count("oldEntry!!.setIssuer(issuer, true)"),
                         "every type branch must keep its edit path")
        self.assertEqual(3, text.count("updateCallback?.onUpdate()"),
                         "every type branch must report the edit to the callback")


class CustomPeriodCardLabelTest(unittest.TestCase):
    """The custom-period row on the entry cards is bound and formatted again."""

    HOLDER = APP_KOTLIN / "View/EntryViewHolder.kt"

    def test_holder_binds_and_formats_the_custom_period_row(self):
        text = self.HOLDER.read_text(encoding="utf-8")
        self.assertIn("R.id.customPeriodLayout", text)
        self.assertIn("R.id.customPeriod", text)
        self.assertIn("R.string.format_custom_period", text)
        self.assertIn("entry.hasNonDefaultPeriod()", text,
                      "the row is shown exactly for entries with a non-default period")
        self.assertRegex(
            text,
            r"if \(entry\.hasNonDefaultPeriod\(\)\) \{[^}]*customPeriodLayout\.visibility = View\.VISIBLE",
            "a non-default period must reveal the custom-period row",
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


class IntroPinWarningTest(unittest.TestCase):
    """PIN setup asks the user to confirm the PIN, not the password."""

    INTRO = APP_KOTLIN / "Activities/IntroScreenActivity.kt"

    def _function_body(self, name):
        text = self.INTRO.read_text(encoding="utf-8")
        match = re.search(
            rf"private fun {name}\(\) \{{(.*?)\n        (?:private |public |protected |internal |override |open )*fun ",
            text, re.DOTALL)
        self.assertIsNotNone(match, f"{name} not found")
        return match.group(1)

    def test_setup_for_pin_uses_the_pin_confirmation_warning(self):
        body = self._function_body("setupForPinInput")
        self.assertIn("R.string.intro_slide3_warn_confirm_pin", body)
        self.assertNotIn("R.string.intro_slide3_warn_confirm_password", body,
                         "the PIN path must not show the password wording")

    def test_setup_for_password_keeps_the_password_confirmation_warning(self):
        body = self._function_body("setupForPasswordInput")
        self.assertIn("R.string.intro_slide3_warn_confirm_password", body)
        self.assertNotIn("R.string.intro_slide3_warn_confirm_pin", body)

    def test_both_warning_strings_stay_declared(self):
        text = (RES_VALUES / "strings_intro.xml").read_text(encoding="utf-8")
        self.assertIn('name="intro_slide3_warn_confirm_pin"', text)
        self.assertIn('name="intro_slide3_warn_confirm_password"', text)


if __name__ == "__main__":
    unittest.main()
