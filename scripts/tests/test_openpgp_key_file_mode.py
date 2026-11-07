"""Guards for the in-app OpenPGP key-file mode on Android.

The rpgp adapter, its JNA bindings and the packaged `jniLibs` used to ship with no production caller:
the backup screen only ever drove an external OpenKeychain provider. These guards pin the wiring that
makes the in-app engine reachable, and the rules that keep it as careful as the provider path —
bounded documents, an owned entry snapshot, a passphrase that is never stored, and buttons that stay
disabled with a reason when the backend or the key documents are missing.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
VALUES = ROOT / "app/src/main/res/values"
ACTIVITY = APP / "Activities/BackupActivity.kt"
SETTINGS_ACTIVITY = APP / "Activities/SettingsActivity.kt"
SETTINGS = APP / "Utilities/Settings.kt"
BACKUP_TASK = APP / "Tasks/OpenPgpKeyFileBackupTask.kt"
RESTORE_TASK = APP / "Tasks/OpenPgpKeyFileRestoreTask.kt"
PORT = APP / "openpgp/RpgpOpenPgpPort.kt"
POLICY = CORE / "Utilities/OpenPgpPolicy.kt"


class OpenPgpKeyFileModeTest(unittest.TestCase):
    def test_the_backup_screen_reaches_the_in_app_engine(self):
        """The adapter must have a production caller, not just packaging."""
        activity = ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("import com.fairuniversal.offlineotp.openpgp.RpgpOpenPgpPort", activity)
        self.assertIn("private fun usesKeyFileMode(): Boolean = openPgpMode() == OpenPgpMode.KEY_FILE",
                      activity)
        # Both actions branch on the mode before touching the provider service.
        for action in ("backupEncryptedWithPGP", "restoreEncryptedWithPGP"):
            body = activity[activity.index(f"private fun {action}("):]
            head = body[:body.index("requireOpenPgpService()")]
            self.assertIn("if (usesKeyFileMode())", head, action)
        self.assertIn("OpenPgpKeyFileBackupTask(this, entries, recipients, openPgpPort, uri)", activity)
        self.assertIn("OpenPgpKeyFileRestoreTask(this, uri, secretFile, passphrase, openPgpPort)",
                      activity)

    def test_the_key_file_mode_never_binds_or_requires_a_provider(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        setup = activity[activity.index("private fun setupBackupType("):]
        key_file_branch = setup[setup.index("if (usesKeyFileMode())"):]
        key_file_branch = key_file_branch[:key_file_branch.index("txtBackupLabel?.setText(R.string.backup_label_pgp)")]
        self.assertIn("releaseOpenPgpService()", key_file_branch)
        self.assertIn("updateKeyFileAvailability()", key_file_branch)
        # A resumed picker action must not wait for a provider binding in this mode.
        self.assertIn("!usesKeyFileMode() && pgpServiceConnection?.isBound() != true", activity)

    def test_the_buttons_report_why_they_are_disabled(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("OpenPgpKeyFilePolicy.availability(", activity)
        self.assertIn("backendAvailable = keyFileBackendAvailable", activity)
        for reason in ("backup_desc_openpgp_backend_unavailable",
                       "backup_desc_openpgp_keyfiles_recipients",
                       "backup_desc_openpgp_keyfiles_secret"):
            self.assertIn(reason, activity)
        strings = (VALUES / "strings_backup.xml").read_text(encoding="utf-8")
        for reason in ("backup_desc_openpgp_backend_unavailable",
                       "backup_desc_openpgp_keyfiles_recipients",
                       "backup_desc_openpgp_keyfiles_secret",
                       "backup_label_pgp_keyfiles"):
            self.assertIn(f'<string name="{reason}"', strings)
        # Loading the native library is probed once, not on every redraw.
        self.assertIn("by lazy { openPgpPort.isBackendAvailable() }", activity)
        self.assertIn("fun isBackendAvailable(): Boolean = tryLoadNative() != null",
                      PORT.read_text(encoding="utf-8"))

    def test_the_passphrase_is_asked_for_and_never_stored(self):
        activity = ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("promptForKeyFilePassphrase", activity)
        # An unprotected key is an explicit answer, not a forced empty password.
        self.assertIn("backup_dialog_openpgp_passphrase_unprotected", activity)
        self.assertIn("startKeyFileRestore(uri, secretFile, null)", activity)
        # A relock or destroy dismisses the chooser along with the password dialog.
        for lifecycle in ("override fun onVaultLocked()", "public override fun onDestroy()"):
            body = activity[activity.index(lifecycle):]
            self.assertIn("activeKeyFileDialog?.dismiss()", body[:600], lifecycle)

        restore = RESTORE_TASK.read_text(encoding="utf-8")
        self.assertIn("private val passphrase: CharArray?", restore)
        self.assertIn("passphrase?.fill('\\u0000')", restore)
        self.assertIn("secretKey?.fill(0)", restore)
        # No preference write, no logging of the passphrase.
        self.assertNotIn("setOpenPGP", restore)
        self.assertNotIn("Log.", restore)
        self.assertNotIn("printStackTrace", restore)
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertNotRegex(settings, r"fun (get|set)OpenPGP\w*Passphrase")
        keys = (VALUES / "settings.xml").read_text(encoding="utf-8")
        self.assertNotIn("passphrase", keys.lower())

    def test_the_tasks_reuse_the_shared_bounded_codec(self):
        backup = BACKUP_TASK.read_text(encoding="utf-8")
        self.assertIn("OpenPgpBackupCodec.seal(port, plain, keyBlocks)", backup)
        self.assertIn("StorageAccessHelper.loadFile", backup)
        self.assertIn("StorageAccessHelper.saveFile(applicationContext, uri, message, this::canWrite)",
                      backup)
        # An owned snapshot, destroyed on every exit path, like EncryptedBackupTask.
        self.assertIn("DatabaseHelper.stringToEntries(DatabaseHelper.entriesToString(entries))", backup)
        self.assertIn("protected override fun onCancellation() { destroySnapshot() }", backup)
        self.assertIn("protected override fun discardResult(result: BackupTaskResult) { destroySnapshot() }",
                      backup)
        self.assertIn("VaultSession.getKey() != null", backup)
        # An unreadable recipient fails the backup rather than silently dropping a recipient.
        self.assertIn("if (block.isEmpty()) return false", backup)

        restore = RESTORE_TASK.read_text(encoding="utf-8")
        self.assertIn("OpenPgpBackupCodec.open(port, message, secretKey, passphrase)", restore)
        self.assertIn("OpenPgpKeyFilePolicy.isUsableKeyUri(secretKeyUri)", restore)
        self.assertIn("backup_toast_import_decryption_failed", restore)

    def test_the_mode_defaults_to_the_provider_for_existing_installs(self):
        policy = POLICY.read_text(encoding="utf-8")
        self.assertIn("fun parse(value: String?): OpenPgpMode = if (value == KEY_FILE_VALUE) KEY_FILE else PROVIDER",
                      policy)
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("OpenPgpMode.parse(getString(R.string.settings_key_openpgp_mode, OpenPgpMode.PROVIDER_VALUE))",
                      settings)
        preferences = (ROOT / "app/src/main/res/xml/preferences.xml").read_text(encoding="utf-8")
        self.assertIn('android:key="@string/settings_key_openpgp_mode"', preferences)
        self.assertIn('android:defaultValue="provider"', preferences)
        self.assertIn('android:key="@string/settings_key_openpgp_recipient_files"', preferences)
        self.assertIn('android:key="@string/settings_key_openpgp_secret_file"', preferences)

    def test_only_persisted_content_grants_are_used_as_key_documents(self):
        policy = POLICY.read_text(encoding="utf-8")
        self.assertIn('value.startsWith("content://")', policy)
        self.assertIn("InputPolicy.MAX_URI_CHARS", policy)
        self.assertIn("const val MAX_RECIPIENTS = 64", policy)
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("OpenPgpKeyFilePolicy.recipients(", settings)
        self.assertIn("if (OpenPgpKeyFilePolicy.isUsableKeyUri(stored)) stored else \"\"", settings)

        activity = SETTINGS_ACTIVITY.read_text(encoding="utf-8")
        # Read-only access, persisted so the next backup can still read the key.
        picker = activity[activity.index("private fun requestOpenPgpKeyFiles("):]
        picker = picker[:picker.index("private fun retainKeyFileGrant(")]
        self.assertIn("Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
                      picker)
        # Key documents are only ever read; the app must not ask to write to the user's key files.
        self.assertNotIn("FLAG_GRANT_WRITE_URI_PERMISSION", picker)
        self.assertIn("takePersistableUriPermission", activity)
        self.assertIn("handleOpenPgpKeyFileResult", activity)
        self.assertIn("refreshOpenPgpKeyFileSummaries", activity)

    def test_the_sandbox_compiles_the_new_sources(self):
        run = (ROOT / "scripts/kotlin-sandbox/run.sh").read_text(encoding="utf-8")
        for source in ("Tasks/OpenPgpKeyFileBackupTask.kt", "Tasks/OpenPgpKeyFileRestoreTask.kt",
                       "openpgp/RpgpOpenPgpPort.kt"):
            self.assertIn(source, run)


if __name__ == "__main__":
    unittest.main()
