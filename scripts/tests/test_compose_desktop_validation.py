"""Source guards for the opt-in real-build gate; these do not run Gradle or devices."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]

class ComposeDesktopValidationTest(unittest.TestCase):
    def test_manual_read_only_matrix_and_pinned_actions(self):
        text = (ROOT / '.github/workflows/compose-desktop-validation.yml').read_text()
        workflow = yaml.safe_load(text)
        triggers = workflow.get('on', workflow.get(True))
        self.assertEqual({'workflow_dispatch': None}, triggers)
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        job = workflow['jobs']['desktop']
        self.assertEqual(['ubuntu-24.04', 'windows-2022', 'macos-15'], job['strategy']['matrix']['os'])
        for step in job['steps']:
            if 'uses' in step:
                self.assertRegex(step['uses'], r'@[0-9a-f]{40}$')
        for task in (':core:test', ':ui-model:desktopTest', ':desktop:test', ':desktop-compose:test', ':desktop-compose:installDist'):
            self.assertIn(task, text)
        self.assertIn('set -euo pipefail', text)
        self.assertIn('git rev-parse HEAD', text)
        self.assertIn('if: always()', text)
        self.assertNotIn('secrets.', text)
        self.assertNotIn('continue-on-error', text)

    def test_sdk_substitute_suite_still_runs_in_ci_outside_real_sdk_tests(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/compose-desktop-validation.yml').read_text())
        job = workflow['jobs']['scripted-hardware']
        steps = str(job['steps'])
        self.assertIn('bootstrap-toolchain.sh', steps)
        self.assertIn('compile-desktop.sh', steps)
        self.assertNotIn('continue-on-error', steps)
        scripted = ROOT / 'scripts/kotlin-sandbox/desktop-tests/com/fairuniversal/offlineotp/desktop/hardware/HardwareKeyManagerTest.kt'
        self.assertTrue(scripted.is_file())
        self.assertIn('OathSandbox', scripted.read_text())
        gate = (ROOT / 'scripts/kotlin-sandbox/compile-desktop.sh').read_text()
        self.assertIn('"$SANDBOX/desktop-tests"', gate)
        for source in (ROOT / 'desktop/src/test').rglob('*.kt'):
            self.assertNotIn('import com.yubico.yubikit.oath.OathSandbox', source.read_text())
            self.assertNotIn('import com.yubico.yubikit.fido.client.FidoSandbox', source.read_text())
        fixture = (ROOT / 'desktop/src/test/kotlin/com/yubico/yubikit/oath/CredentialFixture.kt').read_text()
        self.assertIn('Credential("test-device", id.toByteArray(Charsets.UTF_8), type, false)', fixture)

    def test_cutover_ledger_records_reported_verification_without_inventing_evidence(self):
        text = (ROOT / 'docs/compose-desktop-cutover.md').read_text()
        self.assertIn('all non-iOS builds/workflows and platform checks verified', text)
        self.assertIn('Detailed\nper-scenario artifact logs are not recorded here', text)
        self.assertIn('iOS is separately build-blocked', text)
        self.assertIn('not the exact artifact tested later', text)
        self.assertIn('Rollback requires a prior', text)

if __name__ == '__main__':
    unittest.main()
