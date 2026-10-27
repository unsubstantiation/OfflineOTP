"""Source-level integration guards; cryptography and tasks also have executed host tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
K = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


def source(path):
    return (K / path).read_text(encoding="utf-8")


class LoginBackupWiringTest(unittest.TestCase):
    def test_password_only_handoffs_cover_unlock_setup_and_credential_changes(self):
        auth = source("Tasks/AuthenticationTask.kt")
        intro = source("Tasks/FinishIntroTask.kt")
        change = source("Tasks/ChangeCredentialsTask.kt")
        self.assertIn("settings.getAuthMethod() != Constants.AuthMethod.PASSWORD", auth)
        self.assertIn("VaultSession.snapshot() !is SessionState.Unlocking", auth)
        self.assertIn("VaultSession.offerBackupKey(candidate, taskGeneration())", auth)
        self.assertIn("authMethod == Constants.AuthMethod.PASSWORD", intro)
        self.assertIn("VaultSession.offerBackupKey(candidate, taskGeneration())", intro)
        self.assertIn("newAuthMethod == Constants.AuthMethod.PASSWORD", change)
        self.assertIn("VaultSession.replaceBackupKeyWithoutNotification(backupCandidate, epoch)", change)
        self.assertIn("if (replacedBackup) VaultSession.notifyBackupKeyChanged()", change)
        self.assertNotIn("KeyStoreHelper", source("Utilities/LoginBackupKey.kt"))
        self.assertNotIn("KeyStoreHelper", source("Tasks/LoginEncryptedBackupTask.kt"))

    def test_change_marker_is_staged_before_save_and_retried_after_unlock(self):
        persistence = source("Utilities/EntryPersistence.kt")
        self.assertIn("if (before == after)", persistence)
        stage = persistence.index("PendingAutomaticBackup.stage(context")
        self.assertGreater(persistence.index("val result = DatabaseHelper.saveVault", stage), stage)
        self.assertIn("PendingAutomaticBackup.rollback(context, stage)", persistence)
        self.assertIn("reportOutcome(backupCallback, TaskOutcome.Value(", persistence)
        self.assertIn("task.setOutcomeCallback { outcome -> reportOutcome(callback, outcome) }", persistence)
        self.assertIn("PendingAutomaticBackup.complete(context, marker.token)", persistence)
        main = source("Activities/MainActivity.kt")
        self.assertIn("retryPendingAutomaticBackup()", main)
        resume = main[main.index("public override fun onResume()"):main.index("public override fun onPause()")]
        self.assertLess(resume.index("if (inactivityRelockExpired())"), resume.index("retryPendingAutomaticBackup()"))
        self.assertIn("settings.getAutoBackupEncryptedPasswordsEnabled(), backupCallback",
                      source("Utilities/VaultRestoreOperation.kt"))
        self.assertIn("PendingAutomaticBackup.clear(context)", source("Utilities/DatabaseHelper.kt"))
        pending = source("Utilities/PendingAutomaticBackup.kt")
        self.assertIn("ConcurrentHashMap.newKeySet", pending)
        self.assertIn("fun release(token: String) { inFlight.remove(token) }", pending)

    def test_legacy_configuration_is_preserved_and_restore_prompts_for_each_file(self):
        policy = source("Utilities/LoginBackupPolicy.kt")
        self.assertIn("prefs.contains(key)", policy)
        self.assertIn("settings_key_backup_password_enc", policy)
        self.assertIn("settings_key_backup_password)", policy)
        self.assertIn("legacy.isNullOrEmpty() && oldPlain.isNullOrEmpty()", policy)
        backup = source("Activities/BackupActivity.kt")
        restore = backup[backup.index("private fun doRestoreCrypt("):backup.index("private fun doRestoreCryptWithPassword(")]
        self.assertIn("PasswordEntryDialog.Mode.ENTER", restore)
        self.assertNotIn("getBackupPasswordEnc()", restore)
        self.assertIn("doBackupCryptWithLoginKey(uri, lease)", backup)
        settings = source("Activities/SettingsActivity.kt")
        self.assertIn("settings_dialog_msg_remove_old_backup_password", settings)
        self.assertIn("sharedPref.edit().remove(oldKey).commit()", settings)

    def test_broadcasts_cannot_create_a_snapshot_without_a_pending_change(self):
        receiver = source("Receivers/EncryptedBackupBroadcastReceiver.kt")
        self.assertLess(receiver.index("PendingAutomaticBackup.read(context) == null"),
                        receiver.index("enqueueUniqueWork("))
        worker = source("Tasks/EncryptedBackupWorker.kt")
        self.assertIn("PendingAutomaticBackup.read(context) ?: return Result.success()", worker)
        self.assertIn("PendingAutomaticBackup.claim(context, marker.token)", worker)
        task = source("Tasks/LoginEncryptedBackupTask.kt")
        self.assertIn("VaultSession.isBackupCurrent(lease)", task)
        self.assertIn("VaultSession.addBackupChangeListener(backupChanged)", task)


if __name__ == "__main__":
    unittest.main()
