"""Guard signing configuration order and exercise CI's keystore gating/diagnostics.

Release signing is opt-in: without the ``ANDROID_RELEASE_KEYSTORE`` secret the signed-release
job must be skipped (green build), while a malformed secret that does reach the install step
must still fail closed.
"""
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

    def step_script(self, step_name):
        """Return the dedented `run:` block of one workflow step.

        The block scalar body is indented ten spaces, so the first line indented less than that
        ends the script; this works for the last step of a job as well as the first one.
        """
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        step = workflow.split(f"      - name: {step_name}\n", 1)[1]
        body = []
        for line in step.split("        run: |\n", 1)[1].splitlines():
            if line.strip() and not line.startswith(" " * 10):
                break
            body.append(line)
        return textwrap.dedent("\n".join(body)).strip("\n") + "\n"

    def run_keystore_step(self, value):
        script = self.step_script("Install release signing keystore")
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                ["bash", "-c", script], cwd=directory,
                env={**os.environ, "ANDROID_RELEASE_KEYSTORE": value},
                text=True, capture_output=True)
            path = Path(directory) / "signing/OfflineOTP-release-keystore.jks"
            return result, path.read_bytes() if path.exists() else None

    def run_preflight_step(self, value):
        """Run CI's preflight check and return (process, parsed GITHUB_OUTPUT)."""
        script = self.step_script("Check release signing secret")
        with tempfile.TemporaryDirectory() as directory:
            output_file = Path(directory) / "github_output"
            output_file.write_text("", encoding="utf-8")
            result = subprocess.run(
                ["bash", "-c", script], cwd=directory,
                env={**os.environ, "ANDROID_RELEASE_KEYSTORE": value,
                     "GITHUB_OUTPUT": str(output_file)},
                text=True, capture_output=True)
            outputs = dict(line.split("=", 1) for line in
                           output_file.read_text(encoding="utf-8").splitlines() if "=" in line)
            return result, outputs

    def test_missing_secret_skips_signed_release_job(self):
        """An unconfigured keystore must skip signing, not fail the whole build.

        The regression this guards: pushes to master went red in ~0s at the keystore install
        step because the release secret was never configured.
        """
        result, outputs = self.run_preflight_step("")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", outputs.get("enabled"))
        self.assertIn("::notice::ANDROID_RELEASE_KEYSTORE is not configured", result.stdout)
        self.assertIn("docs/release-signing.md", result.stdout)

    def test_configured_secret_enables_signed_release_job(self):
        encoded = base64.b64encode(b"keystore bytes").decode("ascii")
        result, outputs = self.run_preflight_step(encoded)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", outputs.get("enabled"))
        self.assertNotIn(encoded, result.stdout + result.stderr)
        self.assertNotIn("::error::", result.stdout)

    def test_preflight_gates_the_signed_release_job(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        self.assertIn("      enabled: ${{ steps.check.outputs.enabled }}\n", workflow)
        self.assertIn("    needs: signing-preflight\n"
                      "    if: needs.signing-preflight.outputs.enabled == 'true'\n", workflow)
        # No job may build/sign release APKs without the keystore; only the debug job is
        # unconditional, and leaking the secret into a log is still forbidden.
        self.assertNotIn("sign-release:\n    if: github.event_name != 'pull_request'\n", workflow)
        self.assertEqual(1, workflow.count("assembleFdroidRelease"))

    def test_a_keystore_without_credentials_fails_before_signing(self):
        """Gradle must name the missing property, not fail later inside apksigner."""
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertIn("throw new GradleException(", gradle)
        for name in ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD"):
            self.assertIn(f"'{name}'", gradle)
        # Validation runs only when a keystore was supplied, so unsigned builds stay unchanged.
        validation = gradle.index("must be set (and non-empty)")
        self.assertLess(gradle.index("def releaseSigningConfigured ="), validation)
        self.assertLess(validation, gradle.index("    signingConfigs {"))
        # The guard is the same opt-in flag, so an unsigned build never runs the check.
        self.assertLess(validation, gradle.index("    signingConfigs {"))
        between = gradle[gradle.index("def releaseSigningConfigured ="):validation]
        self.assertIn("if (releaseSigningConfigured) {", between)

    def test_secrets_are_never_interpolated_into_a_step_script(self):
        """A secret must reach a step through `env:`, never as text inside the shell script.

        Substituting ``${{ secrets.X }}`` into a ``run:`` block pastes the value straight into a
        shell script, so a password containing a quote, a backslash or ``$`` breaks the command
        (and lets shell syntax in a value run). The ``env:`` context has neither problem.
        """
        for step in ("Install release signing keystore",
                     "Check the rest of the release signing inputs",
                     "Build signed release APKs",
                     "Verify APK signatures",
                     "Prepare signed APKs and checksums"):
            self.assertNotIn("${{ secrets.", self.step_script(step),
                             f"{step} must read its secret from env, not inline")

    def test_build_step_passes_credentials_through_gradle_environment_properties(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        script = self.step_script("Build signed release APKs")
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        for name in ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"):
            # The env name maps onto the exact property Gradle reads, and never onto argv.
            self.assertIn(f"ORG_GRADLE_PROJECT_{name}: ${{{{ secrets.", workflow)
            self.assertIn(name, gradle)
            self.assertNotIn(f"-P{name}=", script,
                             f"{name} must not be passed on the command line (visible in ps)")
        self.assertIn('project.properties[\'RELEASE_STORE_PASSWORD\']', gradle)
        self.assertIn('project.properties[\'RELEASE_KEY_PASSWORD\']', gradle)
        self.assertIn("project.property('RELEASE_KEY_ALIAS')", gradle)
        # The keystore path is not a secret, so it stays an ordinary build argument.
        self.assertIn('-PRELEASE_STORE_FILE="$PWD/signing/OfflineOTP-release-keystore.jks"', script)

    def test_a_missing_password_names_the_secret_and_fails(self):
        """A present keystore with a missing password must name the secret, not fail in Gradle."""
        script = self.step_script("Check the rest of the release signing inputs")
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                ["bash", "-c", script], cwd=directory,
                env={**os.environ, "STORE_PASSWORD": "", "KEY_ALIAS": "offlineotp",
                     "KEY_PASSWORD": "hunter2"},
                text=True, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("::error::ANDROID_RELEASE_STORE_PASSWORD is not configured", result.stdout)
        self.assertNotIn("hunter2", result.stdout + result.stderr)

    def test_complete_credentials_pass_the_check_silently(self):
        script = self.step_script("Check the rest of the release signing inputs")
        values = {"STORE_PASSWORD": "p@ss word", "KEY_ALIAS": "offlineotp",
                  "KEY_PASSWORD": 'q"uote$(id)`tick`'}
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                ["bash", "-c", script], cwd=directory,
                env={**os.environ, **values}, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        for value in values.values():
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_the_decoded_keystore_does_not_outlive_the_job(self):
        script = self.step_script("Remove the decoded keystore")
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "signing" / "OfflineOTP-release-keystore.jks"
            keystore.parent.mkdir(parents=True)
            keystore.write_bytes(b"keystore bytes")
            result = subprocess.run(["bash", "-c", script], cwd=directory,
                                    text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(keystore.exists())
            self.assertFalse(keystore.parent.exists())

    def test_signing_document_names_every_required_secret(self):
        document = (ROOT / "docs/release-signing.md").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        secrets = {name for name in ("ANDROID_RELEASE_KEYSTORE", "ANDROID_RELEASE_STORE_PASSWORD",
                                     "ANDROID_RELEASE_KEY_ALIAS", "ANDROID_RELEASE_KEY_PASSWORD")}
        for name in secrets:
            self.assertIn(name, workflow)
            self.assertIn(name, document)
        self.assertIn("docs/release-signing.md", (ROOT / "README.md").read_text(encoding="utf-8"))

    def test_install_step_still_fails_closed_on_bad_payloads(self):
        """Reaching the install step means a secret exists, so malformed input is an error."""
        result, path = self.run_keystore_step("")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(path)
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
