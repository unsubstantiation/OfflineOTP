"""Regression checks for the local emulator and instrumentation-report helpers."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EMULATOR_SCRIPT = ROOT / "scripts/ci/start-emulator.sh"
FAILURE_SUMMARIZER = ROOT / "scripts/ci/summarize-instrumentation-failures.py"


class EmulatorScriptTest(unittest.TestCase):
    def test_script_exists_and_is_executable(self):
        self.assertTrue(EMULATOR_SCRIPT.is_file())
        self.assertTrue(os.access(EMULATOR_SCRIPT, os.X_OK),
                        "start-emulator.sh must be executable")

    def test_script_validates_arguments_and_tears_down(self):
        text = EMULATOR_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("usage: $0 <fdroid|play> <28|36>", text)
        self.assertIn('avd="ci-offlineotp-$flavor-$api"', text)
        self.assertIn("trap cleanup EXIT", text, "the emulator/AVD teardown must be unconditional")
        for required in ("create avd -n", "sys.boot_completed", "emu kill",
                         "delete avd -n", "DebugAndroidTest"):
            self.assertIn(required, text, f"start-emulator.sh lost: {required}")

    def test_invalid_arguments_are_rejected_before_starting(self):
        for arguments in ([], ["fdroid"], ["invalid", "28"], ["play", "30"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run([str(EMULATOR_SCRIPT), *arguments],
                                        capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertIn("usage:", result.stderr)

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
            'summarize_test_failures > "$report/test-failures.txt"',
            'summarize-instrumentation-failures.py',
        ):
            self.assertIn(required, text, f"emulator helper lost a diagnostic/timeout: {required}")


class InstrumentationFailureSummaryTest(unittest.TestCase):
    def test_reports_failed_test_names_messages_and_stack_traces(self):
        with tempfile.TemporaryDirectory() as temporary:
            build = Path(temporary)
            result_file = build / "outputs/androidTest-results/connected/TEST-device.xml"
            result_file.parent.mkdir(parents=True)
            result_file.write_text(
                '<testsuite><testcase classname="org.example.DeviceTest" name="fails">'
                '<failure message="expected value">at org.example.DeviceTest.fails(DeviceTest.kt:9)'
                '</failure></testcase></testsuite>',
                encoding="utf-8",
            )
            result = subprocess.run(
                [sys.executable, str(FAILURE_SUMMARIZER), str(build)],
                check=True, capture_output=True, text=True,
            )

        self.assertIn("org.example.DeviceTest.fails: expected value", result.stdout)
        self.assertIn("DeviceTest.kt:9", result.stdout)

    def test_plain_report_includes_multiple_failed_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            build = Path(temporary)
            result_file = build / "outputs/androidTest-results/connected/TEST-device.xml"
            result_file.parent.mkdir(parents=True)
            result_file.write_text(
                '<testsuite>'
                '<testcase classname="org.example.DeviceTest" name="first">'
                '<failure message="one">first trace</failure></testcase>'
                '<testcase classname="org.example.DeviceTest" name="second">'
                '<error message="two">second trace</error></testcase>'
                '</testsuite>', encoding="utf-8")
            result = subprocess.run([sys.executable, str(FAILURE_SUMMARIZER), str(build)],
                                    check=True, capture_output=True, text=True)
        self.assertIn("DeviceTest.first: one", result.stdout)
        self.assertIn("DeviceTest.second: two", result.stdout)

    def test_reports_when_no_instrumentation_results_exist(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, str(FAILURE_SUMMARIZER), temporary],
                check=True, capture_output=True, text=True,
            )
        self.assertIn("No Android instrumentation JUnit XML", result.stdout)


if __name__ == "__main__":
    unittest.main()
