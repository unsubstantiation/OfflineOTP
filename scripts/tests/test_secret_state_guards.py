"""Guards for secret-bearing widget state and cleanup.

A password/PIN/seed widget that saves its text into Android view state leaks the secret into
the system's saved-state store (and onto the recent-tasks snapshot path), so the layouts of the
credential, authentication, intro, manual-entry and backup-password screens must keep
``android:saveEnabled="false"`` on those inputs, and the owning widgets must clear their text
when they hand ownership to a task or go away.
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# layout file -> ids of the secret-bearing inputs in that layout
SECRET_INPUTS = {
    "component_authentication.xml": {"passwordEntry", "passwordConfirm"},
    "component_intro_authentication.xml": {"introPasswordEdit", "introPasswordConfirm"},
    "component_password.xml": {"passwordEntry", "passwordConfirm"},
    "content_authenticate.xml": {"passwordEdit"},
    "activity_hardware_keys.xml": {"hardwarePassword"},
    "activity_hardware_unlock.xml": {"hardwareUnlockPin"},
    "dialog_password_entry.xml": {"passwordEntry", "passwordConfirm"},
    "dialog_manual_entry.xml": {"manual_secret", "manual_secret_view"},
}


def layout_inputs(layout):
    root = ET.parse(layout).getroot()
    for element in root.iter():
        if element.tag.endswith("EditText") or "TextInputEditText" in element.tag:
            yield element


class SecretWidgetStateTest(unittest.TestCase):
    def test_secret_inputs_do_not_save_view_state(self):
        problems = []
        for layout_name, ids in SECRET_INPUTS.items():
            layout = RES / "layout" / layout_name
            self.assertTrue(layout.is_file(), f"{layout_name} is missing")
            for element in layout_inputs(layout):
                element_id = element.get(ANDROID + "id", "")
                element_id = element_id.replace("@+id/", "").replace("@id/", "")
                if element_id in ids:
                    if element.get(ANDROID + "saveEnabled") != "false":
                        problems.append(f"{layout_name}: {element_id} lacks android:saveEnabled=\"false\"")
        self.assertEqual([], problems,
                         "secret-bearing inputs must not persist their text as view state")

    def test_every_declared_secret_input_layout_is_covered(self):
        """A layout gaining a password/secret input must be added to SECRET_INPUTS."""
        known = set(SECRET_INPUTS)
        for layout in sorted((RES / "layout").glob("*.xml")):
            for element in layout_inputs(layout):
                element_id = element.get(ANDROID + "id", "").replace("@+id/", "").replace("@id/", "")
                if re.search(r"password|secret", element_id, re.IGNORECASE):
                    self.assertIn(layout.name, known,
                                  f"{layout.name} has a secret-looking input {element_id}; "
                                  "add it to SECRET_INPUTS or rename it")


class SecretWidgetCleanupTest(unittest.TestCase):
    def app_source(self, relative):
        return (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp" / relative).read_text(encoding="utf-8")

    def test_authentication_clears_input_on_task_handoff_and_teardown(self):
        text = self.app_source("Activities/AuthenticateActivity.kt")
        self.assertIn("startBackgroundTask(task)\n        } finally {\n            // The task owns its immutable copy; do not leave another copy in the UI widget.\n            clearPasswordInput()", text)
        self.assertIn("override fun onDestroy() {\n        clearPasswordInput()", text)

    def test_manual_entry_dialog_clears_secret_on_dismissal(self):
        text = self.app_source("Dialogs/ManualEntryDialog.kt")
        self.assertIn("dialog.setOnDismissListener", text)
        self.assertIn('secretInput.setText("")', text)
        self.assertIn('secretView.setText("")', text)

    def test_password_entry_dialog_clears_when_dismissed(self):
        """dismiss() clears both inputs, including when the callback throws."""
        text = self.app_source("Dialogs/PasswordEntryDialog.kt")
        self.assertIn("private fun clearSensitiveInputs() {\n        passwordInput.setText(\"\")\n        passwordConfirm.setText(\"\")", text)
        self.assertIn("override fun dismiss() {\n        clearSensitiveInputs()\n        super.dismiss()", text)
        self.assertIn("try {", text)
        self.assertIn("} finally {\n            dismiss()", text)

    def test_intro_clears_credentials_after_transfer(self):
        text = self.app_source("Activities/IntroScreenActivity.kt")
        self.assertIn("authentication.clearCredentialInputs()", text)
        self.assertIn("override fun onDestroy() {\n        authenticationFragment?.clearCredentialInputs()", text)

    def test_password_encrypted_preference_keeps_no_decrypted_field(self):
        text = self.app_source("Preferences/PasswordEncryptedPreference.kt")
        self.assertNotRegex(text, r"private var (plain|decrypted)\w*[Pp]assword", text)


if __name__ == "__main__":
    unittest.main()
