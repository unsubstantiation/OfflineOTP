"""Source-level guards for the converted document I/O helpers.

Pins the security-relevant policy of ``Utilities/BackupHelper.kt`` and
``Utilities/StorageAccessHelper.kt``: unique automatic-backup names (never truncating the last
recoverable backup), the encrypted-backup framing, the `content:`-only write path with its
chunked permission re-checks, and the bounded reads of untrusted documents.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKUP_HELPER = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/BackupHelper.kt"
STORAGE = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/StorageAccessHelper.kt"
# The encrypted-backup framing was extracted into the Android-free :core codec so the desktop
# client produces byte-identical backups; the framing guard therefore follows it there.
BACKUP_CODEC = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EncryptedBackupCodec.kt"


class BackupHelperGuardTest(unittest.TestCase):
    def setUp(self):
        self.text = BACKUP_HELPER.read_text(encoding="utf-8")

    def test_automatic_backup_names_are_unique(self):
        """A new UUID-named document is created; no existing backup is ever truncated."""
        self.assertIn('"otp_accounts_" + UUID.randomUUID()', self.text)
        self.assertIn('.json.gpg" else ".json.aes"', self.text)
        self.assertNotIn("createFile(backupMimeType(type), Constants.BACKUP_FILENAME", self.text,
                         "automatic backups must not write a fixed, truncatable filename")

    def test_backup_password_and_permission_gate(self):
        """backupToFile refuses an empty password and consults the permission supplier."""
        self.assertIn("password == null || password.isEmpty() || !permitted.getAsBoolean()",
                      self.text)
        self.assertIn("StorageAccessHelper.saveFile(context, uri, data, permitted)", self.text)

    def test_task_callers_supply_cancellation_or_permission_guards(self):
        """Legacy convenience overloads remain, but app tasks use the guarded versions."""
        tasks = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Tasks"
        pgp = (tasks / "PGPBackupTask.kt").read_text(encoding="utf-8")
        encrypted = (tasks / "EncryptedBackupTask.kt").read_text(encoding="utf-8")
        change = (tasks / "ChangeEncryptionTask.kt").read_text(encoding="utf-8")
        self.assertIn("StorageAccessHelper.saveFile(applicationContext, uri, bytes, this::canWrite)", pgp)
        self.assertIn("BackupHelper.backupToFile(applicationContext, uri, password, payload, this::canWrite)",
                      encrypted)
        self.assertIn(
            "EncryptionHelper.tryEncryptionChange(context, oldEncryptionKey, newEncryptionType, "
            "newKeyMaterial, this) { !isCanceled() }", change)

    def test_backup_helper_delegates_framing_to_core_codec(self):
        """BackupHelper owns only document I/O; byte framing is delegated to the shared codec."""
        self.assertIn("EncryptedBackupCodec.seal(password, plain)", self.text)
        self.assertNotIn("ByteBuffer", self.text,
                         "framing must live in the core codec, not be rebuilt in BackupHelper")

    def test_encrypted_backup_framing(self):
        """The framed layout is iteration count, then salt, then the AES-GCM output."""
        codec = BACKUP_CODEC.read_text(encoding="utf-8")
        self.assertIn(
            ".allocate(Constants.INT_LENGTH + Constants.ENCRYPTION_IV_LENGTH + encrypted.size)",
            codec)
        self.assertIn(".putInt(iter)", codec)
        self.assertIn(".put(salt)", codec)
        self.assertIn(".put(encrypted)", codec)


class StorageAccessHelperGuardTest(unittest.TestCase):
    def setUp(self):
        self.text = STORAGE.read_text(encoding="utf-8")

    def test_writes_are_content_scheme_only_and_bounded(self):
        self.assertIn('"content" != file.getScheme()', self.text)
        self.assertIn("file.toString().length > InputPolicy.MAX_URI_CHARS", self.text)
        self.assertIn("data.size > BoundedInputReader.MAX_DOCUMENT_BYTES", self.text)

    def test_write_chunking_rechecks_permission(self):
        """The permission is re-checked before the stream, inside the chunk loop and after flush."""
        self.assertIn("WRITE_CHUNK_BYTES", self.text)
        self.assertIn("private const val WRITE_CHUNK_BYTES = 8192", self.text)
        self.assertIn("if (descriptor == null || !permitted.getAsBoolean()) return false", self.text)
        self.assertIn("if (!permitted.getAsBoolean()) return false\n                        output.write", self.text)
        self.assertIn("permitted.getAsBoolean()\n                }", self.text)

    def test_reads_are_bounded_and_file_uris_restricted(self):
        """loadFile enforces the byte limit and allows file: URIs only inside the cache dir."""
        self.assertIn("BoundedInputReader.read(inputStream, BoundedInputReader.MAX_DOCUMENT_BYTES)",
                      self.text)
        self.assertIn('"file" != file.getScheme() || path == null', self.text)
        self.assertIn(
            "File(path).canonicalPath.startsWith(context.getCacheDir().canonicalPath + File.separator)",
            self.text)

    def test_display_name_is_bounded_and_control_stripped(self):
        self.assertIn("MAX_TEXT_CHARS", self.text)
        self.assertIn('name.replace(Regex("[\\\\p{Cntrl}]"), "")', self.text)


if __name__ == "__main__":
    unittest.main()
