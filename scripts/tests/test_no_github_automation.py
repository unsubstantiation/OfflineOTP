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
            self.assertIn("actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02", text)
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
        self.assertIn("runs-on: windows-2022", windows)
        self.assertIn(":desktop:packageDesktop", windows)
        self.assertIn("OfflineOTP.exe", windows)
        self.assertIn("container: debian:bookworm-slim", debian)
        self.assertIn(":desktop:packageDesktop", debian)
        self.assertIn("OfflineOTP/bin/OfflineOTP", debian)


if __name__ == "__main__":
    unittest.main()
