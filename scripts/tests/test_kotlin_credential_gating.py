"""Source-level guards for the credential-change dialog's save button.

The button state needs a bound `DialogPreference`, which the desktop harness does not model,
so pin the call sites that decide it: the password/PIN branches must ask
`CredentialsPreference.canSaveCredentials`, and a credential below the minimum must not leave
Save enabled (the previous code only showed the warning). `Checks.kt` covers the predicate and
the minimums themselves.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREFERENCE = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Preferences"
              "/CredentialsPreference.kt")


class CredentialGatingGuardTest(unittest.TestCase):
    def setUp(self):
        self.text = PREFERENCE.read_text(encoding="utf-8")

    def test_save_state_comes_from_the_shared_predicate(self):
        self.assertIn("btnSave?.isEnabled = canSaveCredentials(value, minLength, password, confirm)",
                      self.text)

    def test_a_too_short_credential_does_not_leave_save_enabled(self):
        # The rejected shape enabled Save whenever both fields matched, whatever their length.
        self.assertNotIn("val canSave = password.isNotEmpty()", self.text)
        self.assertIn(
            "tooShortWarning?.visibility = if (password.length >= minLength) View.GONE else View.VISIBLE",
            self.text)

    def test_none_and_device_stay_saveable_without_a_credential(self):
        self.assertIn("if (value == AuthMethod.NONE || value == AuthMethod.DEVICE) {", self.text)
        self.assertIn("btnSave?.isEnabled = true", self.text)

    def test_the_minimums_come_from_the_shared_constants(self):
        self.assertIn("Constants.AUTH_MIN_PASSWORD_LENGTH", self.text)
        self.assertIn("Constants.AUTH_MIN_PIN_LENGTH", self.text)


if __name__ == "__main__":
    unittest.main()
