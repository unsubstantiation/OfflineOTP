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
        unlock_native = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/UnlockViewController.kt').read_text()
        self.assertIn('AboutNavigation(onAbout = { if (active && !busy) actions.about() })', unlock_native)
        unlock_swift = (ROOT / 'ios/App/Sources/ComposeUnlockHost.swift').read_text()
        self.assertIn('AboutPresentation()', unlock_swift)
        self.assertIn('AboutComposeViewController(', unlock_swift)
        self.assertIn('AboutInfo.url(id:', unlock_swift)

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
        dialog = (ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/UriImportDialog.kt').read_text()
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
            'com/fairuniversal/offlineotp/ui/vault/SharedVaultEntryDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EntryCounterDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EntryDetailsDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/TagsDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/HideableDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/MultiChoiceSelectionDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/QrCodeDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EntryCopyDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EncryptedExportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EncryptedRestoreDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/OpenPgpExportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/OpenPgpImportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EmailImportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/LinScpExportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/LinScpImportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/UriImportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/ArchiveExportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/PasswordExportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/StorageLocationDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/EmailChoiceField.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedPasswordEntryDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedEmailDetailsDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedEmailAccountDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedManualEntryDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedPasswordImportDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedCardLayoutDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedThumbnailPickerDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedVaultComponents.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedVaultScreen.kt',
            'com/fairuniversal/offlineotp/ui/vault/SharedVaultToolsDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/HardwareCopyDialog.kt',
            'com/fairuniversal/offlineotp/ui/vault/HardwareOathDialog.kt',
            'com/fairuniversal/offlineotp/ui/unlock/HardwareEnrollmentDialog.kt',
            'com/fairuniversal/offlineotp/ui/unlock/HardwareUnlockDialog.kt',
        ):
            form = (COMMON / rel).read_text()
            self.assertNotIn('rememberSaveable', form, rel)

        bounds = (ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortableInputBounds.kt').read_text()
        self.assertIn('const val MAX_TEXT_CHARS: Int = 1024', bounds)
        self.assertIn('const val MAX_URI_CHARS: Int = 16384', bounds)
        self.assertIn('const val MAX_TAG_CHARS: Int = 128', bounds)
        self.assertIn('const val MAX_PASSWORD_FIELD_CHARS: Int = 64 * 1024', bounds)
        self.assertIn('const val MAX_EMAIL_CREDENTIAL_CHARS: Int = 16 * 1024', bounds)

        vc = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text()
        for shared_call in (
            'AboutDialog(',
            'AutoLockDialog(',
            'PasswordChangeDialog(',
            'BiometricEnableDialog(',
            'PreferencesImportDialog(',
            'EntryConfirmationDialog(',
            'SharedEntryPinDialog(',
            'SharedVaultEntryDialog(',
            'HideableDialog(',
            'EntryCounterDialog(',
            'TagsDialog(',
            'EntryCopyDialog(',
            'EntryDetailsDialog(',
            'MultiChoiceSelectionDialog(',
            'EncryptedExportDialog(',
            'EncryptedRestoreDialog(',
            'OpenPgpExportDialog(',
            'OpenPgpImportDialog(',
            'EmailImportDialog(',
            'LinScpExportDialog(',
            'LinScpImportDialog(',
            'UriImportDialog(',
            'QrCodeDialog(',
            'ArchiveExportDialog(',
            'StorageLocationDialog(',
            'SharedEmailAccountDialog(',
            'SharedManualEntryDialog(',
            'SharedPasswordEntryDialog(',
            'SharedPasswordImportDialog(',
            'SharedCardLayoutDialog(',
            'SharedEmailDetailsDialog(',
            'SharedThumbnailPickerDialog(',
            'SharedVaultToolsDialog(',
            'PasswordExportDialog(',
        ):
            self.assertIn(shared_call, vc)

        root = (ROOT / 'ios/App/Sources/AppRootViewController.swift').read_text()
        self.assertIn('QrImageReader.payload(in:)', root)
        self.assertIn('importedQrImagePayload(', root)

        screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text()
        self.assertIn('clickable { onCard(card.index, "reveal") }', screen)
        self.assertIn('onCard(card.index, "pin")', screen)
        self.assertIn('if (card.revealed) {', screen)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('func selectTab(position: Int32)', host)
        self.assertIn('func card(index: Int32, action: String)', host)
        self.assertIn('index: Int32(index)', host)
        self.assertIn('secondsLeft: Int32(seconds)', host)
        self.assertIn('pinRequired: pinRequired,', host)
        self.assertIn('openSurface("pinEdit", keepDraft: true)', host)
        self.assertIn('openSurface("counterEdit", keepDraft: true)', host)
        self.assertIn('private func openSurface(_ key: String, keepDraft: Bool = false)', host)
        self.assertIn('EmailMaterial.details(account: account, revealSecrets: emailSecretsVisible)', host)
        self.assertIn('EmailMaterial.create(', host)
        self.assertIn('store.importBrowserPasswords(', host)
        self.assertNotIn(r'action("edit:\(index)")', host)

    def test_kotlin_compose_imports_and_ios_objc_interop_signatures(self):
        """Guard against Compose top-level import errors and Kotlin/Native ObjC label/default-arg mismatches."""
        for module in ('ui-contract', 'ui-foundation', 'ui', 'app', 'desktop', 'desktop-compose'):
            for path in (ROOT / module / 'src').rglob('*.kt'):
                text = path.read_text(encoding='utf-8')
                self.assertNotIn('import androidx.compose.ui.then', text, str(path))

        compile_ui = (ROOT / 'scripts/kotlin-sandbox/compile-ui.sh').read_text(encoding='utf-8')
        self.assertIn('ui-foundation/src/iosMain/kotlin', compile_ui)
        self.assertIn('phase 0b: ios host seam', compile_ui)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        self.assertIn('presentation.updateVault(state: vaultState())', host)
        self.assertIn('presentation.updateSurface(state: surfaceState(surfaceKey))', host)
        self.assertIn('presentation.updateSurface(state: surfaceState(key))', host)
        self.assertIn('presentation.updateSurface(state: nil)', host)
        self.assertNotIn('presentation.updateVault(vaultState())', host)
        self.assertNotIn('presentation.updateSurface(nil)', host)
        self.assertEqual(1, host.count('PortableSurfaceState('))
        self.assertIn(
            'PortableSurfaceState(key: key, title: title, sections: sections, confirmLabel: confirmLabel,\n'
            '                             dismissLabel: dismissLabel, showConfirm: showConfirm, message: message,\n'
            '                             busy: busy)',
            host,
        )

    def test_portable_manual_entry_and_ios_settings_parity(self):
        contract = (ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortablePresentation.kt').read_text(encoding='utf-8')
        for field in ('val algorithm: String', 'val clearHiddenLabel: Boolean', 'val clearPin: Boolean', 'val clearSshPassword: Boolean'):
            self.assertIn(field, contract)

        dialog = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/SharedManualEntryDialog.kt').read_text(encoding='utf-8')
        for sym in ('PORTABLE_HASH_ALGORITHMS', 'clearHiddenLabel', 'clearPin', 'clearSshPassword', '"SHA1"', '"SHA256"', '"SHA512"'):
            self.assertIn(sym, dialog)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        self.assertIn('draft["clearHiddenLabel"]', host)
        self.assertIn('draft["clearPin"]', host)
        self.assertIn('draft["clearSshPassword"]', host)
        self.assertIn('PreferencesExport.inspect(', host)
        self.assertIn('store.hideIssuer', host)
        self.assertIn('store.cardLayout', host)

        prefs = (ROOT / 'ios/Sources/OfflineOTPKit/PreferencesExport.swift').read_text(encoding='utf-8')
        self.assertIn('public static let cardLayoutKey = "ui_card_layout"', prefs)
        self.assertIn('public static let hideIssuerKey = "ui_hide_issuer"', prefs)
        self.assertIn('public static func inspect(', prefs)

    def test_ios_thumbnail_preservation_password_export_dialog_and_card_copy_parity(self):
        draft = (ROOT / 'ios/Sources/OfflineOTPKit/EntryDraft.swift').read_text(encoding='utf-8')
        self.assertIn('thumbnailName: String? = nil', draft)
        self.assertIn('overrideThumbnail: String? = nil', draft)

        store = (ROOT / 'ios/App/Sources/VaultStore.swift').read_text(encoding='utf-8')
        self.assertIn('public func setThumbnail(at index: Int, name: String) -> Bool', store)
        self.assertIn('public func passwordExportPlan() -> PasswordExportPlan', store)
        self.assertIn('entries.filter { isRevealed($0) }', store)
        self.assertNotIn('entries.filter { $0.detailsVisible }', store)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        for sym in ('copySelect:', 'passwordExport', 'thumbnailEdit', 'thunderbirdConfirm', 'canCopy: canCopy'):
            self.assertIn(sym, host)

        screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text(encoding='utf-8')
        self.assertIn('if (card.canCopy)', screen)
        self.assertIn('if (card.canCopyAccount)', screen)
        self.assertIn('if (card.canCopyPassword)', screen)
        self.assertNotIn('onCard(card.index, "delete")', screen)
        self.assertIn('detectDragGesturesAfterLongPress(', screen)

        ios_vc = (ROOT / 'ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text(encoding='utf-8')
        for shared_dialog in ('SharedEmailEditDialog(', 'SharedEmailPasswordDialog(', 'SharedEmailSettingsDialog('):
            self.assertIn(shared_dialog, ios_vc)
        for email_key in ('"emailEdit"', '"emailPasswordEdit"', '"emailSettingsEdit"'):
            self.assertIn(email_key, host)

        self.assertIn('private var emailSecretsVisible = false', host)
        self.assertIn('lastImported = Data()', host)
        self.assertIn('onViewDetails = { onAction("details") }', ios_vc)
        self.assertIn('onExportArchive = if (rowValue("archiveMode") == "true")', ios_vc)
        self.assertIn('command == "archiveExportSelect" || command == "exportArchive"', host)
        self.assertIn('row("qrImageFromTools", "Import QR image…")', host)
        self.assertIn('count = rowValue("count").toIntOrNull(),', ios_vc)
        self.assertIn('dismissOnConfirm = false,', ios_vc)
        self.assertIn('allowKeyFileSelection = true', ios_vc)

        unlock = (ROOT / 'ios/App/Sources/ComposeUnlockHost.swift').read_text(encoding='utf-8')
        self.assertIn('store.objectWillChange', unlock)
        self.assertIn('onDismiss?()', unlock)
        self.assertIn('self?.dismiss(animated: false)', unlock)

        scanner = (ROOT / 'ios/App/Sources/QrCameraScanner.swift').read_text(encoding='utf-8')
        self.assertIn('controls.viewController(actions: self)', scanner)
        self.assertNotIn('UIButton', scanner)
        self.assertIn('self.dismiss(animated: false)', scanner)

        picker = (ROOT / 'ios/App/Sources/NativeFilePicker.swift').read_text(encoding='utf-8')
        self.assertIn('cleanupPendingExport()', picker)

        self.assertIn('secret: current.secretBase32 ?? ""', host)
        self.assertIn('VaultStore.vaultFilename', host)
        self.assertIn('VaultStore.settingsFilename', host)
        self.assertNotIn('"vault.enc"', host)

        main_kt = (ROOT / 'app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt').read_text(encoding='utf-8')
        self.assertIn('.filter { current.isEntryRevealed(it) }', main_kt)

        ios_clip = (ROOT / 'ios/App/Sources/Clipboard.swift').read_text(encoding='utf-8')
        self.assertIn('.localOnly: true', ios_clip)

        prefs_swift = (ROOT / 'ios/Sources/OfflineOTPKit/PreferencesExport.swift').read_text(encoding='utf-8')
        self.assertIn('number.objCType', prefs_swift)
        self.assertIn('flag.objCType', prefs_swift)

    def test_shared_vault_bars_ios_backups_tools_dialog_and_cross_platform_uri_import(self):
        shared_components = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/SharedVaultComponents.kt').read_text(encoding='utf-8')
        self.assertIn('fun SharedVaultCategoryBar(', shared_components)
        self.assertIn('fun SharedVaultTagBar(', shared_components)

        ui_components = (ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultComponents.kt').read_text(encoding='utf-8')
        self.assertIn('SharedVaultCategoryBar(', ui_components)
        self.assertIn('SharedVaultTagBar(', ui_components)
        self.assertIn('onCycleTagRule', ui_components)

        portable_screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text(encoding='utf-8')
        self.assertIn('SharedVaultCategoryBar(', portable_screen)
        self.assertIn('SharedVaultTagBar(', portable_screen)

        ios_vc = (ROOT / 'ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text(encoding='utf-8')
        self.assertIn('"settings", "backups" -> SharedVaultToolsDialog(', ios_vc)
        self.assertIn('onSwitchToUriImport = if (dialog.title != "Edit entry")', ios_vc)
        self.assertIn("private iOS sandbox container", ios_vc)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        backup_section = host[host.index('private func backupSurface()'):host.index('private func openPgpSection()')]
        self.assertNotIn('row("backupPassword"', backup_section)
        self.assertNotIn('passwordPromptSurface', host)
        self.assertIn('case "switchToUriImport":', host)

        shared_manual = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/SharedManualEntryDialog.kt').read_text(encoding='utf-8')
        self.assertIn('onSwitchToUriImport: (() -> Unit)? = null', shared_manual)
        self.assertIn('Import otpauth:// link', shared_manual)

        ui_manual = (ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/ManualEntryDialog.kt').read_text(encoding='utf-8')
        self.assertIn('onSwitchToUriImport: (() -> Unit)? = null', ui_manual)

        android_vault = (ROOT / 'ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultComposeView.kt').read_text(encoding='utf-8')
        self.assertIn('var onOpenUriImport: (() -> Unit)? = null', android_vault)
        self.assertIn('onSwitchToUriImport = if (!request.editing) onOpenUriImport?.let', android_vault)

        desktop_main = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text(encoding='utf-8')
        self.assertIn('onSwitchToUriImport =', desktop_main)

    def test_portable_vault_and_busy_progress_share_geometry_countdown_counter_and_email_details(self):
        busy = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/BusyProgressDialog.kt').read_text(encoding='utf-8')
        self.assertIn('fun BusyProgressDialog(', busy)
        self.assertNotIn('rememberSaveable', busy)
        self.assertNotIn('Clipboard', busy)

        portable = (ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortablePresentation.kt').read_text(encoding='utf-8')
        for prop in ('val cardVerticalPaddingDp: Int', 'val codeBelowIdentity: Boolean',
                     'val truncatedLabelMaxLines: Int', 'val thumbnailSizeDp: Float',
                     'enum class PortableLabelDisplay', 'val labelDisplayName: String = "TRUNCATE"',
                     'val showThumbnails: Boolean = true',
                     'val periodSeconds: Int = 30', 'val showCountdown: Boolean = true',
                     'val counterLabel: String = ""', 'val isEmail: Boolean = false'):
            self.assertIn(prop, portable)

        appearance = (ROOT / 'ui-model/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultUiState.kt').read_text(encoding='utf-8')
        self.assertIn('portableLabelDisplay.labelMaxLines(portableLayout)', appearance)
        self.assertIn('portableLabelDisplay.scrollLabels', appearance)
        self.assertIn('portableLayout.codeBelowIdentity', appearance)
        self.assertIn('portableLayout.cardVerticalPaddingDp', appearance)

        screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text(encoding='utf-8')
        self.assertIn('SharedVaultFilterHeader(', screen)
        self.assertIn('showThumbnail = showThumbnails', screen)
        self.assertIn('scrollLabels = labelDisplay.scrollLabels', screen)
        self.assertIn('cardLayout.effectiveThumbnailSizeDp(customThumbnailSizeDp)', screen)
        self.assertIn('val displayMark = card.visibleMark(hideIssuer)', screen)
        self.assertIn('focusOnStart = state.focusSearchOnStart', screen)
        self.assertIn('showCountdown = card.hasCode && card.showCountdown && card.secondsLeft > 0', screen)
        self.assertIn('showSecondsText = true', screen)
        self.assertIn('counterText = if (card.revealed) card.counterLabel.ifEmpty { null } else null', screen)
        self.assertIn('onCard(card.index, "emailDetails")', screen)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        self.assertIn('showCountdown: showCountdown', host)
        self.assertIn('counterLabel: counterLabel', host)
        self.assertIn('isEmail: entry.kind == .email', host)
        self.assertIn('case "emailDetails":', host)
        self.assertIn('labelDisplayName: store.labelDisplay', host)
        self.assertIn('showThumbnails: store.showThumbnails', host)

        ios_prefs = (ROOT / 'ios/Sources/OfflineOTPKit/PreferencesExport.swift').read_text(encoding='utf-8')
        self.assertIn('public static let labelDisplayKey = "ui_label_display"', ios_prefs)
        self.assertIn('public static let showThumbnailsKey = "ui_show_thumbnails"', ios_prefs)
        self.assertIn('public static let labelSizeSpKey = "ui_label_size_sp"', ios_prefs)
        self.assertIn('public static let highlightTokenKey = "ui_highlight_token"', ios_prefs)
        self.assertIn('public static let tapSingleKey = "ui_tap_single"', ios_prefs)
        self.assertIn('public static let tapDoubleKey = "ui_tap_double"', ios_prefs)

        self.assertIn('periodSeconds: Int32(entry.kind == .motp ? 10 : max(entry.period, 1))', host)
        self.assertIn('labelSizeSp: Int32(store.labelSizeSp)', host)
        self.assertIn('highlightExpiringTokens: store.highlightToken', host)
        self.assertIn('tapSingleName: store.tapSingle', host)
        self.assertIn('tapDoubleName: store.tapDouble', host)
        self.assertIn('row("cameraSupported", "Camera", value: "true", kind: "info")', host)

        ios_vc = (ROOT / 'ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text(encoding='utf-8')
        self.assertIn('onScanCamera = if (cameraSupported) { { onAction("qrCamera") } } else null', ios_vc)
        self.assertIn('onSaveCompleteDisplay =', ios_vc)
        self.assertIn('onSaveSelection =', ios_vc)
        self.assertIn('"usedTokensNotice" -> HideableDialog(', ios_vc)
        self.assertIn('onViewDetails = if (dialog.title == "Edit entry" && rowValue("type") != "NOTE")', ios_vc)
        self.assertIn('customPeriodLabel = card.customPeriodLabel.ifEmpty { null }', screen)
        self.assertIn('labelSizeSp = state.labelSizeSp.takeIf { it > 0 }', screen)
        self.assertIn('SharedVaultGlobalProgressBar(', screen)
        self.assertIn('public static let hideGlobalTimeoutKey = "ui_hide_global_timeout"', ios_prefs)
        self.assertIn('public static let tapToRevealTimeoutKey = "ui_tap_to_reveal_timeout"', ios_prefs)
        self.assertIn('hideGlobalTimeout: store.hideGlobalTimeout', host)
        self.assertIn('store.expireRevealedEntries(now: now)', host)
        self.assertIn('openSurface("usedTokensNotice")', host)

    def test_shared_vector_brand_artwork_and_portable_preferences_parity(self):
        project_yml = (ROOT / 'ios/project.yml').read_text(encoding='utf-8')
        self.assertIn('path: ../app/src/main/res/drawable', project_yml)
        self.assertIn('"thumb_*.xml"', project_yml)

        vector_doc = (ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortableVectorDocument.kt').read_text(encoding='utf-8')
        self.assertIn('class PortableVectorDocument(', vector_doc)
        self.assertIn('fun parse(xml: String): PortableVectorDocument?', vector_doc)

        shared_vec = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/SharedVectorArtwork.kt').read_text(encoding='utf-8')
        self.assertIn('class BundledVectorVaultArtwork', shared_vec)
        self.assertIn('fun SharedVectorBadge(', shared_vec)

        ios_vc = (ROOT / 'ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text(encoding='utf-8')
        self.assertIn('fun registerArtworkXmlLoader(', ios_vc)
        self.assertIn('BundledVectorVaultArtwork(loader)', ios_vc)
        self.assertIn('artwork = artwork', ios_vc)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        self.assertIn('presentation.registerArtworkXmlLoader', host)
        self.assertIn('customThumbnailSizeDp: Int32(store.thumbnailSizeDp)', host)
        self.assertIn('focusSearchOnStart: store.focusSearchOnStart', host)
        self.assertIn('relockOnBackground', host)
        self.assertIn('case "backupTimestamp":', host)

        app_root = (ROOT / 'ios/App/Sources/AppRootViewController.swift').read_text(encoding='utf-8')
        self.assertIn('UIScene.didEnterBackgroundNotification', app_root)
        self.assertIn('store.relockOnBackground', app_root)

        compose_main = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text(encoding='utf-8')
        self.assertIn('onOpenAbout = { showAbout = true }', compose_main)
        self.assertIn('windowIconified', compose_main)
        self.assertIn('toggleBackupTimestamp', compose_main)

        core_prefs = (ROOT / 'core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/PreferencesExport.kt').read_text(encoding='utf-8')
        ios_prefs = (ROOT / 'ios/Sources/OfflineOTPKit/PreferencesExport.swift').read_text(encoding='utf-8')
        for key in ('"ui_thumbnail_size_dp"', '"ui_focus_search_on_start"',
                    '"ui_relock_background"', '"ui_backup_append_date_time"'):
            self.assertIn(key, core_prefs)
            self.assertIn(key, ios_prefs)

    def test_unified_vault_tools_bar_hideable_notice_and_tabbed_about_libraries_parity(self):
        portable_screen = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt').read_text(encoding='utf-8')
        self.assertIn('SharedVaultToolsBar(', portable_screen)
        self.assertIn('state.toolGroups.isNotEmpty()', portable_screen)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        self.assertIn('toolGroups: unifiedToolGroups()', host)
        self.assertIn('_ = surfaceAction(action)', host)
        self.assertIn('if draft["dontShowAgain"] == "true" {\n                store.setUsedTokensDialogShown(true)\n            }', host)

        desktop_main = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text(encoding='utf-8')
        self.assertIn('HideableDialog(', desktop_main)
        self.assertIn('if (usedTokensHideForever && lifetime.checkActive())', desktop_main)

        about_dialog = (COMMON / 'com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt').read_text(encoding='utf-8')
        self.assertIn('SharedVaultTab(label = "About"', about_dialog)
        self.assertIn('SharedVaultTab(label = "Libraries"', about_dialog)
        self.assertIn('PortableLibraryCatalog.bundled', about_dialog)

    def test_ios_and_portable_foundation_support_website_password_creation_and_mode_switching(self):
        bridge = (MODULE / 'src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt').read_text(encoding='utf-8')
        self.assertIn('"passwordCreate" -> SharedPasswordEntryDialog(', bridge)
        self.assertIn('onAction("switchToPasswordEditor")', bridge)
        self.assertIn('onAction("switchToManualEditor")', bridge)

        host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text(encoding='utf-8')
        self.assertIn('else if store.category == .passwords {\n                openPasswordCreator()\n            }', host)
        self.assertIn('private func savePasswordCreate() -> String?', host)
        self.assertIn('store.addPasswordEntry(', host)

        csv = (ROOT / 'ios/Sources/OfflineOTPKit/PasswordCsvExport.swift').read_text(encoding='utf-8')
        self.assertIn('public static func create(', csv)
        self.assertIn('case missingIdentity', csv)






