"""Guards for the JVM unit-test suite (app/src/test + scripts/run-jvm-tests.sh).

The suite is plain JUnit 4 so a Gradle host runs it unchanged (``./gradlew test``), while
``scripts/run-jvm-tests.sh`` drives the same sources against the sandbox toolchain with the
minimal JUnit shim in scripts/jvm-tests. These checks pin the wiring so the two cannot drift:

* every test file sits in the right package and actually declares @Test methods,
* app/build.gradle wires the host and instrumentation dependencies,
* the harness compiles exactly the source set scripts/kotlin-sandbox/run.sh compiles, and
* the reflective runner reports failures with a non-zero exit code (verified end-to-end by
  compiling a deliberately failing test whenever the sandbox toolchain is available).
"""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST_ROOT = ROOT / "app/src/test/kotlin"
BUILD_GRADLE = ROOT / "app/build.gradle"
HARNESS = ROOT / "scripts/run-jvm-tests.sh"
SHIM = ROOT / "scripts/jvm-tests"
TOOLCHAIN = Path(os.environ.get("KOTLIN_SANDBOX_TOOLCHAIN",
                                os.path.join(tempfile.gettempdir(), "kotlin-sandbox-toolchain")))


def kotlinc_path():
    for candidate in (os.environ.get("KOTLINC"), shutil_which("kotlinc-jvm"),
                      str(TOOLCHAIN / "kotlin/bin/kotlinc-jvm")):
        if candidate and os.path.isfile(candidate):
            return candidate
    return None


def shutil_which(name):
    from shutil import which
    return which(name)


def java_path():
    for candidate in (os.environ.get("JAVA_BIN"),
                      str(TOOLCHAIN / "jdk/jdk4py/java-runtime/bin/java")):
        if candidate and os.path.isfile(candidate):
            return candidate
    return None


class JvmTestSuiteStructureTest(unittest.TestCase):
    def test_test_files_exist_and_declare_matching_packages(self):
        files = sorted(TEST_ROOT.rglob("*.kt"))
        self.assertTrue(files, "no JVM test sources found")
        for path in files:
            text = path.read_text(encoding="utf-8")
            package = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
            self.assertIsNotNone(package, f"{path} declares no package")
            expected = path.parent.relative_to(TEST_ROOT).as_posix().replace("/", ".")
            self.assertEqual(expected, package.group(1),
                             f"{path} package does not match its directory")

    def test_every_test_file_declares_junit_tests(self):
        for path in sorted(TEST_ROOT.rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            self.assertIn("import org.junit.Test", text, f"{path.name}: missing JUnit import")
            self.assertRegex(text, r"@Test\s*\n\s*fun ",
                             f"{path.name} declares no @Test method")

    def test_build_gradle_wires_the_suite(self):
        text = BUILD_GRADLE.read_text(encoding="utf-8")
        for needle in (
            "testImplementation 'junit:junit:4.13.2'",
            "testImplementation 'org.json:json:",
            "androidTestImplementation 'androidx.test.ext:junit:1.2.1'",
            "androidTestImplementation 'androidx.test:runner:1.6.2'",
            "androidTestImplementation 'androidx.test:core:1.6.1'",
            'testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"',
        ):
            self.assertIn(needle, text, f"app/build.gradle lost: {needle}")

    def test_harness_stays_in_lockstep_with_the_sandbox_check(self):
        text = HARNESS.read_text(encoding="utf-8")
        # The compiled source set must be read out of run.sh, not duplicated here.
        self.assertIn("eval \"$(sed -n '/^APP_SOURCES=(/,/^)$/p' \"$SANDBOX/run.sh\")\"", text)
        self.assertIn("eval \"$(sed -n '/^STUBS=(/,/^)$/p' \"$SANDBOX/run.sh\")\"", text)
        self.assertIn("app/src/test/kotlin", text)
        self.assertTrue(os.access(HARNESS, os.X_OK), "run-jvm-tests.sh must be executable")
        self.assertTrue((SHIM / "org/junit/JUnit4.kt").is_file(), "JUnit shim is missing")

    def test_shim_covers_the_assertions_the_suite_uses(self):
        """Every org.junit member a test imports must exist in the shim."""
        shim = (SHIM / "org/junit/JUnit4.kt").read_text(encoding="utf-8")
        used = set()
        for path in TEST_ROOT.rglob("*.kt"):
            text = path.read_text(encoding="utf-8").replace(" ", "")
            used.update(re.findall(r"importorg\.junit\.Assert\.(\w+)", text))
            used.update(re.findall(r"importorg\.junit\.Assume\.(\w+)", text))
        self.assertTrue(used, "no JUnit imports found in the suite")
        for name in sorted(used):
            self.assertRegex(shim, rf"fun {name}\(", f"shim lacks Assert.{name}")

    def test_no_test_touches_the_unmocked_android_runtime(self):
        """A JVM test must not call into android.jar: the host stubs throw 'not mocked'."""
        for path in TEST_ROOT.rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
            stripped = re.sub(r"//[^\n]*", "", stripped)
            self.assertNotRegex(stripped, r"^\s*import (android|androidx)\.",
                                f"{path.relative_to(ROOT)} imports the Android runtime")
            self.assertNotRegex(stripped, r"\bandroid\w*(\.\w+)+",
                                f"{path.relative_to(ROOT)} references Android runtime types; "
                                "move that coverage to androidTest")


@unittest.skipIf(kotlinc_path() is None or java_path() is None,
                 "kotlinc/java unavailable; run scripts/kotlin-sandbox/bootstrap-toolchain.sh")
class JvmTestRunnerBehaviorTest(unittest.TestCase):
    """End-to-end: the reflective runner must fail the run on a failing test."""

    def compile_and_run(self, sources, work):
        out = work / "classes"
        out.mkdir()
        kotlinc = kotlinc_path()
        java = java_path()
        stdlib = str(Path(kotlinc).resolve().parent.parent / "lib/kotlin-stdlib.jar")
        # kotlinc's launcher resolves its JVM through JAVA_HOME/PATH, like run-jvm-tests.sh does.
        env = dict(os.environ)
        env.setdefault("JAVA_HOME", str(Path(java).resolve().parent.parent))
        env["PATH"] = f"{Path(java).resolve().parent}:{env.get('PATH', '')}"
        result = subprocess.run(
            [kotlinc, "-jvm-target", "1.8", "-nowarn",
             *sorted(str(path) for path in SHIM.rglob("*.kt")),
             *map(str, sources), "-d", str(out)],
            capture_output=True, text=True, timeout=600, env=env)
        self.assertEqual(0, result.returncode, f"compile failed:\n{result.stderr}")
        return subprocess.run(
            [java, "-cp", f"{out}:{stdlib}", "JvmTestRunnerKt", str(out)],
            capture_output=True, text=True, timeout=300, env=env)

    def test_failing_test_fails_the_run(self):
        with tempfile.TemporaryDirectory(prefix="jvm-runner-") as work:
            work = Path(work)
            (work / "FailingTest.kt").write_text(
                "import org.junit.Assert.assertEquals\n"
                "import org.junit.Test\n"
                "class FailingTest {\n"
                "    @Test fun broken() { assertEquals(1, 2) }\n"
                "    @Test fun fine() { assertEquals(1, 1) }\n"
                "}\n", encoding="utf-8")
            result = self.compile_and_run([work / "FailingTest.kt"], work)
            self.assertEqual(1, result.returncode, result.stdout)
            self.assertIn("PASS  FailingTest.fine", result.stdout)
            self.assertIn("FAIL  FailingTest.broken", result.stdout)
            self.assertIn("failed: 1", result.stdout)

    def test_clean_run_exits_zero_and_reports_counts(self):
        with tempfile.TemporaryDirectory(prefix="jvm-runner-") as work:
            work = Path(work)
            (work / "PassingTest.kt").write_text(
                "import org.junit.Assert.assertTrue\n"
                "import org.junit.Test\n"
                "class PassingTest {\n"
                "    @Test fun fine() { assertTrue(true) }\n"
                "}\n", encoding="utf-8")
            result = self.compile_and_run([work / "PassingTest.kt"], work)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("PASS  PassingTest.fine", result.stdout)
            self.assertIn("failed: 0", result.stdout)


if __name__ == "__main__":
    unittest.main()
