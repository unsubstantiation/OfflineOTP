"""Source guards complement the Google export parser and device tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class GoogleMigrationWiringTest(unittest.TestCase):
    def test_scanned_image_and_external_uris_reach_the_migration_importer(self):
        main = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("addQRCode(result.getContents())", body(main, "override fun onActivityResult"))
        self.assertIn("ScanQRCodeFromFile.scanQRImage", body(main, "override fun onActivityResult"))
        self.assertIn("GoogleAuthenticatorMigration.isMigration(result)", body(main, "private fun addQRCode"))
        action = body(main, "private fun checkIntent")
        self.assertIn("GoogleAuthenticatorMigration.isMigration(intentData)", action)
        self.assertIn("callingIntent.data = null", action)
        self.assertIn("addMigrationQRCode(intentData)", action)

    def test_batch_and_confirmation_are_session_owned_and_cleaned_up(self):
        main = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        for lifecycle in ("override fun onVaultLocked", "override fun onDestroy"):
            self.assertIn("dismissMigrationImport()", body(main, lifecycle))
        cleanup = body(main, "private fun dismissMigrationImport")
        self.assertIn("migrationCollector?.close()", cleanup)
        self.assertIn("pendingMigrationImport?.close()", cleanup)
        self.assertLess(cleanup.index("pendingMigrationImport?.close()"), cleanup.index("dialog?.dismiss()"))
        confirmation = body(main, "private fun showMigrationConfirmation")
        self.assertIn("synchronized(VaultSession.MutationLock)", confirmation)
        self.assertIn("pending.take(VaultSession.generation(), authorized)", confirmation)
        self.assertIn("currentAdapter.importEntries(confirmed)", confirmation)
        self.assertIn("pending.close()", confirmation)

    def test_batch_persists_once_before_updating_the_adapter(self):
        adapter = (APP / "View/EntriesCardAdapter.kt").read_text(encoding="utf-8")
        method = body(adapter, "internal fun importEntries")
        self.assertEqual(1, method.count("persistence.save("))
        self.assertLess(method.index("persistence.save("), method.index("entries.addDistinctEntries(accepted)"))
        self.assertIn("VaultSession.MutationLock", method)
        self.assertIn("getAutoBackupEncryptedPasswordsEnabled()", method)
        self.assertIn("it.destroy()", method)


if __name__ == "__main__":
    unittest.main()
