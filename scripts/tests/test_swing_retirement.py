"""Retirement guards, not a claim of native Compose validation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class SwingRetirementTest(unittest.TestCase):
    def test_no_swing_sources_or_legacy_entry_point(self):
        for module in ('desktop', 'desktop-compose'):
            for path in (ROOT / module / 'src').rglob('*.kt'):
                self.assertNotIn('javax.swing', path.read_text(), str(path))
        base = ROOT / 'desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop'
        self.assertFalse((base / 'Main.kt').exists())
        self.assertFalse((base / 'model/EntryTableModel.kt').exists())
        self.assertEqual({'IssuerIcons.kt', 'VectorArtwork.kt'}, {p.name for p in (base / 'ui').glob('*.kt')})

    def test_engine_is_library_and_only_compose_is_application(self):
        engine = (ROOT / 'desktop/build.gradle').read_text()
        host = (ROOT / 'desktop-compose/build.gradle').read_text()
        self.assertIn("apply plugin: 'java-library'", engine)
        self.assertNotIn("apply plugin: 'application'", engine)
        self.assertNotIn('runSwing', engine)
        self.assertNotIn('packageSwingDesktop', engine)
        self.assertIn("dependsOn ':desktop-compose:run'", engine)
        self.assertIn("apply plugin: 'application'", host)
        self.assertIn('desktop.compose.ComposeMainKt', host)
        self.assertIn("implementation project(':desktop')", host)

    def test_composition_retains_native_platform_adapters(self):
        base = ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose'
        self.assertTrue((base / 'AwtScheduler.kt').is_file())
        self.assertTrue((base / 'DesktopSavePicker.kt').is_file())
        host = (base / 'ComposeMain.kt').read_text()
        for feature in ('DesktopHardwareEnrollment', 'DesktopEntryCopy', 'DesktopEmailDetails', 'DesktopUriImport', 'DesktopOpenPgpImport', 'IssuerIcons'):
            self.assertIn(feature, host)
