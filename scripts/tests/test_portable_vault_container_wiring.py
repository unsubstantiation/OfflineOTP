"""Source guards, not native execution evidence, for the portable file-copy boundary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def source(path):
    return (ROOT / path).read_text()


class PortableVaultContainerWiringTest(unittest.TestCase):
    def test_android_stages_header_with_ciphertext_before_committing_metadata(self):
        db = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/DatabaseHelper.kt")
        body = db.split("fun changeVaultEncryptionWithContainer", 1)[1]
        self.assertLess(body.index("encryptedEntries(entries, newKey, header)"), body.index("commit.commit(id)"))
        self.assertLess(body.index("FileHelper.writeBytesToFile(file(context, PENDING_FILE), staged)"), body.index("commit.commit(id)"))
        self.assertIn("header == null && VaultContainer.isContainer", body)
        self.assertIn("VaultContainer.openWithKey(key, bytes)", db)
        intro = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/FinishIntroTask.kt")
        self.assertIn("encryptionType != Constants.EncryptionType.PASSWORD", intro)
        self.assertIn("authMethod != Constants.AuthMethod.PASSWORD", intro)
        self.assertIn("credentials?.containerHeader()", intro)

    def test_android_copied_file_is_its_own_password_authority(self):
        settings = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/Settings.kt")
        for method, result in [("getAuthMethod", "Constants.AuthMethod.PASSWORD"),
                               ("getEncryption", "Constants.EncryptionType.PASSWORD"),
                               ("getFirstTimeWarningShown", "true")]:
            body = settings.split(f"open fun {method}()", 1)[1].split("\n    }", 1)[0]
            self.assertIn(f"if (DatabaseHelper.hasPortableVault(context)) return {result}", body)
        auth = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/AuthenticationTask.kt")
        self.assertLess(auth.index("DatabaseHelper.portableRecoveryKey"), auth.index("return if (isAuthUpgrade)"))
        activity = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AuthenticateActivity.kt")
        self.assertIn("existingAuthCredentials.isEmpty() && !portableVault", activity)
        db = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/DatabaseHelper.kt")
        recover = db.split("fun portableRecoveryKey", 1)[1].split("/** Non-secret", 1)[0]
        self.assertIn("VaultContainer.header(bytes)", recover)
        self.assertIn("VaultContainer.openWithKey(key, bytes)", recover)
        self.assertNotIn("settings", recover)

    def test_full_copy_does_not_serialize_live_entries_or_reencrypt(self):
        android = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/EncryptedBackupTask.kt").split("open class VaultSnapshotBackupTask", 1)[1]
        self.assertIn("DatabaseHelper.vaultSnapshot", android)
        self.assertIn("this::canWrite", android)
        self.assertIn("snapshot?.fill(0)", android)
        self.assertNotIn("entriesToString", android)
        self.assertNotIn("EncryptedBackupCodec.seal", android)
        desktop = source("desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopEncryptedExport.kt")
        self.assertIn("fun submitSnapshot", desktop)
        self.assertIn("input.close(); encrypted?.fill(0)", desktop)
        swift = source("ios/App/Sources/VaultStore.swift").split("public func exportVaultSnapshot", 1)[1].split("/// Previews", 1)[0]
        self.assertIn("vault.snapshot(key: key)", swift)
        self.assertNotIn("VaultDocument.encode", swift)
        self.assertNotIn("EncryptedBackup.seal", swift)

    def test_all_restore_decoders_recognize_container_before_other_framing(self):
        kotlin = source("core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/EncryptedBackupCodec.kt")
        swift = source("ios/Sources/OfflineOTPKit/EncryptedBackup.swift")
        self.assertIn("route == BackupRoute.VAULT_COPY", kotlin)
        self.assertIn('protocolPolicy.route(data, format: "auto"', swift)
        policy = source("shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/VaultProtocol.kt")
        self.assertLess(policy.index("vault -> route(BackupRoute.VAULT_COPY)"), policy.index("linscp -> route(BackupRoute.LINSCP)"))
        self.assertIn("VaultContainer.open", kotlin)
        self.assertIn("VaultContainer.open", swift)

    def test_swift_device_unlock_identity_uses_container_salt(self):
        swift = source("ios/Sources/OfflineOTPKit/PasswordVault.swift")
        identity = swift.split("public var vaultIdentifier", 1)[1].split("/// Provisions", 1)[0]
        self.assertLess(identity.index("VaultContainer.header"), identity.index("settings.string"))
        self.assertIn("header.salt", identity)

    def test_migration_is_discoverable_and_separate_from_export_password(self):
        shared = source("ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/EncryptedExportDialog.kt")
        self.assertIn("onMigrateAndSnapshot", shared)
        self.assertIn("Current vault recovery password", shared)
        self.assertIn("val current = recoveryPassword; clear(); onMigrateAndSnapshot(current)", shared)
        android = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt")
        self.assertIn("onMigrateAndSnapshot =", android)
        self.assertIn("VaultSnapshotBackupTask(this, uri, currentPassword)", android)
        desktop = source("desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt")
        self.assertIn("exporter.submitMigration", desktop)
        swift = source("ios/App/Sources/ComposeVaultHost.swift")
        self.assertIn('case "migrateVaultSnapshot":', swift)
        self.assertIn('draft.removeValue(forKey: "migrationPassword")', swift)

    def test_automatic_copies_have_separate_mode_without_reinterpreting_export_versions(self):
        codec = source("core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AutomaticBackupCredentialCodec.kt")
        self.assertIn('configuration.encode(folder, "", minutes, keep, emptyList(), false, true)', codec)
        desktop = source("desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopAutomaticBackup.kt")
        self.assertIn('if (vaultCopy) "" else snapshot()', desktop)
        self.assertIn('requireNotNull(rawSnapshot).invoke(key)', desktop)
        self.assertIn('listOf("aes", "pgp", "offlineotp")', desktop)
        swift = source("ios/App/Sources/SessionAutomaticBackup.swift")
        self.assertIn('if let vaultBytes = input.vaultBytes { encrypted = vaultBytes }', swift)
        self.assertIn('let vaultBytes: Data?', swift)
        android = source("app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/EntryPersistence.kt")
        self.assertIn('TrackedSnapshotBackupTask(context, finish)', android)
        for name in ["Tasks/EncryptedBackupWorker.kt", "Receivers/EncryptedBackupBroadcastReceiver.kt"]:
            self.assertIn('LoginBackupPolicy.usesVaultCopy(context)', source("app/src/main/kotlin/com/fairuniversal/offlineotp/" + name))

    def test_paused_export_mode_is_not_defaulted_to_snapshot_mode(self):
        desktop = source("desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt")
        self.assertIn('automaticVaultCopyMode = config.vaultCopy', desktop)
        self.assertIn('initialVaultCopy = automaticVaultCopyMode', desktop)
        self.assertIn('automaticVaultCopyMode = false; automaticRequiresSigning = true', desktop)
        swift = source("ios/App/Sources/ComposeVaultHost.swift")
        restore = swift.split('if let config = try store.loadAutomaticBackupConfiguration()', 1)[1]
        self.assertLess(restore.index('automaticVaultCopyMode = config.vaultCopy'), restore.index('BackupAutomationPolicy().resume('))
        self.assertIn('draft["automaticVaultCopy"] ?? String(automaticVaultCopyMode)', swift)
