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
        swift = (ROOT / 'ios/App/Sources/ComposeUnlockHost.swift').read_text()
        self.assertIn('import OfflineOTPPresentation', swift)
        self.assertIn('UnlockPresentation()', swift)
        self.assertIn('presentation.viewController(', swift)
        self.assertIn('store.unlock(password: password)', swift)
        self.assertIn('store.unlock(password: password, confirmation: confirmation, creating: true)', swift)
        self.assertIn('store.unlockWithBiometrics()', swift)
        self.assertIn('coordinator.close()', swift)
        for widget in ('SecureField(', 'TextField(', 'Button(', 'UIViewControllerRepresentable'):
            self.assertNotIn(widget, swift)
        root = (ROOT / 'ios/App/Sources/AppDelegate.swift').read_text()
        self.assertIn('@main', root)
        self.assertIn('UIApplicationDelegate', root)
        self.assertIn('AppRootViewController()', root)
        self.assertNotIn('import SwiftUI', root)
        project = (ROOT / 'ios/project.yml').read_text()
        self.assertIn('framework: Frameworks/OfflineOTPPresentation.xcframework', project)
        self.assertNotIn('excludes:', project)
        script = (ROOT / 'scripts/ios/build-compose-framework.sh').read_text()
        self.assertIn('set -euo pipefail', script)
        self.assertIn(':ui-foundation:assembleOfflineOTPPresentationReleaseXCFramework', script)

    def test_about_is_shared_production_content_not_a_swift_copy(self):
        self.assertFalse((ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt').exists())
        self.assertTrue((COMMON / 'com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt').is_file())
        generated = (ROOT / 'ios/App/Sources/AboutInfo.swift').read_text()
        self.assertIn('enum AboutInfo {', generated)
        self.assertIn('static func url(id: String) -> String', generated)
        ios = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('case "about":', ios)
        self.assertIn('row("aboutLink:website"', ios)
        self.assertIn('row("aboutLink:issues"', ios)
        self.assertIn('AboutInfo.url(id: parts[1])', ios)
        self.assertIn('["http", "https"].contains(url.scheme ?? "")', ios)
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
        swift = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        for guard in ('guard active, store.isUnlocked else', 'case "autoLock"',
                      'store.setInactivityLockSeconds(seconds)',
                      'let seconds = Int(raw), seconds >= 0', 'closeSurface()'):
            self.assertIn(guard, swift)
        self.assertIn('row("autoLock", "Auto-lock after inactivity"', swift)

    def test_password_change_has_one_ephemeral_form_and_guarded_native_adapter(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PasswordChangeDialog.kt').read_text()
        self.assertNotIn('rememberSaveable', form)
        self.assertIn('clear(); onSubmit(a, b, c)', form)
        self.assertIn('if (requiresUnlock)', form)
        self.assertIn('it.length <= maxFieldChars', form)
        self.assertIn('next != current && next == confirmation', form)
        swift = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('row("passwordChange", "Change vault password")', swift)
        self.assertIn('case "passwordChange":', swift)
        self.assertIn('store.changePassword(currentPassword: draft["current"] ?? "",', swift)
        self.assertIn('newPassword: draft["next"] ?? "",', swift)
        self.assertIn('confirmation: draft["confirmation"] ?? ""', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PasswordChangeViewController.kt').read_text()
        self.assertIn('if (active && !busy && result == null)', bridge)
        self.assertIn('requiresUnlock = false', bridge)
        self.assertIn('actions.successMessage()', bridge)

    def test_preferences_import_confirmation_is_shared_and_guarded_on_ios(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PreferencesImportDialog.kt').read_text()
        self.assertIn('platformName: String = "desktop"', form)
        self.assertIn('enabled = !busy && count != null && !failed', form)
        swift = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('row("prefsImport", "Import preferences…")', swift)
        self.assertIn('case "prefsImport": kind = "preferences"', swift)
        self.assertIn('PreferencesExport.apply(pendingImport, into: store.preferencesPort)', swift)
        self.assertIn('store.reloadInactivityLock()', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PreferencesImportViewController.kt').read_text()
        self.assertIn('platformName = "iOS"', bridge)
        self.assertIn('if (active && !busy && count != null && !failed)', bridge)

    def test_delete_confirmation_is_shared_and_guarded_on_ios(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/EntryConfirmationDialog.kt').read_text()
        self.assertIn('onDismissRequest = onDismiss', form)
        self.assertNotIn('rememberSaveable', form)
        swift = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('Are you sure you want to remove the account', swift)
        self.assertIn('case "delete":', swift)
        self.assertIn('guard let index = editorIndex, store.remove(at: index)', swift)
        self.assertIn('if store.isRevealed(entry)', swift)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/EntryConfirmationViewController.kt').read_text()
        self.assertIn('if (failure == null) { close(); actions.dismiss() } else error = failure', bridge)

    def test_biometric_enrollment_confirmation_is_shared_compose(self):
        form = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/BiometricEnableDialog.kt').read_text()
        self.assertIn('PasswordVisualTransformation()', form)
        self.assertIn('onConfirm(password)', form)
        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('case "biometric":', host)
        self.assertIn('openSurface("biometricEnable")', host)
        self.assertIn('store.enableBiometricUnlock(password: password)', host)
        self.assertIn('store.biometry.isAvailable', host)
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

    def test_ios_unlocked_vault_is_rendered_by_shared_compose(self):
        """SwiftUI production sources were removed; Compose owns the release UI."""
        root = (ROOT / 'ios/App/Sources/AppRootViewController.swift').read_text()
        self.assertIn('VaultComposeViewController(', root)
        self.assertIn('UnlockComposeViewController(', root)
        self.assertNotIn('EntryListView(', root)
        self.assertNotIn('import SwiftUI', root)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('import OfflineOTPPresentation', host)
        self.assertIn('final class VaultComposeViewController: UIViewController', host)
        self.assertIn('PortableVaultPresentation()', host)
        self.assertIn('PortableVaultActions, PortableSurfaceActions', host)
        self.assertIn('presentation.viewController(', host)
        self.assertNotIn('struct EntryRowView: View', host)
        self.assertNotIn('SecureField(', host)

        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text()
        self.assertIn('ComposeUIViewController', bridge)
        self.assertIn('PortableVaultScreen(', bridge)
        self.assertIn('PortableSurfaceDialog(', bridge)
        self.assertIn('fun updateVault(', bridge)
        self.assertIn('fun updateSurface(', bridge)

        screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text()
        self.assertIn('fun PortableVaultScreen(', screen)
        self.assertIn('fun PortableSurfaceDialog(', screen)
        for forbidden in ('com.fairuniversal.offlineotp.core', 'Database.', 'java.', 'android.'):
            self.assertNotIn(forbidden, screen)

        contract = ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortablePresentation.kt'
        self.assertTrue(contract.is_file())
        self.assertIn('data class PortableCard(', contract.read_text())
        self.assertIn('data class PortableSurfaceState(', contract.read_text())

        project = (ROOT / 'ios/project.yml').read_text()
        self.assertNotIn('excludes:', project)
        for path in (ROOT / 'ios/App/Sources').glob('*.swift'):
            self.assertNotIn('import SwiftUI', path.read_text(), path.name)

    def test_ios_first_run_restore_uses_shared_compose_form_and_native_file_adapter(self):
        unlock = (ROOT / 'ios/App/Sources/ComposeUnlockHost.swift').read_text()
        self.assertIn('guard active, !store.hasVault, !store.isUnlocked', unlock)
        self.assertIn('store.restoreNewVault(encrypted: data', unlock)
        picker = (ROOT / 'ios/App/Sources/NativeFilePicker.swift').read_text()
        self.assertIn('UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)', picker)
        self.assertIn('UIDocumentPickerViewController(forExporting: [url], asCopy: true)', picker)
        root = (ROOT / 'ios/App/Sources/AppRootViewController.swift').read_text()
        self.assertIn('filePicker.open(types: [.data], from: self)', root)
        self.assertIn('InputPolicy.maxDocumentBytes', root)
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/NewVaultRestoreViewController.kt').read_text()
        self.assertIn('NewVaultRestoreDialog(', bridge)
        self.assertIn('allowChooseBackup = false', bridge)
        store = (ROOT / 'ios/App/Sources/VaultStore.swift').read_text()
        self.assertIn('public func restoreNewVault(encrypted data: Data', store)
        self.assertIn('try vault.create(password: newPassword, entries: restored)', store)

    def test_foundation_forms_and_ios_vault_host_contracts(self):
        build = (MODULE / 'build.gradle').read_text()
        self.assertIn("export rootProject.findProject(':ui-contract')", build)

        for rel in (
            'com/fairuniversal/offlineotp/ui/intro/SharedIntroScreen.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedEntryPinDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EntryCounterDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EntryDetailsDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/TagsDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/HideableDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/MultiChoiceSelectionDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/QrCodeDialog.kt',
        ):
            form = (COMMON / rel).read_text()
            self.assertNotIn('rememberSaveable', form, rel)

        screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text()
        self.assertIn('clickable { onCard(card.index, "reveal") }', screen)
        self.assertIn('onCard(card.index, "pin")', screen)
        self.assertIn('if (card.revealed) {', screen)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('func selectTab(position: Int32)', host)
        self.assertIn('func card(index: Int32, action: String)', host)
        self.assertIn('index: Int32(index)', host)
        self.assertIn('secondsLeft: Int32(seconds)', host)
        self.assertIn('pinRequired: pinRequired)', host)
        self.assertIn('openSurface("pinEdit", keepDraft: true)', host)
        self.assertIn('openSurface("counterEdit", keepDraft: true)', host)
        self.assertIn('private func openSurface(_ key: String, keepDraft: Bool = false)', host)
        self.assertIn('EmailMaterial.details(account: account, revealSecrets: emailSecretsVisible)', host)
        self.assertIn('EmailMaterial.create(', host)
        self.assertIn('store.importBrowserPasswords(', host)
        self.assertNotIn(r'action("edit:\(index)")', host)
