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
        self.assertNotIn('project(', build)
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
        self.assertIn('if (active && !busy) submit(password)', bridge)
        self.assertIn('fun close() { active = false;', bridge)
        self.assertNotIn('SwiftUI', bridge)
