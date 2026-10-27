"""Plain-text backups may be restored, but must never be exported.

The vault-security hardening removed plain-text backup creation because it would expose every seed.
Legacy plaintext JSON import is intentionally supported as a separately labeled, warning-confirmed
restore path. These guards reject any plaintext save route while keeping that import available.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
CORE_KOTLIN = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"
CONSTANTS = CORE_KOTLIN / "Utilities/Constants.kt"
BACKUP_ACTIVITY = APP_KOTLIN / "Activities/BackupActivity.kt"


class NoPlainTextBackupTest(unittest.TestCase):
    def test_backup_type_enum_has_no_plaintext_member(self):
        text = CONSTANTS.read_text(encoding="utf-8")
        match = re.search(r"enum class BackupType \{(.*?)\}", text, re.DOTALL)
        self.assertIsNotNone(match, "BackupType enum not found")
        members = [word.strip() for word in match.group(1).split(",") if word.strip()]
        self.assertEqual(["ENCRYPTED", "OPEN_PGP", "UNAVAILABLE"], members,
                         "plain-text must never become a backup/export type")

    def test_no_plaintext_export_constants(self):
        text = CONSTANTS.read_text(encoding="utf-8")
        for banned in ("BACKUP_MIMETYPE_PLAIN", "BACKUP_FILENAME_PLAIN",
                       "BACKUP_MIMETYPE_JSON", "INTENT_BACKUP_SAVE_DOCUMENT_PLAIN"):
            self.assertNotIn(banned, text, f"{banned} reintroduced a plain-text export path")
        self.assertIn("INTENT_BACKUP_OPEN_DOCUMENT_PLAIN", text,
                      "the explicit, import-only legacy restore route is missing")

    def test_plaintext_route_is_restore_only_and_warning_confirmed(self):
        text = BACKUP_ACTIVITY.read_text(encoding="utf-8")
        self.assertNotIn("BackupType.PLAIN", text)
        for banned in ("SAVE_DOCUMENT_PLAIN", "BACKUP_MIMETYPE_JSON", "backupPlain", "loadFileString"):
            self.assertNotIn(banned, text, f"{banned} reintroduced a plain-text export path")
        self.assertIn("restorePlainText(intent.data)", text)
        self.assertIn("PlainTextRestoreTask(this, uri)", text)
        self.assertIn("backup_plaintext_warning_message", text)
        self.assertIn("setPositiveButton(R.string.backup_button_restore_plaintext)", text)
        # Every `when (backupType)` branch set must cover the encrypted and OpenPGP types.
        for branch in re.finditer(r"when \(backupType\) \{(.*?)\n        \}", text, re.DOTALL):
            for expected in ("BackupType.ENCRYPTED", "BackupType.OPEN_PGP", "BackupType.UNAVAILABLE"):
                self.assertIn(expected, branch.group(1),
                              "a backup-type branch no longer covers all supported types")

    def test_settings_default_backup_type_is_encrypted(self):
        settings = APP_KOTLIN / "Utilities/Settings.kt"
        text = settings.read_text(encoding="utf-8")
        self.assertIn(
            'getString(R.string.settings_key_backup_default_type, Constants.BackupType.ENCRYPTED.name)',
            text)


if __name__ == "__main__":
    unittest.main()
