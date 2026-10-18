"""The source manifest must match the release manifest security policy.

``scripts/check_manifest.py`` inspects the *packaged* manifest (placeholders resolved, merged
library components present). This guard runs the same policy over the *source* manifest with
``${applicationId}`` resolved, so a component that is newly exported, a permission change or a
weakened protection level fails before anything is built.
"""
import json
import sys
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import check_manifest  # noqa: E402

MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
POLICY = json.loads((ROOT / "scripts/manifest-policy.json").read_text(encoding="utf-8"))
PACKAGE = POLICY["package"]


class ManifestSourcePolicyTest(unittest.TestCase):
    def test_source_manifest_matches_release_policy(self):
        text = MANIFEST.read_text(encoding="utf-8")
        resolved = text.replace("${applicationId}", PACKAGE)
        errors = check_manifest.check_manifest.__wrapped__(
            resolved, POLICY, require_sdk=False) if hasattr(
            check_manifest.check_manifest, "__wrapped__") else None
        if errors is None:
            # check_manifest() reads from a path; write the resolved manifest to a temp file.
            import tempfile
            with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as handle:
                handle.write(resolved)
                path = handle.name
            try:
                errors = check_manifest.check_manifest(path, POLICY, require_sdk=False)
            finally:
                Path(path).unlink()
        # The source manifest has no uses-sdk (the Gradle build injects it) and no merged
        # library components, so filter those expectations before comparing.
        allowed = set(POLICY["exported_components"])
        errors = [
            error for error in errors
            if not error.startswith("Packaged manifest has no uses-sdk")
        ]
        merged_missing = {
            "service:androidx.work.impl.background.systemjob.SystemJobService",
            "receiver:androidx.work.impl.diagnostics.DiagnosticsReceiver",
            "receiver:androidx.profileinstaller.ProfileInstallReceiver",
        }
        errors = [error for error in errors
                  if not any(key in error for key in merged_missing)]
        self.assertEqual([], errors,
                         "the source manifest drifted from the release security policy")
        _ = allowed  # the policy's own allowed set is enforced by check_manifest itself


if __name__ == "__main__":
    unittest.main()
