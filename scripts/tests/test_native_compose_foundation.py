"""Architecture gates only: actual Kotlin/Native and Xcode builds are still required."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'ui-foundation'
COMMON = MODULE / 'src/commonMain/kotlin'

class NativeComposeFoundationTest(unittest.TestCase):
    def test_foundation_has_native_targets_and_no_engine_dependencies(self):
        build = (MODULE / 'build.gradle').read_text()
        for target in ('iosArm64()', 'iosSimulatorArm64()', 'iosX64()', 'androidTarget', "jvm('desktop')"):
            self.assertIn(target, build)
        self.assertEqual(1, build.count('project('))
        self.assertIn("api project(':ui-contract')", build)
        self.assertNotIn('project(', (ROOT / 'ui-contract/build.gradle').read_text())
        self.assertIn("baseName = 'OfflineOTPPresentation'", build)
        for path in COMMON.rglob('*.kt'):
            for line in path.read_text().splitlines():
                if line.startswith('import '):
                    for forbidden in ('java.', 'android.', '.Utilities.', '.Database.', '.View.', '.ui.vault.'):
                        self.assertNotIn(forbidden, line, str(path))

    def test_existing_host_delegates_instead_of_copying_unlock(self):
        ui = ROOT / 'ui'
        self.assertIn("api project(':ui-foundation')", (ui / 'build.gradle').read_text())
        adapter = (ui / 'src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/UnlockScreen.kt').read_text()
        self.assertIn('SharedUnlockScreen(', adapter)
        self.assertNotIn('OutlinedTextField', adapter)
        self.assertNotIn('mutableStateOf', adapter)
        self.assertFalse((ui / 'src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/theme/ForestTheme.kt').exists())
        self.assertFalse((ui / 'src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/theme/ForestPalette.kt').exists())
        gate = (ROOT / 'scripts/kotlin-sandbox/compile-ui.sh').read_text()
        self.assertIn('"${FOUNDATION_SOURCES[@]}" "${COMPOSE_STUB_SOURCES[@]}" -d "$OUT/foundation"', gate)

    def test_ios_bridge_uses_shared_screen_and_revokes_closed_callbacks(self):
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/UnlockViewController.kt').read_text()
        self.assertIn('ComposeUIViewController', bridge)
        self.assertIn('SharedUnlockScreen(', bridge)
        self.assertIn('if (active && !busy && !creating) actions.unlock(password)', bridge)
        self.assertIn('fun close() { active = false;', bridge)
        self.assertNotIn('SwiftUI', bridge)

    def test_ios_release_uses_compose_unlock_without_swiftui_form_fallback(self):
        swift = (ROOT / 'ios/App/Sources/UnlockView.swift').read_text()
        self.assertIn('import OfflineOTPPresentation', swift)
        self.assertIn('UIViewControllerRepresentable', swift)
        self.assertIn('presentation.viewController(', swift)
        self.assertIn('store.unlock(password: password, confirmation: confirmation, creating: true)', swift)
        self.assertIn('store.unlockWithBiometrics()', swift)
        self.assertIn('coordinator.close()', swift)
        for widget in ('SecureField(', 'TextField(', 'Button(', 'canImport('):
            self.assertNotIn(widget, swift)
        project = (ROOT / 'ios/project.yml').read_text()
        self.assertIn('framework: Frameworks/OfflineOTPPresentation.xcframework', project)
        script = (ROOT / 'scripts/ios/build-compose-framework.sh').read_text()
        self.assertIn('set -euo pipefail', script)
        self.assertIn(':ui-foundation:assembleOfflineOTPPresentationReleaseXCFramework', script)

    def test_about_is_shared_production_content_not_a_swift_copy(self):
        self.assertFalse((ROOT / 'ios/App/Sources/AboutInfo.swift').exists())
        self.assertFalse((ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt').exists())
        self.assertTrue((COMMON / 'com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt').is_file())
        ios = (ROOT / 'ios/App/Sources/ComposeAboutHost.swift').read_text()
        self.assertIn('import OfflineOTPPresentation', ios)
        self.assertIn('coordinator.close()', ios)
        self.assertIn('guard active, let url = URL(string: url)', ios)
        self.assertIn('["https", "http"].contains', ios)
        self.assertNotIn('AboutInfo', ios)
        native = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AboutViewController.kt').read_text()
        self.assertIn('if (active) AboutDialog(', native)
        self.assertIn('active && actions.openLink(url)', native)
        self.assertIn('close(); actions.dismiss()', native)

    def test_auto_lock_uses_one_dialog_and_contract_with_guarded_native_save(self):
        self.assertFalse((ROOT / 'ios/Sources/OfflineOTPKit/AutoLockOptions.swift').exists())
        self.assertFalse((ROOT / 'ui-model/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AutoLockOptions.kt').exists())
        contract = ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AutoLockOptions.kt'
        self.assertTrue(contract.is_file())
        self.assertNotIn('import ', contract.read_text())
        self.assertIn("api project(':ui-contract')", (ROOT / 'ui-model/build.gradle').read_text())
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/AutoLockDialog.kt').read_text()
        self.assertIn('(options + currentSeconds).distinct()', form)
        native = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AutoLockViewController.kt').read_text()
        self.assertIn('if (active) AutoLockDialog(', native)
        self.assertIn('seconds in AutoLockOptions.including(currentSeconds)', native)
        self.assertIn('val saved = actions.save(seconds)', native)
        swift = (ROOT / 'ios/App/Sources/ComposeAutoLockHost.swift').read_text()
        for guard in ('active && store.isUnlocked', 'store.generation == generation',
                      'store.inactivityLockSeconds == expectedSeconds', 'guard current, seconds >= 0',
                      'coordinator.close()'):
            self.assertIn(guard, swift)
        settings = (ROOT / 'ios/App/Sources/SettingsView.swift').read_text()
        self.assertIn('ComposeAutoLockHost(store: store)', settings)
        self.assertNotIn('Picker("Auto-lock', settings)

    def test_password_change_has_one_ephemeral_form_and_guarded_native_adapter(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PasswordChangeDialog.kt').read_text()
        self.assertNotIn('rememberSaveable', form)
        self.assertIn('clear(); onSubmit(a, b, c)', form)
        self.assertIn('if (requiresUnlock)', form)
        self.assertIn('it.length <= maxFieldChars', form)
        self.assertIn('next != current && next == confirmation', form)
        settings = (ROOT / 'ios/App/Sources/SettingsView.swift').read_text()
        self.assertIn('ComposePasswordChangeHost(store: store)', settings)
        self.assertNotIn('currentVaultPassword', settings)
        self.assertNotIn('SecureField("New vault password"', settings)
        swift = (ROOT / 'ios/App/Sources/ComposePasswordChangeHost.swift').read_text()
        self.assertIn('active && store.isUnlocked && store.generation == generation', swift)
        self.assertIn('guard currentSession', swift)
        self.assertIn('$0.utf16.count <= InputPolicy.maxTextChars', swift)
        self.assertIn('coordinator.close()', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PasswordChangeViewController.kt').read_text()
        self.assertIn('if (active && !busy && result == null)', bridge)
        self.assertIn('requiresUnlock = false', bridge)
        self.assertIn('actions.successMessage()', bridge)

    def test_preferences_import_confirmation_is_shared_and_guarded_on_ios(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PreferencesImportDialog.kt').read_text()
        self.assertIn('platformName: String = "desktop"', form)
        self.assertIn('enabled = !busy && count != null && !failed', form)
        settings = (ROOT / 'ios/App/Sources/SettingsView.swift').read_text()
        self.assertIn('ComposePreferencesImportHost(store: store, data: item.data)', settings)
        self.assertNotIn('PreferencesExport.apply(data, into: store.preferencesPort)', settings)
        swift = (ROOT / 'ios/App/Sources/ComposePreferencesImportHost.swift').read_text()
        self.assertIn('ScratchPort', swift)
        self.assertIn('active && store.isUnlocked && store.generation == generation', swift)
        self.assertIn('store.reloadInactivityLock()', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PreferencesImportViewController.kt').read_text()
        self.assertIn('platformName = "iOS"', bridge)
        self.assertIn('if (active && !busy && count != null && !failed)', bridge)

    def test_delete_confirmation_is_shared_and_guarded_on_ios(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/EntryConfirmationDialog.kt').read_text()
        self.assertIn('onDismissRequest = onDismiss', form)
        self.assertNotIn('rememberSaveable', form)
        list_view = (ROOT / 'ios/App/Sources/EntryListView.swift').read_text()
        self.assertIn('ComposeEntryConfirmationHost(store: store, index: target.index', list_view)
        self.assertNotIn('.alert("Remove entry?"', list_view)
        self.assertNotIn('deletionActions', list_view)
        swift = (ROOT / 'ios/App/Sources/ComposeEntryConfirmationHost.swift').read_text()
        self.assertIn('store.generation == epoch', swift)
        self.assertIn('store.entries[index].fields == entry.fields', swift)
        self.assertIn('store.isRevealed(entry)', swift)
        self.assertIn('Are you sure you want to remove the account', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/EntryConfirmationViewController.kt').read_text()
        self.assertIn('if (failure == null) { close(); actions.dismiss() } else error = failure', bridge)

    def test_biometric_enrollment_confirmation_is_shared_compose(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/BiometricEnableDialog.kt').read_text()
        self.assertIn('PasswordVisualTransformation()', form)
        self.assertIn('onConfirm(password)', form)
        settings = (ROOT / 'ios/App/Sources/SettingsView.swift').read_text()
        self.assertIn('ComposeBiometricEnableHost(store: store)', settings)
        self.assertNotIn('SecureField("Vault password"', settings)
        self.assertNotIn('private func confirm()', settings)
        host = (ROOT / 'ios/App/Sources/ComposeBiometricEnableHost.swift').read_text()
        self.assertIn('store.enableBiometricUnlock(password: password)', host)
        self.assertIn('guard active, store.isUnlocked, store.biometry.isAvailable', host)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/BiometricEnableViewController.kt').read_text()
        self.assertIn('actions.confirm(password)', bridge)

    def test_desktop_otp_image_import_is_discoverable_from_tools(self):
        strings = (ROOT / 'ui-model/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultStrings.kt').read_text()
        self.assertIn('Import OTP URI / QR image', strings)
        dialog = (ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/UriImportDialog.kt').read_text()
        self.assertIn('Import QR code image…', dialog)
        self.assertIn('onChooseImage?.let', dialog)
        host = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text()
        self.assertIn('VaultStrings', host)
        self.assertIn('openQrImage(window)', host)

    def test_first_run_restore_is_full_replace_with_a_new_device_password(self):
        screen = (COMMON / 'com/fairuniversal/offlineotp/ui/unlock/SharedUnlockScreen.kt').read_text()
        self.assertIn('onRestore: (() -> Unit)? = null', screen)
        self.assertIn('Restore from encrypted backup…', screen)
        dialog = (COMMON / 'com/fairuniversal/offlineotp/ui/unlock/NewVaultRestoreDialog.kt').read_text()
        self.assertIn('Backup password', dialog)
        self.assertIn('New vault password', dialog)
        self.assertIn('no merge choice is needed', dialog)
        desktop = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text()
        self.assertIn('onRestore = if (!controller.isInitialized())', desktop)
        self.assertIn('EncryptedBackupCodec.open(backupPassword, bytes)', desktop)
        self.assertIn('controller.createVault(newPassword, imported!!)', desktop)

    def test_ios_first_run_restore_uses_shared_compose_form_and_native_file_adapter(self):
        unlock = (ROOT / 'ios/App/Sources/UnlockView.swift').read_text()
        self.assertIn('fileImporter(isPresented: $pickingRestore', unlock)
        self.assertIn('ComposeNewVaultRestoreHost(store: store, data: item.data)', unlock)
        swift = (ROOT / 'ios/App/Sources/ComposeNewVaultRestoreHost.swift').read_text()
        self.assertIn('store.restoreNewVault(encrypted: data', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/NewVaultRestoreViewController.kt').read_text()
        self.assertIn('NewVaultRestoreDialog(', bridge)
        self.assertIn('allowChooseBackup = false', bridge)
        store = (ROOT / 'ios/App/Sources/VaultStore.swift').read_text()
        self.assertIn('public func restoreNewVault(encrypted data: Data', store)
        self.assertIn('try vault.create(password: newPassword, entries: restored)', store)
