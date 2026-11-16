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
        self.assertEqual(['ubuntu-24.04', 'windows-2022', 'macos-14'], job['strategy']['matrix']['os'])
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

    def test_cutover_ledger_does_not_assert_unperformed_validation(self):
        text = (ROOT / 'docs/compose-desktop-cutover.md').read_text()
        self.assertIn('Validation remains incomplete; this is not a validated release.', text)
        self.assertIn('preparation, not a validation attestation', text)
        self.assertIn('cutover approval has already been given', text)
        self.assertIn('Reported successful; detailed scope unrecorded', text)
        self.assertIn('not** the build that was tested', text)

if __name__ == '__main__':
    unittest.main()
