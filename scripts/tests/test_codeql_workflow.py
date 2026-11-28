"""CodeQL wiring checks, not execution of CodeQL or native build tools."""
from pathlib import Path
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class CodeqlWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load((ROOT / '.github/workflows/codeql.yml').read_text())
        self.job = self.workflow['jobs']['analyze']
        self.steps = self.job['steps']

    def test_languages_keep_kotlin_manual_and_other_scans_enabled(self):
        matrix = self.job['strategy']['matrix']['include']
        self.assertEqual({'java-kotlin': 'manual', 'python': 'none',
                          'rust': 'none', 'actions': 'none'},
                         {row['language']: row['build-mode'] for row in matrix})
        self.assertFalse(self.job['strategy']['fail-fast'])
        self.assertEqual('write', self.job['permissions']['security-events'])
        for step in self.steps:
            self.assertNotIn('continue-on-error', step)
            self.assertNotIn('autobuild', step.get('uses', ''))

    def test_prerequisites_precede_traced_clean_compilation(self):
        names = [step.get('name') for step in self.steps]
        self.assertLess(names.index('Set up Java'), names.index('Initialize CodeQL'))
        self.assertLess(names.index('Set up Android SDK'), names.index('Initialize CodeQL'))
        self.assertLess(names.index('Initialize CodeQL'),
                        names.index('Compile Android and desktop production sources'))
        java = next(s for s in self.steps if s.get('name') == 'Set up Java')
        self.assertEqual('17', java['with']['java-version'])
        android = next(s for s in self.steps if s.get('name') == 'Set up Android SDK')
        self.assertIn('platforms;android-36', android['with']['packages'])
        build = next(s for s in self.steps if 'run' in s)
        self.assertEqual("matrix.language == 'java-kotlin'", build['if'])
        for flag in ('--no-build-cache', '--rerun-tasks', '--no-daemon'):
            self.assertIn(flag, build['run'])
        for task in (':app:compileFdroidDebugKotlin', ':app:compilePlayDebugKotlin',
                     ':desktop-compose:compileKotlin', ':ui-foundation:compileKotlinDesktop'):
            self.assertIn(task, build['run'])
        subprocess.run(['bash', '-n'], input=build['run'], text=True, check=True)
        analyze = self.steps[-1]
        self.assertIn('github/codeql-action/analyze@', analyze['uses'])

    def test_administrator_handoff_does_not_claim_settings_changed(self):
        doc = (ROOT / 'docs/codeql.md').read_text()
        self.assertIn('default setup', doc)
        self.assertIn('HTTP 403', doc)
        self.assertIn('no setting was changed', doc)
        self.assertIn('still needs a successful GitHub-hosted run', doc)
