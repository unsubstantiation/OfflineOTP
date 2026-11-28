"""Chunk-4 wiring/ownership guards, not native runtime evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

def source(path):
    return (ROOT / path).read_text()

class SharedRestoreWorkflowTest(unittest.TestCase):
    def test_portable_application_state_contains_no_secret_or_native_payload(self):
        workflow = source('shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/RestoreWorkflow.kt')
        for forbidden in ['import java.', 'import android.', 'import platform.', 'ByteArray', 'password:', 'Entry>', 'SecretKey']:
            self.assertNotIn(forbidden, workflow)
        for marker in ['READING', 'PREVIEW', 'CONFIRMED', 'INSTALLING', 'SUCCEEDED', 'CANCELLED', 'RestoreWorkflow(redacted)', 'ticket != serial', 'authority != this.authority']:
            self.assertIn(marker, workflow)
        self.assertIn('phase != RestorePhase.CONFIRMED', workflow)
        self.assertIn('resultCount !in 0..MAX_ENTRIES', workflow)

    def test_android_shows_a_postdecode_preview_instead_of_a_prepicker_replace_prompt(self):
        activity = source('app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt')
        self.assertNotIn('activeRestoreModeDialog', activity)
        self.assertIn('previewRestore(result.payload!!, true)', activity)
        self.assertEqual(2, activity.count('previewRestore(restoredJSON, true)'))
        preview = activity.split('private fun previewRestore', 1)[1].split('/** Adds', 1)[0]
        for marker in ['workflow.decoded(', 'CheckboxConfirmationComposeView', 'workflow.confirm(', 'workflow.beginInstall(', 'pendingRestoreDocument = null', 'decoded?.forEach { it.destroy() }']:
            self.assertIn(marker, preview)
        self.assertLess(preview.index('workflow.confirm('), preview.index('restoreEntries(document, finish)'))

    def test_android_final_transaction_rechecks_authority_and_current_merged_size(self):
        operation = source('app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/VaultRestoreOperation.kt')
        for marker in ['workflow.decoded(', 'workflow.confirm(', 'synchronized (VaultSession.MutationLock)', 'workflow.beginInstall(', 'merged.size', 'workflow.installed(']:
            self.assertIn(marker, operation)
        self.assertLess(operation.index('workflow.beginInstall('), operation.index('val write = persistence.save'))
        finish = source('app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/FinishIntroTask.kt')
        self.assertIn('restore?.start(taskGeneration(), true)', finish)
        self.assertLess(finish.index('restore.beginInstall('), finish.index('val write = DatabaseHelper.changeVaultEncryptionWithContainer'))

    def test_desktop_preview_and_installer_both_use_shared_decisions(self):
        base = 'desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/'
        restore = source(base + 'DesktopEncryptedRestore.kt')
        for marker in ['request.workflow.decoded(', 'request.workflow.confirm(', 'request.workflow.beginInstall(', 'state.key === request.authorityKey', 'request.workflow.cancel()']:
            self.assertIn(marker, restore)
        installer = source(base + 'BackupCoordinator.kt')
        self.assertLess(installer.index('workflow.beginInstall('), installer.index('check(controller.save(state.key, merged))'))
        first = source('desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt')
        self.assertIn('workflow.beginInstall(ticket, 0, restoreActive.get() && !controller.isInitialized()', first)

    def test_apple_confirmation_consumes_the_preview_without_a_second_decryption(self):
        store = source('ios/App/Sources/VaultStore.swift')
        confirmation = store.split('public func restore(encrypted', 1)[1].split('/// True when `data`', 1)[0]
        self.assertNotIn('EncryptedBackup.open', confirmation)
        for marker in ['pending.source == data', 'pending.format == format', 'pending.variant == andOtpVariant', 'restoreWorkflow.confirm(', 'pendingRestore = nil', 'restoreWorkflow.beginInstall(', 'commit(plan.entries)']:
            self.assertIn(marker, confirmation)
        self.assertIn('RestoreMergePolicy().plan(', store)
        self.assertNotIn('VaultRestoreMerge.plan(', store)
        self.assertIn('sessionAuthority.lock(reason: .explicit)', store)
        self.assertIn('private func didBeginSession() {\n        cancelPendingRestore()', store)
        host = source('ios/App/Sources/ComposeVaultHost.swift')
        self.assertIn('if surfaceKey == "restore" { store.cancelPendingRestore() }', host)
        self.assertIn('draft["password"] = "" // Confirmation consumes', host)

    def test_jvm_merge_is_a_native_identity_adapter_over_the_shared_policy(self):
        merge = source('core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/VaultRestoreMerge.kt')
        self.assertIn('RestoreMergePolicy().plan(', merge)
        self.assertIn('decision.importedIndices', merge)
        self.assertIn('iterator.remove(); entry.destroy()', merge)
        self.assertIn('if (decision.retainCurrent) merged.addAll(current)', merge)
        self.assertIn('testPreviewIsReadOnlyBoundToTheFileAndCannotReplayAfterLock', source('ios/App/Tests/SharedRestoreWorkflowTests.swift'))
