"""Keep the current project name and package unified under com.fairuniversal.offlineotp."""
import json
import re
import subprocess
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
VALUES = ROOT / "app/src/main/res/values"
PACKAGE = "com.fairuniversal.offlineotp"
LEGACY_DOT = "org.shadowice." + "flocke.andotp"
LEGACY_SLASH = "org/shadowice/" + "flocke/andotp"


def resource(filename, name):
    root = ET.parse(VALUES / filename).getroot()
    element = root.find(f"string[@name='{name}']")
    if element is None:
        raise AssertionError(f"missing string {name} in {filename}")
    return "".join(element.itertext())


class ProjectBrandingTest(unittest.TestCase):
    def test_gradle_project_and_app_are_named_offlineotp(self):
        settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
        self.assertRegex(settings, r'(?m)^\s*rootProject\.name\s*=\s*[\"\']OfflineOTP[\"\']\s*$')
        self.assertEqual("OfflineOTP", resource("strings_main.xml", "app_name"))

    def test_android_app_identity_uses_fairuniversal_offlineotp(self):
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertRegex(gradle, rf'(?m)^\s*namespace\s+(?:=\s*)?[\"\']{re.escape(PACKAGE)}[\"\']\s*$')
        self.assertRegex(gradle, rf'(?m)^\s*applicationId\s+(?:=\s*)?[\"\']{re.escape(PACKAGE)}[\"\']\s*$')
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        policy = json.loads((ROOT / "scripts/manifest-policy.json").read_text(encoding="utf-8"))
        self.assertIsNone(manifest.get("package"))
        self.assertEqual(PACKAGE, policy["package"])

    def test_no_legacy_package_or_folder_paths_remain(self):
        listed = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
            cwd=ROOT,
            text=True,
        ).splitlines()
        present = sorted({rel for rel in listed if (ROOT / rel).is_file()})
        bad_paths = [p for p in present if LEGACY_SLASH in p or LEGACY_DOT in p]
        self.assertEqual([], bad_paths, "legacy package directories remain in repository")

        bad_files = []
        dot_bytes = LEGACY_DOT.encode("utf-8")
        slash_bytes = LEGACY_SLASH.encode("utf-8")
        for rel in present:
            data = (ROOT / rel).read_bytes()
            if dot_bytes in data or slash_bytes in data:
                bad_files.append(rel)
        self.assertEqual([], bad_files, "legacy package references remain in repository files")

    def test_upstream_attribution_is_not_rebranded(self):
        self.assertIn("andOTP", resource("strings_about.xml", "about_label_main_developer"))
        self.assertIn("andOTP", resource("strings_about.xml", "about_label_developer"))


if __name__ == "__main__":
    unittest.main()
