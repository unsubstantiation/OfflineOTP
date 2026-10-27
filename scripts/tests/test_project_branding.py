"""Keep the current project name distinct from compatibility and upstream references."""
import json
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
VALUES = ROOT / "app/src/main/res/values"
LEGACY_PACKAGE = "org.shadowice.flocke.andotp"


def resource(filename, name):
    root = ET.parse(VALUES / filename).getroot()
    element = root.find(f"string[@name='{name}']")
    if element is None:
        raise AssertionError(f"missing string {name} in {filename}")
    return "".join(element.itertext())


class ProjectBrandingTest(unittest.TestCase):
    def test_gradle_project_and_app_are_named_offlineotp(self):
        settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
        self.assertRegex(settings, r'(?m)^\s*rootProject\.name\s*=\s*["\']OfflineOTP["\']\s*$')
        self.assertEqual("OfflineOTP", resource("strings_main.xml", "app_name"))

    def test_android_app_identity_remains_compatible(self):
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        for name in ("namespace", "applicationId"):
            self.assertRegex(gradle, rf'(?m)^\s*{name}\s+["\']{re.escape(LEGACY_PACKAGE)}["\']\s*$')
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        policy = json.loads((ROOT / "scripts/manifest-policy.json").read_text(encoding="utf-8"))
        self.assertEqual(LEGACY_PACKAGE, manifest.get("package"))
        self.assertEqual(LEGACY_PACKAGE, policy["package"])

    def test_upstream_attribution_and_legacy_backup_are_not_rebranded(self):
        self.assertIn("andOTP", resource("strings_about.xml", "about_label_main_developer"))
        self.assertIn("andOTP", resource("strings_about.xml", "about_label_developer"))
        self.assertIn("andOTP", resource("strings_backup.xml", "backup_check_restore_old"))


if __name__ == "__main__":
    unittest.main()
