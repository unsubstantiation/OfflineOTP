"""Chunk-5 wiring guards. They supplement, not replace, native compilation/device checks."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

def source(path):
    return (ROOT / path).read_text()

class SharedSessionAuthorityTest(unittest.TestCase):
    def test_common_authority_contains_only_portable_decisions_and_redacted_diagnostics(self):
        shared = source('shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/SessionAuthority.kt')
        for forbidden in ['import java.', 'import android.', 'import platform.', 'SecretKey', 'ByteArray', 'password:', 'Runnable', '@Synchronized']:
            self.assertNotIn(forbidden, shared)
        for marker in ['SessionAuthority(redacted)', 'UnlockAttemptPolicy(redacted)', 'Long.MAX_VALUE', 'exhausted', 'phase == SessionPhase.FAILED', 'generation == authority', 'size !in 1..64']:
            self.assertIn(marker, shared)

    def test_jvm_controller_delegates_transitions_and_retains_native_secret_ownership(self):
        controller = source('core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/SessionContracts.kt')
        for marker in ['domain.SessionAuthority()', 'authority.activate(expected, changed)', 'authority.beginUnlock()', 'authority.lock(reason)', 'authority.fail(error)', 'authority.handoffRejection', 'authority.canOfferBackup', 'synchronized(mutationLock)', 'clearHandoff()', 'backupHandoff?.close()']:
            self.assertIn(marker, controller)
        self.assertNotIn('state = SessionState.Unlocked', controller)
        self.assertNotIn('state = next(state.generation + 1)', controller)
        policy = source('core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AuthenticationResultPolicy.kt')
        self.assertIn('SessionResultPolicy.accepts', policy)
        main = source('app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt')
        update = main.split('private fun updateEncryption(newKey: ByteArray?, expectedGeneration: Long)', 1)[1].split('private fun ', 1)[0]
        self.assertLess(update.index('DatabaseHelper.readVault(this, encryptionKey)'), update.index('VaultSession.activate(encryptionKey, expectedGeneration)'))

    def test_android_provider_callbacks_capture_attempt_not_mutable_completion_generation(self):
        auth = source('app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AuthenticateActivity.kt')
        for marker in ['val expected = biometricGeneration', 'val ticket = biometricAttempt.start(expected', 'biometricAttempt.current(ticket, VaultSession.generation()', 'biometricTicket == ticket', 'finishWithResult(true, vaultKey, expected)', 'biometricAttempt.cancel(); biometricPending = false', 'biometricAttempt.close(); hardwareAttempt.close()', 'hardwareAttempt.complete(']:
            self.assertIn(marker, auth)
        self.assertNotIn('finishWithResult(true, vaultKey, biometricGeneration)', auth)
        task = source('app/src/main/kotlin/com/fairuniversal/offlineotp/Tasks/AuthenticationTask.kt')
        self.assertIn('val password = plainPassword.also { plainPassword = null }', task)
        self.assertIn('onCancellation() { plainPassword = null }', task)
        self.assertIn('portableRecoveryKey(applicationContext, password)', task)

    def test_desktop_unlock_and_session_owners_use_common_authority(self):
        base = 'desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/'
        unlock = source(base + 'DesktopUnlockOperation.kt')
        for marker in ['UnlockAttemptPolicy()', 'authority.current(attempt.ticket', 'authority.complete(attempt.ticket', 'authority.close()', 'discard(result)']:
            self.assertIn(marker, unlock)
        state = source(base + 'DesktopVaultState.kt')
        self.assertIn('domain.SessionAuthority()', state)
        self.assertIn('session.activate(session.generation, key !== newKey)', state)
        self.assertIn('SessionLockReason.EXPLICIT', state)
        timer = source('core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/ScreenTimerController.kt')
        self.assertIn('domain.InactivityPolicy()', timer)
        self.assertNotIn('deadline = started + delayMillis', timer)
        self.assertIn('inactivity.remaining(scheduler.elapsedRealtime())', timer)

    def test_apple_all_successes_require_native_file_authentication_and_shared_adoption(self):
        store = source('ios/App/Sources/VaultStore.swift')
        self.assertIn('private let sessionAuthority = SessionAuthority()', store)
        self.assertEqual(1, store.count('isUnlocked = true'))
        adoption = store.split('private func adoptUnlocked', 1)[1].split('///', 1)[0]
        self.assertLess(adoption.index('sessionAuthority.activate('), adoption.index('isUnlocked = true'))
        for marker in ['self.biometricAttempt.current(ticket: ticket', 'self.vault.vaultIdentifier == account', 'try self.vault.openWithKey(sessionKey)', 'self.adoptUnlocked(unlocked, expected: expected)', 'expectedGeneration: Int', 'generation == expectedGeneration', 'ContinuousClock().now', 'inactivity.expired(nowMillis:']:
            self.assertIn(marker, store)
        self.assertNotIn('generation += 1', store)
        self.assertNotIn('lastActivityDate', store)
        unlock = source('ios/App/Sources/ComposeUnlockHost.swift')
        self.assertIn('store.cancelPendingUnlock()', unlock)
        self.assertIn('hardwareAttempt.current(ticket: ticket', unlock)
        self.assertIn('expectedGeneration: generation', unlock)
        self.assertIn('store.cancelPendingUnlock()', source('ios/App/Sources/AppRootViewController.swift'))

    def test_native_gate_tests_cover_deferred_providers_and_real_handoff_cleanup(self):
        self.assertIn('testLateAndDuplicateBiometricCallbacksCannotUnlockAnotherAttemptOrSession', source('ios/App/Tests/BiometricUnlockTests.swift'))
        self.assertIn('testExportedAuthorityRevokesOnLockFailureAndRekeyWithoutTransportingKeys', source('ios/App/Tests/SharedSessionAuthorityTests.swift'))
        self.assertIn('portableSessionAuthorityAndNativeHandoffsRejectStaleResults', source('app/src/androidTest/kotlin/com/fairuniversal/offlineotp/AuthenticationFlowDeviceTest.kt'))
