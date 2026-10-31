"""Guard signing configuration order and exercise CI's keystore diagnostics."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseSigningTest(unittest.TestCase):
    def test_signing_configuration_precedes_build_types(self):
        # Groovy locals must be declared before nested DSL closures capture them;
        # the named signing config must also exist before a build type uses it.
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        declaration = gradle.index("def releaseSigningConfigured =")
        configs = gradle.index("    signingConfigs {")
        build_types = gradle.index("    buildTypes {")
        self.assertLess(declaration, configs)
        self.assertLess(configs, build_types)
        self.assertLess(declaration, gradle.index("if (releaseSigningConfigured)"))
        self.assertIn("if (releaseSigningConfigured) {\n"
                      "                signingConfig signingConfigs.release", gradle)

    def run_keystore_step(self, value):
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Install release signing keystore\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1]
                                 .split("      - name:", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                ["bash", "-c", script], cwd=directory,
                env={**os.environ, "ANDROID_RELEASE_KEYSTORE": value},
                text=True, capture_output=True)
            path = Path(directory) / "signing/OfflineOTP-release-keystore.jks"
            return result, path.read_bytes() if path.exists() else None

    def test_missing_secret_reports_error(self):
        result, _ = self.run_keystore_step("")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("::error::ANDROID_RELEASE_KEYSTORE is not configured", result.stdout)

    def test_invalid_base64_reports_error(self):
        result, _ = self.run_keystore_step("not-base64!")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("is not valid base64", result.stdout)

    def test_empty_decoded_keystore_reports_error(self):
        result, _ = self.run_keystore_step("\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("decoded release keystore is empty", result.stdout)

    def test_base64_keystore_is_written_without_logging_it(self):
        # This only tests decoding, not JKS validity (apksigner/Gradle do that).
        payload = b"test keystore bytes"
        encoded = base64.b64encode(payload).decode("ascii")
        result, decoded = self.run_keystore_step(encoded)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(payload, decoded)
        self.assertNotIn(encoded, result.stdout + result.stderr)
        self.assertNotIn(payload.decode(), result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
