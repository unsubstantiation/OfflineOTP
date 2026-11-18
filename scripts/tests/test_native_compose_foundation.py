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
