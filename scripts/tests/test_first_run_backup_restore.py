from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class FirstRunBackupRestoreTest(unittest.TestCase):
    def test_android_final_setup_offers_create_or_restore_without_auto_creating(self):
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        layout = (ROOT / "app/src/main/res/layout/component_intro_finished.xml").read_text()
        self.assertIn("introFinishedCreateVault", layout)
        self.assertIn("introFinishedRestoreBackup", layout)
        self.assertIn("chooseFirstRunBackup()", activity)
        self.assertIn("createInitialVaultFromSetup()", activity)
        self.assertIn("Do not provision an empty vault merely by swiping onto it", activity)

    def test_android_restores_entries_in_same_initial_vault_transaction_and_keeps_auth_choice(self):
        task = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/FirstRunBackupRestoreTask.kt").read_text()
        self.assertIn("EncryptedBackupCodec.open(secret, bytes)", task)
        self.assertIn("DatabaseHelper.stringToEntries(document)", task)
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        self.assertIn("Your selected Android vault authentication remains unchanged", activity)
        self.assertIn("FinishIntroTask(resolvedContext, encryptionType, authMethod, password, androidSyncEnabled, initialEntries)", activity)
        finish = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/FinishIntroTask.kt").read_text()
        self.assertIn("changeVaultEncryptionWithContainer", finish)
        self.assertIn("credentials?.containerHeader()", finish)
        self.assertIn("encryptionType != Constants.EncryptionType.PASSWORD", finish)
        db = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/DatabaseHelper.kt").read_text()
        self.assertIn("Entry(it.toJSON())", db)
        self.assertIn("initialEntries", db)

    def test_restore_dialog_is_shared_for_desktop_and_ios(self):
        dialog = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/NewVaultRestoreDialog.kt").read_text()
        self.assertIn("New vault password", dialog)
        self.assertIn("no merge choice is needed", dialog)
        self.assertIn("NewVaultRestoreDialog(", (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text())
        self.assertIn("NewVaultRestoreDialog(", (ROOT / "ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/NewVaultRestoreViewController.kt").read_text())


if __name__ == "__main__":
    unittest.main()
