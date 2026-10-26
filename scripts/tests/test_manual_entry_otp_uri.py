"""Regression checks for importing otpauth URIs through manual entry."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIALOG = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Dialogs/ManualEntryDialog.kt"
LAYOUT = ROOT / "app/src/main/res/layout/dialog_manual_entry.xml"
STRINGS = ROOT / "app/src/main/res/values/strings_main.xml"


class ManualEntryOtpUriTest(unittest.TestCase):
    def test_uri_control_is_bounded_and_not_saved_or_shown_while_editing(self):
        source = DIALOG.read_text(encoding="utf-8")
        layout = LAYOUT.read_text(encoding="utf-8")
        self.assertIn("InputFilter.LengthFilter(InputPolicy.MAX_URI_CHARS)", source)
        self.assertIn('android:saveEnabled="false"', layout)
        self.assertIn('android:importantForAutofill="no"', layout)
        self.assertIn("otpUriInput.visibility = View.GONE", source)
        self.assertIn("applyOtpUriButton.visibility = View.GONE", source)
        self.assertIn("otpUriInput.setText(\"\")", source)

    def test_manual_tag_picker_shows_live_entry_counts(self):
        source = DIALOG.read_text(encoding="utf-8")
        self.assertIn("tagsAdapter.setTagCounts(adapter.getTagCounts())", source)

    def test_import_uses_the_existing_parser_and_populates_supported_fields(self):
        source = DIALOG.read_text(encoding="utf-8")
        self.assertIn("val entry = Entry(otpUriInput.text?.toString()?.trim())", source)
        for getter in (
            "entry.getType()", "entry.getIssuer()", "entry.getLabel()",
            "entry.getSecretEncoded()", "entry.getPeriod()", "entry.getDigits()",
            "entry.getCounter()", "entry.getAlgorithm()", "entry.getTags()",
        ):
            with self.subTest(getter=getter):
                self.assertIn(getter, source)
        self.assertIn("for (tag in entry.getTags()) importedTags[tag] = true", source)
        self.assertIn("otpUriInput.setText(\"\")", source,
                      "the URI copy should be removed after its fields are applied")
        self.assertIn("parsedEntry?.destroy()", source,
                      "the temporary parsed model must be wiped on both success and failure")

    def test_import_errors_are_generic_and_never_render_parser_details(self):
        source = DIALOG.read_text(encoding="utf-8")
        self.assertIn("R.string.error_invalid_otp_uri", source)
        self.assertNotIn("error.message", source)
        self.assertNotIn("e.message", source)
        strings = STRINGS.read_text(encoding="utf-8")
        self.assertIn('name="error_invalid_otp_uri"', strings)


if __name__ == "__main__":
    unittest.main()
