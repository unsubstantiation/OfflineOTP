"""The source manifest must match the release manifest security policy.

``scripts/check_manifest.py`` inspects the *packaged* manifest (placeholders resolved, merged
library components present). This guard runs the same policy over the *source* manifest with
``${applicationId}`` resolved, so a component that is newly exported, a permission change or a
weakened protection level fails before anything is built.
"""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import check_manifest  # noqa: E402

MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
POLICY = json.loads((ROOT / "scripts/manifest-policy.json").read_text(encoding="utf-8"))
PACKAGE = POLICY["package"]


def check_source(text):
    """Run the packaged-manifest policy on source XML with the application ID resolved."""
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "AndroidManifest.xml"
        path.write_text(text.replace("${applicationId}", PACKAGE), encoding="utf-8")
        return check_manifest.check_manifest(path, POLICY, require_sdk=False)


class ManifestSourcePolicyTest(unittest.TestCase):
    def test_source_manifest_matches_release_policy(self):
        errors = check_source(MANIFEST.read_text(encoding="utf-8"))
        # Library components are only added during Gradle manifest merging.
        merged_missing = {
            "service:androidx.work.impl.background.systemjob.SystemJobService",
            "receiver:androidx.work.impl.diagnostics.DiagnosticsReceiver",
            "receiver:androidx.profileinstaller.ProfileInstallReceiver",
        }
        errors = [error for error in errors if not any(key in error for key in merged_missing)]
        self.assertEqual([], errors, "the source manifest drifted from the release security policy")

    def test_policy_rejects_platform_backup_and_a_registered_agent(self):
        text = MANIFEST.read_text(encoding="utf-8")
        enabled = text.replace('android:allowBackup="false"', 'android:allowBackup="true"')
        self.assertIn("Platform backup/transfer is not disabled", check_source(enabled))
        agent = text.replace('android:allowBackup="false"',
                             'android:allowBackup="false" android:backupAgent=".Utilities.BackupAgent"')
        self.assertIn("Unexpected platform backup agent", check_source(agent))

    def test_source_variants_cannot_reenable_backup_or_register_an_agent(self):
        """Variant manifests can override the main application during manifest merging."""
        source_root = ROOT / "app/src"
        android = "{http://schemas.android.com/apk/res/android}"
        manifests = sorted(source_root.glob("*/AndroidManifest.xml"))
        self.assertIn(MANIFEST, manifests)
        for manifest in manifests:
            source_set = manifest.parent.name.lower()
            if source_set.startswith(("test", "androidtest")):
                continue
            application = ET.parse(manifest).getroot().find("application")
            if application is None:
                continue
            with self.subTest(manifest=manifest):
                self.assertIsNone(application.get(android + "backupAgent"))
                allow_backup = application.get(android + "allowBackup")
                self.assertIn(allow_backup, (None, "false"))
                if manifest == MANIFEST:
                    self.assertEqual("false", allow_backup)
                for attribute, expected in (
                        ("fullBackupContent", "@xml/backup_rules"),
                        ("dataExtractionRules", "@xml/data_extraction_rules")):
                    value = application.get(android + attribute)
                    if manifest == MANIFEST or value is not None:
                        self.assertEqual(expected, value)

    def test_backup_exclusion_resources_cover_storage_domains(self):
        """Every source-set override must continue to exclude all backup domains."""
        source_root = ROOT / "app/src"
        domains = {"root", "file", "database", "sharedpref", "external", "device_root",
                   "device_file", "device_database", "device_sharedpref"}
        for filename in ("backup_rules.xml", "data_extraction_rules.xml"):
            resources = [path for path in source_root.rglob(filename)
                         if not path.relative_to(source_root).parts[0].lower().startswith(
                             ("test", "androidtest"))]
            self.assertIn(source_root / "main/res/xml" / filename, resources)
            for resource in resources:
                with self.subTest(resource=resource):
                    root = ET.parse(resource).getroot()
                    if filename == "backup_rules.xml":
                        self.assertEqual("full-backup-content", root.tag)
                        sections = [root]
                    else:
                        self.assertEqual("data-extraction-rules", root.tag)
                        self.assertCountEqual(["cloud-backup", "device-transfer"],
                                              [section.tag for section in root])
                        sections = list(root)
                    for section in sections:
                        self.assertEqual([], section.findall(".//include"))
                        excluded = {item.get("domain") for item in section.findall("exclude")
                                    if item.get("path") == "."}
                        self.assertTrue(domains <= excluded,
                                        f"{resource} does not exclude {sorted(domains - excluded)}")

    def test_google_export_uri_has_its_own_narrow_view_filter(self):
        manifest = ET.fromstring(MANIFEST.read_text(encoding="utf-8"))
        android = "{http://schemas.android.com/apk/res/android}"
        main = next(activity for activity in manifest.iter("activity")
                    if activity.get(android + "name") == ".Activities.MainActivity")
        migration = [intent for intent in main.findall("intent-filter")
                     if any(data.get(android + "scheme") == "otpauth-migration"
                            for data in intent.findall("data"))]
        self.assertEqual(1, len(migration))
        filters = migration[0].findall("data")
        self.assertEqual(1, len(filters))
        self.assertEqual("offline", filters[0].get(android + "host"))
        self.assertIn("android.intent.action.VIEW", {
            action.get(android + "name") for action in migration[0].findall("action")})

    def test_every_exported_otpauth_kind_can_be_opened(self):
        """The app builds otpauth:// links for all four kinds; ACTION_VIEW must accept each one.

        `Entry.toUriString()` (the QR-code export) maps every OTPType onto its lowercased name, so a
        filter that only lists totp/hotp leaves the app unable to open the steam/motp QR codes it
        generates itself.
        """
        entry = (ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Database/Entry.kt").read_text(
            encoding="utf-8")
        enum_body = re.search(r"enum class OTPType \{([^}]*)\}", entry).group(1)
        kinds = {name.strip().lower() for name in enum_body.split(",") if name.strip()}
        self.assertGreaterEqual(len(kinds), 4, "the OTP types could not be read from Entry.kt")

        manifest = ET.fromstring(MANIFEST.read_text(encoding="utf-8").replace(
            "${applicationId}", PACKAGE))
        android = "{http://schemas.android.com/apk/res/android}"
        main = next(
            activity for activity in manifest.iter("activity")
            if activity.get(android + "name") == ".Activities.MainActivity")
        hosts = {
            data.get(android + "host") for data in main.iter("data")
            if data.get(android + "scheme") == "otpauth"
        }
        self.assertEqual(set(), kinds - hosts,
                         "otpauth links the app creates are not handled by its VIEW filter")


if __name__ == "__main__":
    unittest.main()
