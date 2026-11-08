"""Guards for three half-finished surfaces closed on 2026-11-06.

Each one was code that existed but did not reach the user as documented:

* **Android's removal prompt erased the entry's name when the label was blank.** The dialog asked
  *Are you sure you want to remove the account "%1$s"?* with `entry.getLabel()`, so an entry whose
  identity is its issuer — the normal case for a scanned account — asked to remove the account "".
  The desktop (`removalConfirmationMessage`) and iOS (`VaultStore.displayName`) prompts already
  fall back to the issuer; Android's now shares `Entry.privacyTitle()`, so all three name the entry
  the same way.

* **iOS kept per-tag counts that no view showed.** `VaultStore.tagCounts(for:)` existed (and is
  used for `tags(for:)` ordering), but `TagStrip` rendered a bare `TagChip(title: tag)`, so the
  third client was the only one without the *tag (n)* decoration Android's drawer and the desktop
  filter panel have. The chip text is now built from the same counts.

* **Android's biometric wording was hard-coded strings, and two of them were dead resources.** The
  sentences lived as `BiometricWording` constants (untranslatable), while `biometric_prompt_title`,
  `biometric_prompt_subtitle` and `settings_toast_biometric_stale` sat unused in `res/values`. The
  wording now resolves through resources with the constants as the no-`Context` fallback, and the
  sentences that had no resource at all (wrong password, cancelled, locked out, the enable prompt)
  were added, so nothing a user reads is untranslatable any more.

The Android paths need a device (a live session, a shown dialog, a biometric prompt), so their shape
is pinned here; the iOS chip text and the Android fallback rule are also covered by the sandbox
check *the removal prompt names an entry the way every client does*.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt"
ENTRY = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Database/Entry.kt"
BIOMETRIC = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/BiometricKeyStore.kt"
AUTH = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AuthenticateActivity.kt"
SETTINGS_ACTIVITY = (
    ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt"
)
STRINGS_SETTINGS = ROOT / "app/src/main/res/values/strings_settings.xml"
STRINGS_AUTH = ROOT / "app/src/main/res/values/strings_auth.xml"
IOS_LIST = ROOT / "ios/App/Sources/EntryListView.swift"


def function_body(text, signature):
    """The body of a member function, ending at the next member-level closing brace."""
    start = text.index(signature)
    end = text.index("\n    }\n", start)
    return text[start:end]


class AndroidRemovalPromptNamesTheEntryTest(unittest.TestCase):
    def setUp(self):
        self.body = function_body(ADAPTER.read_text(encoding="utf-8"), "fun removeItem(")

    def test_the_prompt_uses_the_shared_identity_rule(self):
        self.assertIn("dialogs.delete(entry.privacyTitle(),", self.body)

    def test_a_blank_label_no_longer_prints_empty_quotes(self):
        # getLabel() is the raw field: blank for an entry identified by its issuer.
        self.assertNotRegex(self.body, re.compile(r"dialogs\.delete\(\s*entry\.getLabel\(\)"))

    def test_the_shared_rule_prefers_the_label_and_falls_back_to_the_issuer(self):
        entry = ENTRY.read_text(encoding="utf-8")
        self.assertIn("fun privacyTitle(): String = label.ifBlank { issuer }", entry)

    def test_the_other_clients_already_fall_back_to_the_issuer(self):
        desktop = (
            ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/ui/MainScreen.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("val name = label.ifBlank { issuer }", desktop)
        ios = (ROOT / "ios/App/Sources/VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("public static func displayName(_ entry: Entry) -> String", ios)
        self.assertIn('return issuer.isEmpty ? "this entry" : issuer', ios)


class IosTagChipsShowCountsTest(unittest.TestCase):
    def setUp(self):
        self.list_view = IOS_LIST.read_text(encoding="utf-8")
        self.store = (ROOT / "ios/App/Sources/VaultStore.swift").read_text(encoding="utf-8")

    def test_the_chip_title_comes_from_the_shared_counts(self):
        strip = self.list_view.split("struct TagStrip: View {", 1)[1].split("\nstruct TagChip", 1)[0]
        self.assertIn("TagChip(title: tagChipTitle(tag),", strip)
        self.assertIn("store.tagCounts(for: store.category)[tag]", strip)
        self.assertIn('return "\\(tag) (\\(count))"', strip)

    def test_the_counts_count_each_entry_once_per_tag(self):
        self.assertIn("public func tagCounts(for category: EntryCategory) -> [String: Int]", self.store)
        self.assertIn("for tag in Set(entry.tags) { counts[tag, default: 0] += 1 }", self.store)

    def test_the_decoration_matches_the_other_clients(self):
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/TagsAdapter.kt")\
            .read_text(encoding="utf-8")
        self.assertIn('checkedTextView.setText(if (count != null) "$tag ($count)" else tag)', adapter)
        dialog = (
            ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/ui/TagDialog.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('JCheckBox("$name (${counts.getValue(name)})"', dialog)


class AndroidBiometricWordingIsTranslatableTest(unittest.TestCase):
    def setUp(self):
        self.biometric = BIOMETRIC.read_text(encoding="utf-8")
        self.strings = STRINGS_SETTINGS.read_text(encoding="utf-8")
        self.auth_strings = STRINGS_AUTH.read_text(encoding="utf-8")

    def test_every_sentence_has_a_resource(self):
        for name in ("settings_toast_biometric_wrong_password",
                     "settings_toast_biometric_cancelled",
                     "settings_toast_biometric_locked_out",
                     "settings_dialog_biometric_enable"):
            with self.subTest(resource=name):
                self.assertIn(f'<string name="{name}">', self.strings)

    def test_the_previously_unused_resources_now_resolve_through_the_wording(self):
        for resource in ("biometric_prompt_title", "biometric_prompt_subtitle",
                         "biometric_prompt_enable_subtitle"):
            with self.subTest(resource=resource):
                self.assertIn(f'<string name="{resource}">', self.auth_strings)
                self.assertIn(f"R.string.{resource}", self.biometric)
        self.assertIn("R.string.settings_toast_biometric_stale", self.biometric)

    def test_the_description_matches_the_constant_a_second_account_cannot_use_it(self):
        # The settings summary is what a user reads; the constant is the fallback copy of it.
        constant = re.search(r"const val switchDetail =\s*(.*?)\n\s*const val enableQuestion", self.biometric,
                             re.S).group(1)
        # The pieces carry their own spacing, exactly as the Kotlin concatenation does.
        expected = "".join(re.findall(r'"([^"]*)"', constant))
        resource = re.search(r'<string name="settings_desc_biometric">(.*?)</string>', self.strings, re.S)\
            .group(1)
        self.assertEqual(expected, resource)

    def test_prompts_are_built_from_the_localized_helper(self):
        localized = self.biometric.count("BiometricWording.localized(")
        self.assertGreaterEqual(localized, 5, "titles, subtitles and the cancel button are localized")
        self.assertIn("fun localized(context: Context, text: String): String", self.biometric)
        # No prompt left with a bare constant title.
        self.assertNotRegex(self.biometric, re.compile(r"\.setTitle\(BiometricWording\.(switchTitle|unlockTitle)\)"))

    def test_the_unlock_screen_and_settings_show_resources(self):
        auth = AUTH.read_text(encoding="utf-8")
        settings = SETTINGS_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("error.message(this)", auth)
        self.assertIn("R.string.settings_dialog_biometric_enable", settings)
        self.assertIn("R.string.settings_toast_biometric_wrong_password", settings)
        self.assertIn("R.string.settings_toast_biometric_locked_out", settings)
        self.assertIn("R.string.settings_toast_biometric_cancelled", settings)


if __name__ == "__main__":
    unittest.main()
