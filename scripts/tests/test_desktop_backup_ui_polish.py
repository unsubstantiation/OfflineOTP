"""Shared backup filename policy guards retained after Swing prompt retirement."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE_KOTLIN = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities"
APP_KOTLIN = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities"


def read(path):
    return Path(path).read_text(encoding="utf-8")


class DatedBackupNameTest(unittest.TestCase):

    def test_shared_name_helper_matches_the_android_client(self):
        helper = read(CORE_KOTLIN / "BackupFileNames.kt")
        self.assertIn('TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"', helper)
        self.assertIn("Constants.BACKUP_FILENAME_CRYPT_FORMAT", helper)
        self.assertIn("Constants.BACKUP_FILENAME_PGP_FORMAT", helper)
        # Android formats its dates through the same helper, so the two clients cannot drift.
        tools = read(APP_KOTLIN / "Tools.kt")
        self.assertIn("BackupFileNames.timestamp(", tools)
        self.assertNotIn('SimpleDateFormat("yyyy-MM-dd_HH-mm-ss"', tools)
        # The sandbox harness compiles an explicit core file list; a new core source must be added.
        self.assertIn('"$KOTLIN_CORE/Utilities/BackupFileNames.kt"',
                      read(ROOT / "scripts/kotlin-sandbox/run.sh"))

    def test_core_helper_has_a_jvm_test(self):
        test = read(ROOT / "core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/BackupFileNamesTest.kt")
        self.assertIn("BackupFileNames.encrypted(", test)
        self.assertIn("BackupFileNames.openPgp(", test)


if __name__ == "__main__":
    unittest.main()
