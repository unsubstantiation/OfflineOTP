"""Email (FairEmail) accounts must exist on both clients, not just on Android.

`:core` has stored FairEmail accounts as `EMAIL` entries since the Android feature landed, but the
desktop client kept a hard-coded four-tab strip, so an email entry in a shared vault was filtered
into a category no tab could select. These guards keep the desktop tab strip, the action wiring,
the shared details rows and the documentation in step with the projection.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop"
DESKTOP_TEST = ROOT / "desktop/src/test/kotlin/com/fairuniversal/offlineotp/desktop"
APP = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
MODEL_STRINGS = ROOT / "ui-model/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultStrings.kt"


def text(path):
    return (ROOT / path).read_text(encoding="utf-8")


class DesktopEmailSupportTest(unittest.TestCase):
    def test_tab_strip_is_derived_from_the_projection(self):
        # The old hard-coded four-label strip must not come back: it silently dropped a category.
        # The labels are :ui-model's shared table now (the same one the Compose screen and the
        # Android resources use), so the desktop no longer keeps a second copy to fall behind.
        titles = re.search(
            r"fun categoryTitle\([^)]*\): String = when \(.*?\n    \}",
            MODEL_STRINGS.read_text(encoding="utf-8"), re.S)
        self.assertIsNotNone(titles, "VaultStrings.categoryTitle must map every Category")
        # Every category needs a title, so a new core category cannot render a blank tab.
        for category in ("OTP", "NOTES", "SSH_KEYS", "PASSWORDS", "EMAILS"):
            self.assertIn(f"Category.{category} ->", titles.group(0))
        self.assertIn('Category.EMAILS -> "Email"', titles.group(0))

    def test_android_and_desktop_agree_on_the_tab_order(self):
        """`MainWindow` maps a tab index onto `Category.entries[index]`, so both lists must match."""
        core = (CORE / "View/EntryListPresentation.kt").read_text(encoding="utf-8")
        enum = re.search(r"enum class Category \{(.*?)\}", core, re.S).group(1)
        order = [name.strip() for name in enum.split(",") if name.strip()]
        self.assertEqual(["OTP", "NOTES", "SSH_KEYS", "PASSWORDS", "EMAILS"], order)

        activity = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        tabs = re.search(r"listOf\((R\.string\.tab_\w+(?:, R\.string\.tab_\w+)*)\)", activity).group(1)
        self.assertEqual(
            ["R.string.tab_otp", "R.string.tab_notes", "R.string.tab_ssh_keys",
             "R.string.tab_passwords", "R.string.tab_email"],
            [name.strip() for name in tabs.split(",")])



    def test_desktop_email_control_is_headless_and_bounded(self):
        control = (DESKTOP / "control/EmailAccounts.kt").read_text(encoding="utf-8")
        for forbidden in ("javax.swing", "java.awt"):
            self.assertNotIn(forbidden, control, "the email control layer must stay headless")
        # org.json is reached through :core only (see test_desktop_module_boundary); the JSON
        # objects never appear as desktop types.
        self.assertNotIn("import org.json", control)
        self.assertIn("const val MAX_IMPORT_BYTES = 8 * 1024 * 1024", control)
        # Both clients show the same rows, built once in :core.
        self.assertIn("EmailMaterial.details(", control)

    def test_connection_details_are_shared_and_mask_passwords(self):
        material = (CORE / "Utilities/EmailMaterial.kt").read_text(encoding="utf-8")
        self.assertIn("data class Detail(", material)
        self.assertIn("const val SECRET_MASK", material)
        self.assertIn("fun details(account: Account, revealSecrets: Boolean)", material)
        # Android's card dialog uses the shared builder instead of its own row code.
        adapter = (APP / "View/AndroidVaultController.kt").read_text(encoding="utf-8")
        self.assertIn("EmailMaterial.details(actual.getEmailAccount(), revealSecrets = true)", adapter)
        self.assertNotIn('append("Auth type: ")', adapter)
        # The desktop dialog starts masked and only reveals on request.
        owner = (DESKTOP / "control/DesktopEmailDetails.kt").read_text()
        self.assertIn("var secretsVisible = false", owner)
        self.assertIn("request.secretsVisible = !request.secretsVisible", owner)

    def test_email_entries_stay_out_of_the_otp_editors(self):
        android_editor = (APP / "Dialogs/ManualEntryDialog.kt").read_text(encoding="utf-8")
        self.assertIn("EntryDraft.offerableTypes()", android_editor)
        # The one shared list keeps both forms from offering an imported kind again.
        draft = (CORE / "Database/EntryDraft.kt").read_text(encoding="utf-8")
        for kind in ("PASSWORD &&", "PASSWORD_ARCHIVE &&", "EMAIL"):
            self.assertIn(f"it != Entry.OTPType.{kind}", draft)

    def test_the_feature_is_documented_for_both_clients(self):
        readme = text("README.md")
        self.assertIn("### Email accounts (FairEmail)", readme)
        self.assertIn("Passwords and Email tabs", readme)
        self.assertIn("**+ → Add email account**", readme)
        self.assertIn("**Add entry → Add email account…**", readme)
        self.assertIn("**Tools → Import FairEmail backup…**", readme)
        self.assertIn("View connection details", readme)
        changelog = text("CHANGELOG.md")
        unreleased = changelog.split("#### Unreleased", 1)[1]
        self.assertIn("Email accounts", unreleased)

    def test_email_coverage_exists_at_every_layer(self):
        # Core: the material round trip. Desktop: the control layer and the form.
        self.assertIn("fun connectionDetailsMaskPasswordsAndNameTheProtocols",
                      text("core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/EmailMaterialTest.kt"))
        self.assertIn("class EmailAccountsTest",
                      (DESKTOP_TEST / "control/EmailAccountsTest.kt").read_text(encoding="utf-8"))




if __name__ == "__main__":
    unittest.main()
