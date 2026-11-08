"""The manual emulator gate must not confuse compilation, missing tests or skips with UI passes."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("compose_results", ROOT / "scripts/check_compose_device_results.py")
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


class ComposeDeviceValidationTest(unittest.TestCase):
    def report(self, directory, outcome=None, omit=False):
        root = ET.Element("testsuite")
        for index, (classname, method) in enumerate(sorted(results.EXPECTED)):
            if omit and index == 0:
                continue
            node = ET.SubElement(root, "testcase", classname=classname, name=method)
            if outcome and index == 0:
                ET.SubElement(node, outcome)
        ET.ElementTree(root).write(Path(directory) / "TEST-device.xml")

    def test_only_complete_success_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            self.report(directory)
            self.assertEqual([], results.check(Path(directory)))
            for outcome in ("skipped", "failure", "error"):
                self.report(directory, outcome=outcome)
                self.assertTrue(results.check(Path(directory)), outcome)
            self.report(directory, omit=True)
            self.assertTrue(results.check(Path(directory)))

    def test_missing_and_malformed_reports_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(results.check(Path(directory)))
            Path(directory, "broken.xml").write_text("<incomplete")
            self.assertTrue(results.check(Path(directory)))

    def test_expected_cases_match_real_test_methods(self):
        for classname, method in results.EXPECTED:
            source = ROOT / "app/src/androidTest/kotlin" / (classname.replace(".", "/") + ".kt")
            self.assertIn(f"fun {method}()", source.read_text())

    def test_gate_is_manual_unsigned_and_runs_both_flavors(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        job = workflow.split("  compose-validation:", 1)[1].split("  signing-preflight:", 1)[0]
        self.assertIn("if: github.event_name == 'workflow_dispatch'", job)
        self.assertNotIn("secrets.", job)
        self.assertNotIn("environment:", job)
        self.assertNotIn("continue-on-error", job)
        self.assertIn("flavor: Fdroid", job)
        self.assertIn("flavor: Play", job)
        self.assertIn("api: 28", job)
        self.assertIn("api: 36", job)
        self.assertIn("DebugAndroidTest", job)
        self.assertIn("check_compose_device_results.py", job)
        self.assertIn("if: always()", job)
        self.assertNotIn("outputs/apk", job)
