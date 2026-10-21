"""Structure guards for the CI workflows and the emulator lifecycle script.

``check_build_inputs.py`` already enforces the action pins; this file pins the *shape* of the
quality gate so a rename or a dropped matrix leg cannot silently shrink coverage:

* android-ci.yml keeps its five jobs (source checks, host tests, build/lint/manifest policy,
  the fdroid/play x API 28/36 instrumentation matrix, and the master/manual reproducibility
  check),
* the emulator job delegates the whole lifecycle to scripts/ci/start-emulator.sh,
* dependency-baseline.yml stays manual-only and runs writeDependencyInventory.

Requires PyYAML; skipped when unavailable (GitHub's ubuntu runners ship it).
"""
import os
import unittest
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - depends on the host
    yaml = None

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github/workflows"
CI = WORKFLOWS / "android-ci.yml"
BASELINE = WORKFLOWS / "dependency-baseline.yml"
EMULATOR_SCRIPT = ROOT / "scripts/ci/start-emulator.sh"

EXPECTED_JOBS = ["source-checks", "host-tests", "build-and-lint", "instrumentation",
                 "reproducibility"]


def load(path):
    with open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def run_strings(job):
    return " ".join(step.get("run", "") for step in job.get("steps", []))


@unittest.skipIf(yaml is None, "PyYAML unavailable")
class AndroidCiWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.doc = load(CI)
        self.jobs = self.doc["jobs"]

    def test_all_five_jobs_exist(self):
        self.assertEqual(EXPECTED_JOBS, list(self.jobs))

    def test_source_checks_run_the_python_guards(self):
        steps = run_strings(self.jobs["source-checks"])
        self.assertIn("python3 -m unittest discover -s scripts/tests", steps)
        self.assertIn("python3 scripts/check_build_inputs.py", steps)

    def test_host_tests_run_every_no_sdk_gate(self):
        steps = run_strings(self.jobs["host-tests"])
        for script in ("scripts/kotlin-sandbox/bootstrap-toolchain.sh",
                       "scripts/kotlin-sandbox/run.sh",
                       "scripts/run-jvm-tests.sh",
                       "scripts/kotlin-sandbox/compile-android-tests.sh"):
            self.assertIn(script, steps, f"host-tests lost {script}")

    def test_build_and_lint_covers_variants_tests_lint_and_manifest_policy(self):
        steps = run_strings(self.jobs["build-and-lint"])
        for task in ("assembleFdroidDebug", "assembleFdroidRelease",
                     "assemblePlayDebug", "assemblePlayRelease",
                     "assembleFdroidDebugAndroidTest", "assemblePlayDebugAndroidTest",
                     "testFdroidDebugUnitTest", "testPlayDebugUnitTest",
                     "lintFdroidDebug", "lintFdroidRelease", "lintPlayDebug", "lintPlayRelease"):
            self.assertIn(task, steps, f"build-and-lint lost :app:{task}")
        self.assertIn("check_manifest.py", steps)
        self.assertIn("apkanalyzer manifest print", steps)

    def test_instrumentation_matrix_is_the_full_flavor_api_grid(self):
        job = self.jobs["instrumentation"]
        self.assertEqual({"flavor": ["fdroid", "play"], "api": ["28", "36"]},
                         job["strategy"]["matrix"])
        self.assertFalse(job["strategy"]["fail-fast"])
        self.assertEqual("build-and-lint", job["needs"])
        self.assertTrue(str(job.get("timeout-minutes", 0)).isdigit() and
                        job["timeout-minutes"] >= 30)
        steps = run_strings(job)
        self.assertIn("scripts/ci/start-emulator.sh ${{ matrix.flavor }} ${{ matrix.api }}",
                      steps)

    def test_reproducibility_is_master_or_manual_only(self):
        job = self.jobs["reproducibility"]
        self.assertIn("github.event_name == 'workflow_dispatch'", job["if"])
        self.assertIn("github.ref == 'refs/heads/master'", job["if"])
        self.assertIn("scripts/verify_reproducible_build.sh", run_strings(job))


@unittest.skipIf(yaml is None, "PyYAML unavailable")
class DependencyBaselineWorkflowTest(unittest.TestCase):
    def test_baseline_is_manual_and_runs_the_inventory_task(self):
        doc = load(BASELINE)
        self.assertEqual({"workflow_dispatch"}, set(doc[True].keys()) if True in doc else
                         set(doc["on"].keys()))
        job = doc["jobs"]["inventory"]
        steps = run_strings(job)
        self.assertIn(":app:writeDependencyInventory", steps)
        self.assertTrue(any("dependency-inventory.txt" in str(step.get("with", {}).get("path", ""))
                            for step in job["steps"]),
                        "the inventory artifact upload is missing")


class EmulatorScriptTest(unittest.TestCase):
    def test_script_exists_and_is_executable(self):
        self.assertTrue(EMULATOR_SCRIPT.is_file())
        self.assertTrue(os.access(EMULATOR_SCRIPT, os.X_OK),
                        "start-emulator.sh must be executable")

    def test_script_validates_arguments_and_tears_down(self):
        text = EMULATOR_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("usage: $0 <fdroid|play> <28|36>", text)
        self.assertIn("trap cleanup EXIT", text, "the emulator/AVD teardown must be unconditional")
        for required in ("create avd -n", "sys.boot_completed", "emu kill",
                         "delete avd -n", "DebugAndroidTest"):
            self.assertIn(required, text, f"start-emulator.sh lost: {required}")

    def test_license_acceptance_avoids_pipefail_sigpipe(self):
        text = EMULATOR_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("sdkmanager-licenses-input.txt", text)
        self.assertIn("printf 'y\\n%.0s' {1..100}", text)
        self.assertNotIn("yes 2>/dev/null |", text,
                         "yes gets SIGPIPE when sdkmanager closes the license prompt")

    def test_external_emulator_waits_are_bounded_and_reportable(self):
        text = EMULATOR_SCRIPT.read_text(encoding="utf-8")
        for required in (
            'timeout 60 "$SDKMANAGER" --licenses',
            'timeout 720 "$SDKMANAGER" --install',
            'timeout 60 "$AVDMANAGER" create avd',
            'export ANDROID_AVD_HOME="$HOME/.android/avd"',
            'timeout 30 "$EMULATOR" -list-avds',
            'timeout 60 "$ADB" wait-for-device',
            'boot_deadline=$((SECONDS + 480))',
            'timeout 900 ./gradlew --no-daemon',
            '::notice title=Android emulator progress::',
            '::error title=Android emulator failure::',
        ):
            self.assertIn(required, text, f"emulator CI lost a diagnostic/timeout: {required}")


if __name__ == "__main__":
    unittest.main()
