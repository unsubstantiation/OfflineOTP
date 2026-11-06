"""Thunderbird email credential import/export must exist on Android and desktop.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop"
APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
CORE = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"


class ThunderbirdSupportTest(unittest.TestCase):
    def test_core_has_thunderbird_csv_and_import(self):
        for path in ("Utilities/ThunderbirdCsv.kt", "Utilities/ThunderbirdImport.kt"):
            self.assertTrue((CORE / path).exists(), f"missing {path}")
        csv = (CORE / "Utilities/ThunderbirdCsv.kt").read_text(encoding="utf-8")
        self.assertIn("object ThunderbirdCsv", csv)
        self.assertIn("MAX_FILE_BYTES", csv)
        self.assertIn("hostname", csv.lower())
        self.assertIn("username", csv.lower())
        # Must accept both url and hostname as origin
        self.assertIn('"hostname"', csv)
        self.assertIn('"url"', csv)
        imp = (CORE / "Utilities/ThunderbirdImport.kt").read_text(encoding="utf-8")
        self.assertIn("object ThunderbirdImport", imp)
        self.assertIn("fun prepare(", imp)
        self.assertIn("fun exportCsv(", imp)
        # Thunderbird and FairEmail share EMAIL vault storage (unified)
        self.assertTrue("Entry.email" in imp or "isEmail" in imp, "ThunderbirdImport must handle EMAIL entries")
        # Sandbox must compile the new core sources
        run_sh = (ROOT / "scripts/kotlin-sandbox/run.sh").read_text(encoding="utf-8")
        self.assertIn("ThunderbirdCsv.kt", run_sh)
        self.assertIn("ThunderbirdImport.kt", run_sh)

    def test_core_thunderbird_has_tests(self):
        test = (ROOT / "core/src/test/kotlin/org/shadowice/flocke/andotp/Utilities/ThunderbirdCsvTest.kt").read_text(encoding="utf-8")
        self.assertIn("ThunderbirdCsv", test)
        self.assertIn("ThunderbirdImport", test)
        self.assertIn("hostname", test.lower())

    def test_android_has_thunderbird_import_export(self):
        main = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        for sym in ("INTENT_MAIN_THUNDERBIRD_IMPORT", "INTENT_MAIN_THUNDERBIRD_EXPORT",
                    "chooseThunderbirdImport", "chooseThunderbirdExport",
                    "ThunderbirdImport", "thunderbird_import", "thunderbird_export"):
            self.assertIn(sym, main)
        fab = (ROOT / "app/src/main/res/menu/menu_fab.xml").read_text(encoding="utf-8")
        self.assertIn("fabImportThunderbird", fab)
        self.assertIn("fabExportThunderbird", fab)
        strings = (ROOT / "app/src/main/res/values/strings_main.xml").read_text(encoding="utf-8")
        for key in ("thunderbird_import", "thunderbird_export", "thunderbird_import_success",
                    "thunderbird_export_success", "thunderbird_export_warning"):
            self.assertIn(key, strings)

    def test_desktop_has_thunderbird_import_export(self):
        window = (DESKTOP / "ui/MainWindow.kt").read_text(encoding="utf-8")
        for label in ('"Import Thunderbird credentials…"', '"Export Thunderbird credentials…"'):
            self.assertIn(label, window)
        self.assertIn("ThunderbirdDialogs.importBackup", window)
        self.assertIn("ThunderbirdDialogs.exportBackup", window)
        dialogs = (DESKTOP / "ui/ThunderbirdDialogs.kt").read_text(encoding="utf-8")
        self.assertIn("object ThunderbirdDialogs", dialogs)
        self.assertIn("ThunderbirdImport", dialogs)
        self.assertIn("BoundedInputReader.read", dialogs)
        # Add menu also has the entries
        self.assertIn("Import Thunderbird credentials…", window)
        self.assertIn("Export Thunderbird credentials…", window)

    def test_documentation_mentions_thunderbird(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("Thunderbird", readme)
        self.assertIn("Import Thunderbird", readme)


if __name__ == "__main__":
    unittest.main()
