"""Mutation tests for the build-input checker.

``scripts/check_build_inputs.py`` verifies the Gradle wrapper provenance and that every CI
action reference is an immutable 40-hex pin. These tests run the checker over a copy of the
real workflow set (and, for the wrapper, over a minimal fixture tree) and confirm that both a
clean tree passes and each tampering is rejected.
"""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import check_build_inputs  # noqa: E402

WRAPPER_FILES = ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                 "gradle/wrapper/gradle-wrapper.properties"]
MUTATIONS = {
    "mutable action tag": lambda text: text.replace(
        "actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683", "actions/checkout@v4", 1),
    "unpinned local action": lambda text: text.replace(
        "android-actions/setup-android@", "some-org/some-action@", 1),
    "commented pin is not a use": lambda text: text,
}


class BuildInputsCheckerTest(unittest.TestCase):
    def copy_fixture(self):
        """A minimal tree: real wrapper files and the real workflow directory."""
        work = Path(tempfile.mkdtemp(prefix="build-inputs-"))
        self.addCleanup(shutil.rmtree, work, True)
        (work / ".github/workflows").mkdir(parents=True)
        (work / "gradle/wrapper").mkdir(parents=True)
        for name in WRAPPER_FILES:
            shutil.copy2(ROOT / name, work / name)
        for workflow in (ROOT / ".github/workflows").glob("*.yml"):
            shutil.copy2(workflow, work / ".github/workflows" / workflow.name)
        return work

    def test_clean_tree_passes(self):
        self.assertEqual([], check_build_inputs.check(self.copy_fixture()))

    def test_mutable_action_tag_is_rejected(self):
        work = self.copy_fixture()
        workflow = sorted((work / ".github/workflows").glob("*.yml"))[0]
        text = workflow.read_text(encoding="utf-8")
        workflow.write_text(MUTATIONS["mutable action tag"](text), encoding="utf-8")
        errors = check_build_inputs.check(work)
        self.assertTrue(any("Unpinned workflow action" in error for error in errors), errors)

    def test_missing_workflow_directory_is_rejected(self):
        work = self.copy_fixture()
        shutil.rmtree(work / ".github/workflows")
        errors = check_build_inputs.check(work)
        self.assertTrue(any("workflow directory is missing" in error for error in errors), errors)

    def test_tampered_wrapper_is_rejected(self):
        work = self.copy_fixture()
        (work / "gradlew").write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        errors = check_build_inputs.check(work)
        self.assertTrue(any("Wrapper provenance mismatch: gradlew" in error for error in errors), errors)

    def test_changed_distribution_url_is_rejected(self):
        work = self.copy_fixture()
        properties = work / "gradle/wrapper/gradle-wrapper.properties"
        text = properties.read_text(encoding="utf-8").replace(
            "gradle-8.13-all.zip", "gradle-8.14-all.zip")
        properties.write_text(text, encoding="utf-8")
        errors = check_build_inputs.check(work)
        self.assertTrue(any("Unexpected Gradle distribution URL" in error for error in errors), errors)

    def test_removed_distribution_checksum_is_rejected(self):
        work = self.copy_fixture()
        properties = work / "gradle/wrapper/gradle-wrapper.properties"
        text = "".join(
            line for line in properties.read_text(encoding="utf-8").splitlines(keepends=True)
            if not line.startswith("distributionSha256Sum="))
        properties.write_text(text, encoding="utf-8")
        errors = check_build_inputs.check(work)
        self.assertTrue(
            any("Gradle distribution checksum is missing or changed" in error for error in errors),
            errors)

    def test_real_checkout_passes(self):
        self.assertEqual([], check_build_inputs.check(ROOT))


if __name__ == "__main__":
    unittest.main()
