"""The plain-text backup feature must stay removed.

The vault-security hardening removed the plain-text backup type (anyone reading a backed-up
file would get every seed in the clear). These guards fail if a plain-text backup path,
permission or constant is reintroduced.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
CONSTANTS = APP_KOTLIN / "Utilities/Constants.kt"
BACKUP_ACTIVITY = APP_KOTLIN / "Activities/BackupActivity.kt"


class NoPlainTextBackupTest(unittest.TestCase):
    def test_backup_type_enum_has_no_plaintext_member(self):
        text = CONSTANTS.read_text(encoding="utf-8")
        match = re.search(r"enum class BackupType \{(.*?)\}", text, re.DOTALL)
        self.assertIsNotNone(match, "BackupType enum not found")
        members = [word.strip() for word in match.group(1).split(",") if word.strip()]
        self.assertEqual(["ENCRYPTED", "OPEN_PGP", "UNAVAILABLE"], members,
                         "the plain-text backup type must stay removed")

    def test_no_plaintext_backup_constants(self):
        text = CONSTANTS.read_text(encoding="utf-8")
        for banned in ("BACKUP_MIMETYPE_PLAIN", "BACKUP_FILENAME_PLAIN",
                       "BACKUP_MIMETYPE_JSON", "INTENT_BACKUP_SAVE_DOCUMENT_PLAIN",
                       "INTENT_BACKUP_OPEN_DOCUMENT_PLAIN"):
            self.assertNotIn(banned, text, f"{banned} reintroduced a plain-text backup path")

    def test_backup_activity_handles_only_encrypted_and_pgp(self):
        """The backup screen's when-branches cover exactly the surviving backup types."""
        text = BACKUP_ACTIVITY.read_text(encoding="utf-8")
        self.assertNotIn("BackupType.PLAIN", text)
        for banned in ("OPEN_DOCUMENT_PLAIN", "SAVE_DOCUMENT_PLAIN", "BACKUP_MIMETYPE_JSON",
                       "restorePlain", "backupPlain", "loadFileString"):
            self.assertNotIn(banned, text, f"{banned} reintroduced a plain-text backup path")
        # Every `when (backupType)` branch set must cover ENCRYPTED, OPEN_PGP and UNAVAILABLE.
        for branch in re.finditer(r"when \(backupType\) \{(.*?)\n        \}", text, re.DOTALL):
            for expected in ("BackupType.ENCRYPTED", "BackupType.OPEN_PGP", "BackupType.UNAVAILABLE"):
                self.assertIn(expected, branch.group(1),
                              "a backup-type branch no longer covers all surviving types")

    def test_settings_default_backup_type_is_encrypted(self):
        settings = APP_KOTLIN / "Utilities/Settings.kt"
        text = settings.read_text(encoding="utf-8")
        self.assertIn(
            'getString(R.string.settings_key_backup_default_type, Constants.BackupType.ENCRYPTED.name)',
            text)


if __name__ == "__main__":
    unittest.main()
