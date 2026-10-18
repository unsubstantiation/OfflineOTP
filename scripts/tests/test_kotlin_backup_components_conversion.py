"""Source-level guards for the broadcast/worker backup components.

Pins the ordering of the ``EncryptedBackupBroadcastReceiver`` gates (a foreign action must be
ignored before anything is read; each failure has its own notification), the worker's early
exits, and the two public constants the panic responder and the settings screen rely on.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RECEIVER = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Receivers"
            "/EncryptedBackupBroadcastReceiver.kt")
RECEIVER_BASE = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Receivers"
                 "/BackupBroadcastReceiver.kt")
WORKER = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Tasks/EncryptedBackupWorker.kt"


def ordered_index(haystack, needle):
    try:
        return haystack.index(needle)
    except ValueError:
        return -1


class BackupBroadcastReceiverGuardTest(unittest.TestCase):
    def setUp(self):
        self.text = RECEIVER.read_text(encoding="utf-8")

    def test_foreign_action_is_ignored_before_any_state_is_read(self):
        """The action gate is the first statement of onReceive."""
        self.assertRegex(
            self.text,
            r"override fun onReceive\(context: Context, intent: Intent\?\) \{\s*"
            r"if \(intent == null \|\| !ACTION\.equals\(intent\.getAction\(\)\)\) return",
        )

    def test_gate_order_and_failure_notifications(self):
        """enabled → canSaveBackup → slot → password → encryption, each with its own message."""
        positions = [
            ordered_index(self.text, "if (!settings.isEncryptedBackupBroadcastEnabled())"),
            ordered_index(self.text, "if (!canSaveBackup(context))"),
            ordered_index(self.text, "if (!settings.tryAcquireBroadcastBackupSlot()) return"),
            ordered_index(self.text, "password == null || password.isEmpty()"),
            ordered_index(self.text, "if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE)"),
        ]
        self.assertTrue(all(position >= 0 for position in positions),
                        f"one of the receiver gates is missing: {positions}")
        self.assertEqual(positions, sorted(positions), "the receiver gate order changed")

        for message in ("backup_receiver_encrypted_disabled", "backup_receiver_no_location_set",
                        "backup_receiver_no_password_set", "backup_receiver_custom_encryption_failed"):
            self.assertIn(message, self.text, f"missing failure notification {message}")

    def test_public_constants(self):
        self.assertIn('const val UNIQUE_WORK_NAME = "encrypted_backup"', self.text)
        self.assertIn(
            'const val ACTION = "org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP"', self.text)

    def test_work_is_queued_keep_unique(self):
        self.assertIn("ExistingWorkPolicy.KEEP", self.text)
        self.assertIn("OneTimeWorkRequest.Builder(EncryptedBackupWorker::class.java)", self.text)

    def test_base_receiver_stays_abstract_with_protected_helper(self):
        """The abstract base's canSaveBackup helper stays protected."""
        base = RECEIVER_BASE.read_text(encoding="utf-8")
        self.assertIn("abstract class BackupBroadcastReceiver : BroadcastReceiver()", base)
        self.assertIn("protected fun canSaveBackup(context: Context): Boolean", base)


class EncryptedBackupWorkerGuardTest(unittest.TestCase):
    def setUp(self):
        self.text = WORKER.read_text(encoding="utf-8")

    def test_early_exits_fail_without_notification(self):
        """Stopped or disabled work fails silently; every other exit posts a notification."""
        self.assertIn(
            "if (isStopped() || !settings.isEncryptedBackupBroadcastEnabled()) return Result.failure()",
            self.text)

    def test_never_writes_backup_without_password_or_location(self):
        self.assertIn(
            "if (password == null || password.isEmpty()) {\n            return fail(context, "
            "R.string.backup_receiver_no_password_set)",
            self.text)
        self.assertIn(
            "if (!settings.isBackupLocationSet()) {\n            return fail(context, "
            "R.string.backup_receiver_no_location_set)",
            self.text)

    def test_vault_read_is_under_the_file_lock(self):
        self.assertIn("synchronized(DatabaseHelper.DatabaseFileLock)", self.text)

    def test_entries_are_destroyed_after_the_backup(self):
        self.assertIn("for (entry in entries) entry.destroy()", self.text)


if __name__ == "__main__":
    unittest.main()
