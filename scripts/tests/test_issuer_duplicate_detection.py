"""Regression checks for issuer warnings when importing scanned OTP URIs."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
CORE = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"
STRINGS = ROOT / "app/src/main/res/values/strings_main.xml"


class IssuerDuplicateDetectionTest(unittest.TestCase):
    def test_qr_import_warns_but_allows_another_account_from_same_issuer(self):
        source = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("currentAdapter.hasIssuer(issuer)", source)
        self.assertIn("showDuplicateIssuerConfirmation(entry, issuer)", source)
        self.assertIn("R.string.button_add_anyway", source)
        self.assertIn("R.string.dialog_msg_duplicate_issuer", source)
        self.assertIn("R.string.toast_invalid_qr_code", source)
        self.assertIn("dialog.setOnDismissListener", source,
                      "the pending parsed entry must be destroyed if the warning is cancelled")

    def test_issuer_matching_is_case_and_whitespace_insensitive_and_skips_empty_issuer(self):
        entry_list = (CORE / "Database/EntryList.kt").read_text(encoding="utf-8")
        adapter = (APP / "View/EntriesCardAdapter.kt").read_text(encoding="utf-8")
        self.assertIn("fun hasIssuer(issuer: String?): Boolean", entry_list)
        self.assertIn("if (candidate.isEmpty()) return false", entry_list)
        self.assertIn("equals(candidate, ignoreCase = true)", entry_list)
        self.assertIn("fun hasIssuer(issuer: String?): Boolean = entries.hasIssuer(issuer)", adapter)

    def test_duplicate_warning_strings_are_declared(self):
        strings = STRINGS.read_text(encoding="utf-8")
        for name in (
            "dialog_title_duplicate_issuer", "dialog_msg_duplicate_issuer", "button_add_anyway"
        ):
            with self.subTest(name=name):
                self.assertIn(f'name="{name}"', strings)


if __name__ == "__main__":
    unittest.main()
