"""Guards for the desktop release packaging that the offline sandbox cannot execute.

The Gradle ``packageDesktop`` jpackage task and the reproducible-distribution script only run on a
real JDK 17+ host with network access, so these structural checks keep their wiring from silently
drifting and pin the CI workflow that actually executes the headless gates.
"""
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP_BUILD = ROOT / "desktop/build.gradle"
PACKAGING_DIR = ROOT / "scripts/packaging"
CI_SCRIPT = ROOT / "scripts/ci/desktop-ci.sh"


class DesktopPackagingTest(unittest.TestCase):

    def setUp(self):
        self.build = DESKTOP_BUILD.read_text(encoding="utf-8")
        self.script = (PACKAGING_DIR / "verify-desktop-build.sh").read_text(encoding="utf-8")
        self.ci = CI_SCRIPT.read_text(encoding="utf-8")

    def test_distribution_archives_are_reproducible(self):
        self.assertIn("apply plugin: 'application'", self.build)
        self.assertIn("preserveFileTimestamps = false", self.build)
        self.assertIn("reproducibleFileOrder = true", self.build)
        self.assertIn("tasks.withType(AbstractArchiveTask)", self.build)

    def test_jpackage_task_is_guarded_and_targets_the_main_class(self):
        self.assertIn("tasks.register('packageDesktop', Exec)", self.build)
        self.assertIn("bin/jpackage", self.build)
        self.assertIn("--main-class', 'org.shadowice.flocke.andotp.desktop.MainKt'", self.build)
        self.assertIn("--type', 'app-image'", self.build)
        # A JRE without jpackage must skip the task instead of breaking other builds.
        self.assertIn("onlyIf { jpackage.canExecute() }", self.build)

    def test_reproducibility_script_builds_twice_independently_and_compares(self):
        self.assertIn(":desktop:distZip", self.script)
        # Parameterized per-run Gradle homes, with both run indices exercised in the loop.
        self.assertIn('GRADLE_USER_HOME="$work/gradle-home-$run"', self.script)
        self.assertIn("for run in 1 2", self.script)
        self.assertIn("--no-build-cache", self.script)
        self.assertIn('cmp "$report/run-1/desktop-dist.zip" "$report/run-2/desktop-dist.zip"',
                      self.script)
        self.assertTrue(os.access(PACKAGING_DIR / "verify-desktop-build.sh", os.X_OK),
                        "reproducibility script must be executable")

    def test_packaging_readme_documents_the_jdk_requirement(self):
        readme = (PACKAGING_DIR / "README.md").read_text(encoding="utf-8")
        self.assertIn("JDK 17", readme)
        self.assertIn("jpackage", readme)
        self.assertIn("distZip", readme)

    def test_ci_script_runs_the_headless_gates_and_a_real_gradle_distribution(self):
        # The script remains useful for local and external CI runs.
        self.assertTrue(os.access(CI_SCRIPT, os.X_OK), "CI script must be executable")
        for gate in (
            "scripts/kotlin-sandbox/compile-core.sh",
            "scripts/kotlin-sandbox/compile-desktop.sh",
            "scripts/run-jvm-tests.sh",
            "python3 -m unittest discover -s scripts/tests -p \"test_*.py\"",
        ):
            self.assertIn(gate, self.ci)
        self.assertIn(":desktop:distZip", self.ci)
        self.assertIn("bootstrap-toolchain.sh", self.ci)
        # The desktop CI must not pretend the Android SDK build runs there.
        self.assertNotIn("assembleFdroidRelease", self.ci)
        self.assertIn("bin/jpackage.exe", self.build)


if __name__ == "__main__":
    unittest.main()
