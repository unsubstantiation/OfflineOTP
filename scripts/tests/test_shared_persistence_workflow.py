"""Chunk-6 sequencing guards; not proof of filesystem durability or native ABI correctness."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

def text(path):
    return (ROOT / path).read_text()

class SharedPersistenceWorkflowTest(unittest.TestCase):
    def test_shared_policy_has_no_secret_or_filesystem_ownership(self):
        policy = text('shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/PersistenceWorkflow.kt')
        for forbidden in ['import java.', 'import android.', 'import platform.', 'ByteArray', 'SecretKey', 'password:', 'callback', 'File(']:
            self.assertNotIn(forbidden, policy)
        for marker in ['metadataCommitted', 'PersistencePhase.RECOVERY_REQUIRED', 'JournalAction.BLOCK', 'markerMatches', 'requiresBackup', 'PersistenceWorkflow(redacted)']:
            self.assertIn(marker, policy)

    def test_jvm_credentials_are_not_published_before_self_contained_promotion(self):
        source = text('core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/PasswordVault.kt')
        create = source.split('fun create(password: String, initialEntries:', 1)[1].split('fun open(', 1)[0]
        rekey = source.split('fun changePassword(', 1)[1].split('private fun provisionCredentials', 1)[0]
        for block in [create, rekey]:
            self.assertIn('CredentialSettings()', block)
            self.assertLess(block.index('writeEncrypted('), block.index('candidateSettings.publish(settings)'))
        self.assertNotIn('restoreBackup()', rekey)
        self.assertNotIn('rollbackSettings', rekey)
        self.assertIn('PersistenceWorkflow(true, false)', rekey)
        self.assertIn('check(workflow.backedUp())', rekey)
        self.assertIn('check(workflow.promoted())', rekey)
        encrypted = source.split('private fun writeEncrypted', 1)[1]
        self.assertLess(encrypted.index('VaultContainer.openWithKey(key, ciphertext)'), encrypted.index('vaultFile.writeAtomic(ciphertext)'))
        self.assertIn('PersistenceWorkflow(false, false)', encrypted)

    def test_android_journal_retains_native_locks_and_uses_shared_commit_recovery_policy(self):
        source = text('app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/DatabaseHelper.kt')
        self.assertIn('PersistenceRecoveryPolicy.journal(', source)
        self.assertIn('JournalAction.BLOCK', source)
        self.assertIn('JournalAction.PROMOTE', source)
        transaction = source.split('fun changeVaultEncryptionWithContainer(', 1)[1].split('fun canInitialize', 1)[0]
        for marker in ['synchronized(DatabaseFileLock)', 'PersistenceWorkflow(!initialize, true)', 'check(workflow.staged())', 'workflow.metadataCommitted(decision.portable(), id == marker)', 'workflow.promotionFailed()', 'transactionPoisoned = true']:
            self.assertIn(marker, transaction)
        self.assertLess(transaction.index('FileHelper.writeBytesToFile(file(context, PENDING_FILE), staged)'), transaction.index('commit.commit(id)'))
        self.assertLess(transaction.index('workflow.metadataCommitted('), transaction.index('recoverPendingDatabaseLocked(context, id)'))
        self.assertIn('metadataCommitInProgress = true', transaction)
        self.assertIn('PersistenceRecoveryPolicy.canInitialize(', source)

    def test_apple_production_injects_shared_policy_without_runtime_oracle_fallback(self):
        store = text('ios/App/Sources/VaultStore.swift')
        self.assertIn('persistence: SharedPersistenceAdapter()', store)
        adapter = text('ios/App/Sources/SharedPersistenceAdapter.swift')
        self.assertIn('PersistenceWorkflow(requiresBackup:', adapter)
        self.assertNotIn('NativePersistenceAdapter', adapter)
        vault = text('ios/Sources/OfflineOTPKit/PasswordVault.swift')
        rekey = vault.split('public func changePassword(', 1)[1].split('public func snapshot', 1)[0]
        self.assertLess(rekey.index('writeEncrypted('), rekey.index('candidateSettings.publish(to: settings)'))
        self.assertNotIn('restoreBackup()', rekey)
        for marker in ['workflow.backedUp()', 'workflow.beginPromotion()', 'workflow.promoted()', 'workflow.promotionFailed()']:
            self.assertIn(marker, rekey)
        storeRekey = store.split('public func changePassword(', 1)[1].split('// MARK: - The list', 1)[0]
        self.assertIn('catch VaultWriteError.ioFailure', storeRekey)
        self.assertIn('lock()', storeRekey)
        self.assertIn('then the new password if necessary', storeRekey)
        self.assertIn('persistence.transaction(requiresBackup: false)', vault)
        self.assertIn('persistence.transaction(requiresBackup: true)', vault)
        self.assertIn('storageLock.lock()', vault)

    def test_interruption_tests_cover_ambiguous_promotion_and_legacy_rekey(self):
        jvm = text('core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/PasswordVaultTest.kt')
        self.assertIn('rekeyCrashAfterPromotionBeforeSettingsLeavesSelfContainedRecoveryAndOldBackup', jvm)
        self.assertIn('rekeySettingsFailureCannotRollBackAnAlreadyPromotedPortableFile', jvm)
        self.assertIn('failedRekeyDoesNotChangeCredentialsOrOverwriteRollback', jvm)
        apple = text('ios/Tests/OfflineOTPKitTests/PersistenceInterruptionTests.swift')
        self.assertIn('testLegacyRekeyKeepsOriginalSettingsUntilPromotion', apple)
        self.assertIn('testAcknowledgmentFailureAfterRekeyDoesNotOverwriteRecoverableCandidate', apple)
        self.assertIn('testSharedJournalDoesNotDiscardAmbiguousOrCommittedWork', text('ios/App/Tests/SharedPersistenceAdapterTests.swift'))
