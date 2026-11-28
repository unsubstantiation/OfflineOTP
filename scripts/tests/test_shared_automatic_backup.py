"""Chunk-8 production delegation ratchet; source checks are not native execution evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOMAIN = 'shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/'
CORE = 'core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/'
APP = 'app/src/main/kotlin/com/fairuniversal/offlineotp/'
IOS = 'ios/App/Sources/'
DESKTOP = 'desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/'


def read(path):
    return (ROOT / path).read_text()


class SharedAutomaticBackupTest(unittest.TestCase):
    def test_common_configuration_and_scheduling_do_not_access_native_services(self):
        for name in ['AutomaticBackupPolicy', 'AutomaticBackupConfigurationCodec']:
            text = read(DOMAIN + name + '.kt')
            for forbidden in ['import java.', 'import javax.', 'import android.', 'import platform.', 'org.json.',
                              'File(', 'SecretKey', 'Timer(', 'Thread', 'signingPassphrase']:
                self.assertNotIn(forbidden, text)
        text = read(DOMAIN + 'AutomaticBackupPolicy.kt')
        schedule = text.split('class AutomaticBackupSchedule', 1)[1].split('class BackupRetentionCandidate', 1)[0]
        for forbidden in ['ByteArray', 'password', 'signingKey', 'document:']:
            self.assertNotIn(forbidden, schedule)
        self.assertIn('ticket != this.ticket', schedule)
        self.assertIn('this.generation == generation', schedule)

    def test_configuration_validation_is_shared_before_native_adoption(self):
        jvm = read(CORE + 'AutomaticBackupCredentialCodec.kt')
        self.assertIn('configuration.canonical(text)', jvm)
        self.assertNotIn('fun integer(', jvm)
        self.assertIn('BackupAutomationPolicy().validRecipients(keys)', jvm)
        swift = read(IOS + 'AutomaticBackupCredentialStore.swift')
        self.assertEqual(2, swift.count('AutomaticBackupConfigurationCodec().canonical(text: text)'))
        self.assertIn('BackupAutomationPolicy().validRecipients(keys: arrays)', swift)
        shared = read(DOMAIN + 'AutomaticBackupConfigurationCodec.kt')
        self.assertIn('EntryJsonText.parse(text)', shared)
        self.assertIn('root.fields.keys.all', shared)
        self.assertNotIn('"signingKey"', shared)
        self.assertIn('"requiresSigning"', shared)
        self.assertIn('root["recipientKeys"] == null && !signing', shared)

    def test_native_controllers_delegate_timer_decisions_and_scoped_retention(self):
        for path in [DESKTOP + 'control/DesktopAutomaticBackup.kt', IOS + 'SessionAutomaticBackup.swift']:
            text = read(path)
            for required in ['AutomaticBackupSchedule()', 'automation.enable(', 'automation.changed(',
                             'automation.begin(', 'automation.complete(', 'automation.disable()',
                             'AutomaticBackupRetention().managedPlan(', 'InterchangeWorkflow(']:
                self.assertIn(required, text)
        self.assertIn('AutomaticBackupRetention().providerPlan(', read(CORE + 'BackupRetention.kt'))
        self.assertIn('file.isDirectory()', read(APP + 'Utilities/AutomaticBackupPruner.kt'))
        self.assertIn('canDropReverted(', read(APP + 'Utilities/PendingAutomaticBackup.kt'))

    def test_android_workers_cannot_bootstrap_a_locked_vault(self):
        worker = read(APP + 'Tasks/EncryptedBackupWorker.kt')
        self.assertNotIn('KeyStoreHelper.loadEncryptionKeyFromKeyStore', worker)
        self.assertIn('val encryptionKey: SecretKey? = VaultSession.getKey()', worker)
        self.assertIn('VaultSession.isCurrent(generation) && VaultSession.getKey() != null', worker)
        receiver = read(APP + 'Receivers/EncryptedBackupBroadcastReceiver.kt')
        self.assertLess(receiver.index('VaultSession.getKey() == null'), receiver.index('enqueueUniqueWork('))
        settings = read(APP + 'Utilities/Settings.kt')
        block = settings.split('open fun setBackupPassword(value:', 1)[1].split('open fun tryAcquireBroadcastBackupSlot()', 1)[0]
        self.assertIn('AutomaticBackupCredentialCodec.seal(key, plain)', block)
        self.assertIn('vault-abp1:', block)
        self.assertIn('VaultSession.getKey() !== key', block)
        self.assertNotIn('loadOrGenerateAsymmetricKeyPair', block)
        reader = settings.split('open fun getBackupPasswordEnc()', 1)[1].split('open fun setBackupPassword(', 1)[0]
        self.assertIn('AutomaticBackupCredentialCodec.open(vaultKey, encrypted)', reader)
        self.assertIn('val vaultKey = VaultSession.getKey() ?: return ""', reader)
        self.assertIn('return admitted(', reader)

    def test_native_ownership_crypto_and_failure_visibility_are_retained(self):
        desktop = read(DESKTOP + 'control/DesktopAutomaticBackup.kt')
        for required in ['ExclusiveExportFile.writeNew', 'input.close()', 'bytes?.fill(0)', 'task.current(state.generation', 'reportRestoreFailure()']:
            self.assertIn(required, desktop)
        swift = read(IOS + 'SessionAutomaticBackup.swift')
        for required in ['NSFileCoordinator', '.withoutOverwriting', '.completeFileProtection', 'encrypted.resetBytes', 'reportRestoreFailure()']:
            self.assertIn(required, swift)
        self.assertIn('BackupAutomationPolicy().resume(', read(IOS + 'ComposeVaultHost.swift'))
        self.assertIn('BackupAutomationPolicy().resume(', read('desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt'))
        self.assertIn('CryptoPrimitives.encryptWithAad', read(CORE + 'AutomaticBackupCredentialCodec.kt'))
        self.assertIn('VaultCrypto.seal', read('ios/Sources/OfflineOTPKit/AutomaticBackupCredentialCodec.swift'))
        self.assertIn('separatePasswordRecordsRequireTheUnlockedVaultKey', read('app/src/androidTest/kotlin/com/fairuniversal/offlineotp/AutomaticBackupDeviceTest.kt'))
