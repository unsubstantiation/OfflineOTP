"""Regression checks for the limited, packaged-debug-manifest CI gate."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import check_debug_manifest  # noqa: E402

POLICY = json.loads((ROOT / "scripts/manifest-policy.json").read_text(encoding="utf-8"))
PACKAGE = POLICY["package"] + ".dev"


def manifest(*, permissions="", package=PACKAGE, debuggable="true", backup="false", sdk=None,
             extra_application=""):
    if sdk is None:
        sdk = (f'<uses-sdk android:minSdkVersion="{POLICY["min_sdk"]}" '
               f'android:targetSdkVersion="{POLICY["target_sdk"]}" />')
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
{sdk}
{permissions}
<application android:debuggable="{debuggable}" android:allowBackup="{backup}" {extra_application} />
</manifest>'''


def check(xml):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "AndroidManifest.xml"
        path.write_text(xml, encoding="utf-8")
        return check_debug_manifest.check_manifest(path, POLICY)


class DebugManifestTest(unittest.TestCase):
    def test_expected_debug_manifest_and_variant_permissions_pass(self):
        permissions = f'''
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="{PACKAGE}.permission.CONTROL_VAULT" />
<uses-permission android:name="{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />'''
        self.assertEqual([], check(manifest(permissions=permissions)))

    def test_internet_and_other_unreviewed_permissions_fail_even_in_sdk_specific_tags(self):
        for tag, permission in (("uses-permission", "android.permission.INTERNET"),
                                ("uses-permission-sdk-23", "android.permission.INTERNET"),
                                ("uses-permission", "android.permission.READ_SMS"),
                                ("uses-permission", "")):
            with self.subTest(tag=tag, permission=permission):
                xml = manifest(permissions=f'<{tag} android:name="{permission}" />')
                self.assertIn(f"Unreviewed permission: {permission}", check(xml))

    def test_internet_remains_forbidden_if_the_allowlist_is_accidentally_edited(self):
        policy = {**POLICY, "allowed_permissions": POLICY["allowed_permissions"] + ["android.permission.INTERNET"]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(manifest(permissions='<uses-permission android:name="android.permission.INTERNET" />'),
                            encoding="utf-8")
            self.assertIn("Unreviewed permission: android.permission.INTERNET",
                          check_debug_manifest.check_manifest(path, policy))

    def test_release_permissions_are_not_mistaken_for_debug_permissions(self):
        permission = POLICY["package"] + ".permission.CONTROL_VAULT"
        xml = manifest(permissions=f'<uses-permission android:name="{permission}" />')
        self.assertIn(f"Unreviewed permission: {permission}", check(xml))

    def test_wrong_id_non_debug_and_backup_fail(self):
        self.assertTrue(any("Unexpected debug application ID" in error
                            for error in check(manifest(package=POLICY["package"]))))
        self.assertIn("Expected a debuggable APK for the debug artifact",
                      check(manifest(debuggable="false")))
        self.assertIn("Platform backup/transfer is not disabled", check(manifest(backup="true")))
        self.assertIn("Unexpected platform backup agent",
                      check(manifest(extra_application='android:backupAgent=".Agent"')))
        self.assertIn("Unexpected testOnly flag",
                      check(manifest(extra_application='android:testOnly="true"')))

    def test_requires_packaged_sdk_and_expected_versions(self):
        self.assertIn("Packaged manifest has no uses-sdk", check(manifest(sdk="")))
        self.assertTrue(any("Unexpected targetSdkVersion" in error for error in check(manifest(
            sdk=f'<uses-sdk android:minSdkVersion="{POLICY["min_sdk"]}" android:targetSdkVersion="1" />'))))

    def test_rejects_non_manifest_and_multiple_applications(self):
        self.assertIn("Not an Android manifest", check("<not-a-manifest />"))
        xml = manifest().replace("</manifest>", "<application /></manifest>")
        self.assertIn("Expected exactly one application", check(xml))

    def test_cli_fails_closed_on_malformed_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text("<manifest>", encoding="utf-8")
            result = subprocess.run([sys.executable, str(ROOT / "scripts/check_debug_manifest.py"), str(path)],
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Cannot inspect manifest", result.stderr)


@unittest.skipUnless(shutil.which("bash") and shutil.which("sha256sum"), "requires bash and sha256sum")
class ArtifactPreparationTest(unittest.TestCase):
    """Exercise the real workflow shell with fake SDK tools, not a claimed APK scan."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        (self.work / "scripts").mkdir()
        shutil.copy2(ROOT / "scripts/check_debug_manifest.py", self.work / "scripts")
        shutil.copy2(ROOT / "scripts/manifest-policy.json", self.work / "scripts")
        apk = self.work / "app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"fake APK for shell wiring tests")
        tools = self.work / "bin"
        tools.mkdir()
        self.tool(tools / "git", "printf '%s\\n' 0123456789abcdef0123456789abcdef01234567\n")
        self.tool(tools / "apkanalyzer", 'cat "$FAKE_MANIFEST"\n')
        self.tool(self.work / "sdk/build-tools/36.0.0/apksigner",
                  'printf "fake signing certificate\\n"\nexit "$FAKE_SIGNER_EXIT"\n')
        self.env = {**os.environ, "PATH": f"{tools}{os.pathsep}{os.environ['PATH']}",
                    "ANDROID_HOME": str(self.work / "sdk"),
                    "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "owner/repo",
                    "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "FAKE_SIGNER_EXIT": "0",
                    "FAKE_MANIFEST": str(self.work / "fake-manifest.xml")}

    @staticmethod
    def tool(path, body):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!/bin/sh\nset -eu\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def prepare(self, xml):
        Path(self.env["FAKE_MANIFEST"]).write_text(xml, encoding="utf-8")
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        block = workflow.split("      - name: Inspect APK and prepare provenance\n", 1)[1]
        block = block.split("        run: |\n", 1)[1].split("\n      - uses:", 1)[0]
        return subprocess.run(["bash", "-c", textwrap.dedent(block)], cwd=self.work, env=self.env,
                              capture_output=True, text=True, check=False)

    def test_success_writes_consistent_checksums_and_provenance(self):
        result = self.prepare(manifest())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        artifacts = self.work / "artifacts"
        checksums = (artifacts / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
        files = {}
        for line in checksums:
            digest, name = line.split("  ", 1)
            files[name] = digest
        self.assertEqual({"OfflineOTP-fdroid-debug.apk", "AndroidManifest.xml", "signing-certificate.txt",
                          "build-provenance.txt"}, set(files))
        for name, digest in files.items():
            self.assertEqual(digest, hashlib.sha256((artifacts / name).read_bytes()).hexdigest())
        provenance = (artifacts / "build-provenance.txt").read_text(encoding="utf-8")
        self.assertIn("0123456789abcdef0123456789abcdef01234567", provenance)
        self.assertIn("https://github.com/owner/repo/actions/runs/123/attempts/1", provenance)
        self.assertIn("not a malware scan", provenance)

    def test_unreviewed_permission_fails_before_checksums(self):
        result = self.prepare(manifest(permissions='<uses-permission android:name="android.permission.INTERNET" />'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unreviewed permission: android.permission.INTERNET", result.stderr)
        self.assertFalse((self.work / "artifacts/SHA256SUMS.txt").exists())

    def test_invalid_signature_fails_before_manifest_and_checksums(self):
        self.env["FAKE_SIGNER_EXIT"] = "1"
        result = self.prepare(manifest())
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.work / "artifacts/AndroidManifest.xml").exists())
        self.assertFalse((self.work / "artifacts/SHA256SUMS.txt").exists())


if __name__ == "__main__":
    unittest.main()
