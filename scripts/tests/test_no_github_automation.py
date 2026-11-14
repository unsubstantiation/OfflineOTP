"""Restrict repository automation to the reviewed per-platform build workflows."""
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".git", ".gradle", ".cache", ".venv", "build", "node_modules", "reports", "__pycache__"}


class NoGithubAutomationTest(unittest.TestCase):
    def test_only_reviewed_workflows_and_no_local_actions(self):
        workflow_dir = ROOT / ".github/workflows"
        workflows = [path for pattern in ("*.yml", "*.yaml") for path in workflow_dir.glob(pattern)]
        actions = []
        for directory, dirs, files in os.walk(ROOT):
            dirs[:] = [name for name in dirs if name not in SKIP_DIRS]
            actions.extend(Path(directory) / name for name in files if name in {"action.yml", "action.yaml"})
        self.assertEqual({"android.yml", "windows.yml", "debian.yml", "macos.yml", "ios.yml", "compose-desktop-validation.yml"},
                         {path.name for path in workflows})
        self.assertEqual([], [str(path.relative_to(ROOT)) for path in actions])

    def test_workflows_are_read_only_pinned_and_upload_only_artifacts(self):
        workflows = ROOT / ".github/workflows"
        for name in ("android.yml", "windows.yml", "debian.yml", "macos.yml", "ios.yml"):
            text = (workflows / name).read_text(encoding="utf-8")
            self.assertIn("workflow_dispatch:", text)
            self.assertIn("push:\n    branches: [master]", text)
            self.assertNotIn("pull_request", text)
            self.assertIn("contents: read", text)
            self.assertNotIn("pull-requests: write", text)
            # A workflow may contain several jobs; every upload must use the single reviewed pin.
            uploads = text.count("actions/upload-artifact@")
            self.assertGreaterEqual(uploads, 1)
            self.assertEqual(
                uploads,
                text.count("actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2"))
            self.assertIn("if-no-files-found: error", text)
            # iOS ships exactly one file, the unsigned .ipa, so it carries no checksum manifest.
            # The archive it uploads is unpacked and verified in the job instead.
            if name != "ios.yml":
                self.assertIn("SHA256SUMS.txt", text)
            self.assertNotIn("contents: write", text)
            self.assertNotIn("gh release", text)
            for line in text.splitlines():
                if "uses:" in line:
                    self.assertRegex(line, r"uses: [\w./-]+@[0-9a-f]{40}(?:\s|$)")

    def test_workflow_outputs_are_platform_specific(self):
        workflows = ROOT / ".github/workflows"
        android = (workflows / "android.yml").read_text(encoding="utf-8")
        windows = (workflows / "windows.yml").read_text(encoding="utf-8")
        debian = (workflows / "debian.yml").read_text(encoding="utf-8")
        self.assertIn(":app:assembleFdroidRelease", android)
        self.assertIn(":app:assemblePlayRelease", android)
        self.assertIn("apksigner", android)
        setup_pin = "android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd # v4.0.4"
        setups = android.count("android-actions/setup-android@")
        self.assertGreaterEqual(setups, 1)
        self.assertEqual(setups, android.count(setup_pin))
        self.assertEqual(setups, android.count("cmdline-tools-version: '12266719'"))
        self.assertIn("runs-on: windows-2022", windows)
        self.assertIn(":desktop-compose:packageDesktop", windows)
        self.assertIn("OfflineOTP.exe", windows)
        self.assertIn("container: debian:bookworm-slim", debian)
        self.assertIn(":desktop-compose:packageDesktop", debian)
        self.assertIn("OfflineOTP/bin/OfflineOTP", debian)

    def test_the_macos_workflow_builds_and_packs_a_native_app_bundle(self):
        macos = (ROOT / ".github/workflows/macos.yml").read_text(encoding="utf-8")
        # Apple silicon runner, so the artifact name has to say arm64 rather than x64.
        self.assertIn("runs-on: macos-15", macos)
        self.assertIn(":desktop-compose:packageDesktop", macos)
        self.assertIn("OfflineOTP-macos-arm64", macos)
        # jpackage emits a .app bundle on macOS; its launcher is what proves the build worked.
        self.assertIn("desktop-compose/build/release/OfflineOTP.app", macos)
        self.assertIn("Contents/MacOS/OfflineOTP", macos)
        # The icon is generated, not committed, and handed to the task through a property.
        self.assertIn("iconutil -c icns", macos)
        self.assertIn('-PmacIconFile="$PWD/build/offlineotp.icns"', macos)
        # ditto keeps the bundle intact; a plain zip flattens its symlinks and exec bits.
        self.assertIn("ditto -c -k --sequesterRsrc --keepParent", macos)
        self.assertNotIn("zip -r", macos)
        self.assertIn("codesign --verify --strict", macos)
        self.assertIn("SHA256SUMS.txt", macos)

    def test_the_macos_icon_is_generated_rather_than_committed(self):
        self.assertFalse((ROOT / "desktop/packaging/offlineotp.icns").exists(),
                         "the .icns is generated from offlineotp.png by the macOS job")
        gradle = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        self.assertIn("project.hasProperty('macIconFile')", gradle)
        # Only macOS may use the .icns; Windows keeps the .ico and Linux the PNG.
        self.assertIn("osName.contains('mac') && macIcon.isFile()", gradle)


if __name__ == "__main__":
    unittest.main()
