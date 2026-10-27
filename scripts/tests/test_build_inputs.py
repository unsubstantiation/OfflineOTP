"""Mutation tests for Gradle wrapper provenance and distribution settings."""
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



class BuildInputsCheckerTest(unittest.TestCase):
    def copy_fixture(self):
        """A minimal tree containing the real wrapper files."""
        work = Path(tempfile.mkdtemp(prefix="build-inputs-"))
        self.addCleanup(shutil.rmtree, work, True)
        (work / "gradle/wrapper").mkdir(parents=True)
        for name in WRAPPER_FILES:
            shutil.copy2(ROOT / name, work / name)
        return work

    def test_clean_tree_passes(self):
        self.assertEqual([], check_build_inputs.check(self.copy_fixture()))

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
