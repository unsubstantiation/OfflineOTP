"""Keep repository-local GitHub Actions and workflows absent."""
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".git", ".gradle", ".cache", ".venv", "build", "node_modules", "reports", "__pycache__"}


class NoGithubAutomationTest(unittest.TestCase):
    def test_no_workflows_or_local_action_manifests(self):
        workflow_dir = ROOT / ".github/workflows"
        workflows = [path for pattern in ("*.yml", "*.yaml") for path in workflow_dir.glob(pattern)]
        actions = []
        for directory, dirs, files in os.walk(ROOT):
            dirs[:] = [name for name in dirs if name not in SKIP_DIRS]
            actions.extend(Path(directory) / name for name in files if name in {"action.yml", "action.yaml"})
        self.assertEqual([], [str(path.relative_to(ROOT)) for path in workflows + actions])


if __name__ == "__main__":
    unittest.main()
