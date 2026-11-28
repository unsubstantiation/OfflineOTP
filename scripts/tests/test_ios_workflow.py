"""iOS workflow wiring/preflight regressions. Does not execute Xcode or Kotlin/Native."""
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ios.yml"


class IosWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text()
        cls.workflow = yaml.safe_load(cls.text)
        cls.job = cls.workflow["jobs"]["build"]
        cls.steps = cls.job["steps"]

    def step(self, name):
        return next(step for step in self.steps if step.get("name") == name)

    def test_apple_artifact_requires_passing_rust_tests(self):
        self.assertEqual("openpgp-host", self.job["needs"])
        rust = self.workflow["jobs"]["openpgp-host"]
        self.assertEqual("ubuntu-24.04", rust["runs-on"])
        self.assertIn("cargo test --manifest-path rust/Cargo.toml", str(rust["steps"]))
        self.assertGreater(self.job["timeout-minutes"], 35)
        for step in self.steps:
            self.assertNotIn("continue-on-error", step)

    def test_java_and_android_are_provisioned_before_gradle(self):
        java = next(s for s in self.steps if s.get("uses", "").startswith("actions/setup-java@"))
        android = next(s for s in self.steps if s.get("uses", "").startswith("android-actions/setup-android@"))
        self.assertEqual("temurin", java["with"]["distribution"])
        self.assertEqual("17", java["with"]["java-version"])
        self.assertEqual("12266719", android["with"]["cmdline-tools-version"])
        self.assertIn("platforms;android-36", android["with"]["packages"])
        self.assertIn("build-tools;36.0.0", android["with"]["packages"])
        compile_step = self.step("Build required Compose presentation XCFramework")
        self.assertLess(self.steps.index(java), self.steps.index(compile_step))
        self.assertLess(self.steps.index(android), self.steps.index(compile_step))

    def test_preflight_checks_the_actual_selected_apple_toolchain(self):
        script = self.step("Show the toolchain")["run"]
        self.assertIn("xcrun --sdk iphoneos --show-sdk-version", script)
        self.assertIn("xcrun swift --version", script)
        python = script.split("<<'PYTHON'\n", 1)[1].rsplit("\nPYTHON", 1)[0]
        for version in ("Apple Swift version 6.1 (swiftlang)", "Apple Swift version 6.2", "Apple Swift version 7.0"):
            with self.subTest(version=version), patch("subprocess.check_output", return_value=version):
                exec(compile(python, "<ios-toolchain-preflight>", "exec"), {})
        for version in ("Apple Swift version 6.0.3", "Apple Swift version 5.10", "unknown"):
            with self.subTest(version=version), patch("subprocess.check_output", return_value=version):
                with self.assertRaisesRegex(SystemExit, "Swift 6.1 or later"):
                    exec(compile(python, "<ios-toolchain-preflight>", "exec"), {})

    def test_frameworks_are_built_and_checked_before_package_resolution(self):
        rust = self.step("Build OpenPGP XCFramework")
        compose = self.step("Build required Compose presentation XCFramework")
        generate = self.step("Generate the Xcode project")
        self.assertLess(self.steps.index(rust), self.steps.index(generate))
        self.assertLess(self.steps.index(compose), self.steps.index(generate))
        self.assertIn("test -f ios/Frameworks/offlineotp_openpgp.xcframework/Info.plist", rust["run"])
        self.assertIn("test -f ios/Frameworks/OfflineOTPPresentation.xcframework/Info.plist", compose["run"])
        self.assertIn(":ui-model:desktopTest", compose["run"])
        self.assertIn(":ui-foundation:assembleOfflineOTPPresentationReleaseXCFramework", compose["run"])
        self.assertNotIn("swift test", self.text)
        self.assertNotIn("swift build -c release --build-tests", self.text)
        self.assertNotIn("path: ios/.build", self.text)

    def test_long_builds_share_the_failure_diagnostic_log(self):
        self.assertEqual("${{ github.workspace }}/ios/build-log.txt", self.job["env"]["IOS_BUILD_LOG"])
        for name in ("Build OpenPGP XCFramework", "Build required Compose presentation XCFramework",
                     "Build the unsigned Release app for iOS devices"):
            script = self.step(name)["run"]
            self.assertIn("set -euo pipefail", script)
            self.assertIn('tee -a "$IOS_BUILD_LOG"', script)
        diagnostics = self.step("Report diagnostics")["run"]
        self.assertIn("ios/build-log.txt", diagnostics)
        self.assertIn("^e: ", diagnostics)
        self.assertIn("FAILURE:", diagnostics)

    def test_every_embedded_bash_script_parses(self):
        for job in self.workflow["jobs"].values():
            for step in job["steps"]:
                if "run" not in step:
                    continue
                with self.subTest(step=step.get("name")):
                    result = subprocess.run(["bash", "-n"], input=step["run"], text=True, capture_output=True)
                    self.assertEqual(0, result.returncode, result.stderr)
