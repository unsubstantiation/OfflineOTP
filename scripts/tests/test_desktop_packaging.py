"""Guards for the desktop release packaging that the offline sandbox cannot execute.

The Gradle ``packageDesktop`` jpackage task and the reproducible-distribution script only run on a
real JDK 17+ host with network access, so these structural checks keep their wiring from silently
drifting and pin the CI workflow that actually executes the headless gates.
"""
import os
import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP_BUILD = ROOT / "desktop-compose/build.gradle"
PACKAGING_DIR = ROOT / "scripts/packaging"
CI_SCRIPT = ROOT / "scripts/ci/desktop-ci.sh"


class DesktopPackagingTest(unittest.TestCase):

    def setUp(self):
        self.build = DESKTOP_BUILD.read_text(encoding="utf-8")
        self.script = (PACKAGING_DIR / "verify-desktop-build.sh").read_text(encoding="utf-8")
        self.ci = CI_SCRIPT.read_text(encoding="utf-8")

    def test_compose_is_the_only_launcher_with_native_staging(self):
        engine = (ROOT / "desktop/build.gradle").read_text()
        self.assertIn("dependsOn ':desktop-compose:run'", engine)
        self.assertNotIn("runSwing", engine)
        self.assertNotIn("packageSwingDesktop", engine)
        self.assertIn("dependsOn ':desktop-compose:packageDesktop'", engine)
        self.assertIn("from(tasks.named('stageOpenPgpNative')) { into 'lib/native' }", self.build)
        self.assertIn("'-Djna.library.path=$APPDIR/native'", self.build)
        self.assertIn("implementation compose.desktop.currentOs", self.build)
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn('System.getProperty("jna.library.path") == null', host)
        self.assertIn('java.io.File(location.parentFile, "native")', host)

    def test_distribution_archives_are_reproducible(self):
        self.assertIn("apply plugin: 'application'", self.build)
        self.assertIn("preserveFileTimestamps = false", self.build)
        self.assertIn("reproducibleFileOrder = true", self.build)
        self.assertIn("tasks.withType(AbstractArchiveTask)", self.build)

    def test_jpackage_task_is_guarded_and_targets_the_main_class(self):
        self.assertIn("tasks.register('packageDesktop', Exec)", self.build)
        self.assertIn("bin/jpackage", self.build)
        self.assertIn("--main-class', 'com.fairuniversal.offlineotp.desktop.compose.ComposeMainKt'", self.build)
        self.assertIn("--type', 'app-image'", self.build)
        # An explicitly requested release task must fail rather than silently omit the package.
        self.assertIn("if (!jpackage.canExecute()) throw new GradleException", self.build)

    def test_desktop_icons_match_branding_and_package_configuration(self):
        png = (ROOT / 'desktop/src/main/resources/offlineotp.png').read_bytes()
        self.assertEqual((ROOT / 'assets/logo.png').read_bytes(), png)
        ico = (ROOT / 'desktop/packaging/offlineotp.ico').read_bytes()
        reserved, kind, count = struct.unpack_from('<HHH', ico)
        self.assertEqual((0, 1), (reserved, kind))
        images = {}
        for index in range(count):
            width, height, _, _, _, _, length, offset = struct.unpack_from('<BBBBHHII', ico, 6 + 16 * index)
            images[(width or 256, height or 256)] = ico[offset:offset + length]
        self.assertEqual({(size, size) for size in (16, 24, 32, 48, 64, 128, 256)}, set(images))
        for data in images.values():
            self.assertTrue(data.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertEqual(png, images[(256, 256)])
        self.assertIn("rootProject.file('desktop/packaging/offlineotp.ico')", self.build)
        self.assertIn("rootProject.file('desktop/src/main/resources/offlineotp.png')", self.build)
        self.assertIn("args '--icon', icon.absolutePath", self.build)

    def test_reproducibility_script_builds_twice_independently_and_compares(self):
        self.assertIn(":desktop-compose:distZip", self.script)
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
        self.assertIn(":desktop-compose:distZip", self.ci)
        self.assertIn("bootstrap-toolchain.sh", self.ci)
        # The desktop CI must not pretend the Android SDK build runs there.
        self.assertNotIn("assembleFdroidRelease", self.ci)
        self.assertIn("bin/jpackage.exe", self.build)


if __name__ == "__main__":
    unittest.main()
