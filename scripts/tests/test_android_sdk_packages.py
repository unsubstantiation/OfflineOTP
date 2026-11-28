"""Guard the published API 37 package ID across every Android SDK setup.

Source wiring only: actual SDK installation and Gradle builds still run in CI.
"""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class AndroidSdkPackagesTest(unittest.TestCase):
    def test_every_sdk_setup_uses_published_platform_id(self):
        counts = {}
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            workflow = yaml.safe_load(path.read_text())
            for job in workflow.get('jobs', {}).values():
                for step in job.get('steps', []):
                    if not step.get('uses', '').startswith('android-actions/setup-android@'):
                        continue
                    with self.subTest(workflow=path.name, job=job.get('name')):
                        packages = step['with']['packages'].split()
                        self.assertIn('platforms;android-37.0', packages)
                        self.assertNotIn('platforms;android-37', packages)
                        self.assertIn('build-tools;37.0.0', packages)
                        self.assertIn('platform-tools', packages)
                        self.assertNotIn('continue-on-error', step)
                        counts[path.name] = counts.get(path.name, 0) + 1
        self.assertEqual({
            'android.yml': 2,
            'compose-desktop-validation.yml': 1,
            'debian.yml': 1,
            'ios.yml': 1,
            'macos.yml': 1,
            'windows.yml': 1,
        }, counts)
