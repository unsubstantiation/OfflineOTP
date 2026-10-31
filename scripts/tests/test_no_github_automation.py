"""Restrict repository automation to the three reviewed build workflows."""
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
        self.assertEqual({"android.yml", "windows.yml", "debian.yml"}, {path.name for path in workflows})
        self.assertEqual([], [str(path.relative_to(ROOT)) for path in actions])

    def test_workflows_are_read_only_pinned_and_upload_only_artifacts(self):
        workflows = ROOT / ".github/workflows"
        for name in ("android.yml", "windows.yml", "debian.yml"):
            text = (workflows / name).read_text(encoding="utf-8")
            self.assertIn("workflow_dispatch:", text)
            self.assertIn("pull_request:", text)
            self.assertIn("contents: read", text)
            # A workflow may contain several jobs; every upload must use the single reviewed pin.
            uploads = text.count("actions/upload-artifact@")
            self.assertGreaterEqual(uploads, 1)
            self.assertEqual(
                uploads,
                text.count("actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2"))
            self.assertIn("if-no-files-found: error", text)
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
        self.assertIn(":app:assembleFdroidDebug", android)
        self.assertIn("apksigner", android)
        setup_pin = "android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd # v4.0.4"
        setups = android.count("android-actions/setup-android@")
        self.assertGreaterEqual(setups, 1)
        self.assertEqual(setups, android.count(setup_pin))
        self.assertEqual(setups, android.count("cmdline-tools-version: '12266719'"))
        self.assertIn("runs-on: windows-2022", windows)
        self.assertIn(":desktop:packageDesktop", windows)
        self.assertIn("OfflineOTP.exe", windows)
        self.assertIn("container: debian:bookworm-slim", debian)
        self.assertIn(":desktop:packageDesktop", debian)
        self.assertIn("OfflineOTP/bin/OfflineOTP", debian)


if __name__ == "__main__":
    unittest.main()
