"""Guard signing configuration order and exercise CI's keystore gating/diagnostics.

Release signing is opt-in: without the keystore secret the signed-release job must be skipped
(green build), while a malformed or incomplete configuration that does reach a step must still fail
closed and name the thing that is missing.

The repository keeps its signing credentials in the ``android-release`` environment as
``ANDROID_KEYSTORE_BASE64`` and ``ANDROID_KEYSTORE_PASSWORD``; the older ``ANDROID_RELEASE_*``
pieces are still honoured, and the key alias is read out of the keystore itself.
"""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/android.yml"
ENVIRONMENT = "android-release"


class ReleaseSigningTest(unittest.TestCase):
    def workflow_text(self):
        return WORKFLOW.read_text(encoding="utf-8")

    def step_script(self, step_name):
        """Return the dedented `run:` block of one workflow step.

        The block scalar body is indented ten spaces, so the first line indented less than that
        ends the script; this works for the last step of a job as well as the first one.
        """
        workflow = self.workflow_text()
        step = workflow.split(f"      - name: {step_name}\n", 1)[1]
        body = []
        for line in step.split("        run: |\n", 1)[1].splitlines():
            if line.strip() and not line.startswith(" " * 10):
                break
            body.append(line)
        return textwrap.dedent("\n".join(body)).strip("\n") + "\n"

    def job_block(self, name):
        """Return one job's lines: from its two-space key to the next one."""
        lines = self.workflow_text().splitlines()
        start = lines.index(f"  {name}")
        end = len(lines)
        for index in range(start + 1, len(lines)):
            line = lines[index]
            # A job key is the only thing indented exactly two spaces; bodies go deeper.
            if line.strip() and line.startswith("  ") and not line.startswith("   "):
                end = index
                break
        return "\n".join(lines[start:end]) + "\n"

    def run_step(self, step_name, env, directory=None, path_prefix=None):
        """Run one workflow step's script in a scratch directory."""
        script = self.step_script(step_name)
        directory = directory or tempfile.mkdtemp()
        output_file = Path(directory) / "github_output"
        output_file.write_text("", encoding="utf-8")
        environment = {**os.environ, **env, "GITHUB_OUTPUT": str(output_file),
                       "GITHUB_ENV": str(Path(directory) / "github_env")}
        Path(directory, "github_env").write_text("", encoding="utf-8")
        if path_prefix:
            environment["PATH"] = f"{path_prefix}{os.pathsep}{environment['PATH']}"
        result = subprocess.run(["bash", "-c", script], cwd=directory, env=environment,
                                text=True, capture_output=True)
        outputs = dict(line.split("=", 1) for line in
                       output_file.read_text(encoding="utf-8").splitlines() if "=" in line)
        written = dict(line.split("=", 1) for line in
                       Path(directory, "github_env").read_text(encoding="utf-8").splitlines()
                       if "=" in line)
        return result, outputs, written

    # -- configuration shape ----------------------------------------------------------------

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
                      "                signingConfig = signingConfigs.release", gradle)

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
        between = gradle[gradle.index("def releaseSigningConfigured ="):validation]
        self.assertIn("if (releaseSigningConfigured) {", between)

    def test_credentials_live_in_the_android_release_environment(self):
        """Signing secrets live in the `android-release` GitHub Environment."""
        workflow = self.workflow_text()
        for job in ("signing-preflight:", "sign-release:"):
            self.assertIn(f"    environment: {ENVIRONMENT}\n", self.job_block(job),
                          f"{job} must declare the {ENVIRONMENT} environment so signing secrets are visible")
        # The environment allows master only, so non-master dispatches report a skip, not a gate
        # failure they cannot explain, and pull requests do not trigger the workflow at all.
        self.assertNotIn("pull_request", workflow)
        self.assertIn("if: github.ref == 'refs/heads/master'\n", workflow)

    def test_the_repository_secret_names_are_accepted_with_the_old_ones_as_fallback(self):
        workflow = self.workflow_text()
        self.assertIn("${{ secrets.ANDROID_KEYSTORE_BASE64 || secrets.ANDROID_RELEASE_KEYSTORE }}",
                      workflow)
        self.assertIn("${{ secrets.ANDROID_KEYSTORE_PASSWORD || secrets.ANDROID_RELEASE_STORE_PASSWORD }}",
                      workflow)
        # A distinct key password still wins when it is set; otherwise the store password covers it.
        self.assertIn("${{ secrets.ANDROID_RELEASE_KEY_PASSWORD || secrets.ANDROID_KEYSTORE_PASSWORD "
                      "|| secrets.ANDROID_RELEASE_STORE_PASSWORD }}", workflow)

    def test_the_key_alias_comes_from_the_keystore(self):
        """Two secrets are enough: the alias is read from the keystore, not asked for."""
        script = self.step_script("Find the key alias and the passwords")
        self.assertIn("keytool -list -keystore signing/OfflineOTP-release-keystore.jks", script)
        self.assertIn("/PrivateKeyEntry/ { print $1 }", script)
        # An explicit alias still wins, and an ambiguous keystore is refused rather than guessed.
        self.assertIn("ANDROID_RELEASE_KEY_ALIAS", self.workflow_text())
        self.assertIn("holds $COUNT private keys", script)
        self.assertIn("holds no private key", script)

    def test_the_keystore_password_never_reaches_the_command_line(self):
        """`keytool -storepass <value>` would show the password in `ps`; -storepass:env does not."""
        script = self.step_script("Find the key alias and the passwords")
        self.assertIn("-storepass:env STORE_PASSWORD", script)
        self.assertNotIn('-storepass "$', script)

    def test_preflight_gates_the_signed_release_job(self):
        workflow = self.workflow_text()
        self.assertIn("      enabled: ${{ steps.check.outputs.enabled }}\n", workflow)
        self.assertIn("    needs: [signing-preflight, openpgp]\n"
                      "    if: needs.signing-preflight.outputs.enabled == 'true'\n", workflow)
        # No job may build/sign release APKs without the keystore preflight gate.
        self.assertNotIn("sign-release:\n    if: github.ref == 'refs/heads/master'\n", workflow)
        self.assertEqual(1, workflow.count("assembleFdroidRelease"))

    # -- preflight --------------------------------------------------------------------------

    def test_missing_secret_skips_signed_release_job(self):
        """An unconfigured keystore must skip signing, not fail the whole build.

        The regression this guards: pushes to master went red in ~0s at the keystore install
        step because the release secret was never configured.
        """
        result, outputs, _ = self.run_step("Check release signing secret", {"KEYSTORE_BASE64": ""})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", outputs.get("enabled"))
        self.assertIn("::notice::ANDROID_KEYSTORE_BASE64 is not configured", result.stdout)
        self.assertIn("docs/release-signing.md", result.stdout)

    def test_configured_secret_enables_signed_release_job(self):
        encoded = base64.b64encode(b"keystore bytes").decode("ascii")
        result, outputs, _ = self.run_step("Check release signing secret",
                                           {"KEYSTORE_BASE64": encoded})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", outputs.get("enabled"))
        self.assertNotIn(encoded, result.stdout + result.stderr)
        self.assertNotIn("::error::", result.stdout)

    # -- keystore install -------------------------------------------------------------------

    def run_keystore_step(self, value):
        with tempfile.TemporaryDirectory() as directory:
            result, _, _ = self.run_step("Install release signing keystore",
                                         {"KEYSTORE_BASE64": value}, directory=directory)
            path = Path(directory) / "signing/OfflineOTP-release-keystore.jks"
            return result, path.read_bytes() if path.exists() else None

    def test_install_step_still_fails_closed_on_bad_payloads(self):
        """Reaching the install step means a secret exists, so malformed input is an error."""
        result, path = self.run_keystore_step("")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(path)
        self.assertIn("::error::ANDROID_KEYSTORE_BASE64 is not configured", result.stdout)

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

    # -- alias and passwords ----------------------------------------------------------------

    def alias_step(self, env, keytool_output=None, keytool_status=0):
        """Run the alias step, optionally with a stub `keytool` on PATH.

        The stub lets the parsing and the single-alias rule be exercised without a real JKS.
        """
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "signing/OfflineOTP-release-keystore.jks"
            keystore.parent.mkdir(parents=True)
            keystore.write_bytes(b"keystore bytes")
            prefix = None
            if keytool_output is not None:
                prefix = Path(directory) / "bin"
                prefix.mkdir()
                stub = prefix / "keytool"
                stub.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" > keytool-argv\n"
                                "cat <<'EOF'\n" + keytool_output + "\nEOF\n"
                                f"exit {keytool_status}\n", encoding="utf-8")
                stub.chmod(0o755)
            result, _, written = self.run_step("Find the key alias and the passwords", env,
                                               directory=directory, path_prefix=str(prefix))
            return result, written

    def test_a_missing_password_names_the_secret_and_fails(self):
        """A present keystore with a missing password must name the secret, not fail in Gradle."""
        result, written = self.alias_step({"STORE_PASSWORD": "", "KEY_PASSWORD": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("::error::ANDROID_KEYSTORE_PASSWORD is not configured", result.stdout)
        self.assertEqual({}, written)

    def test_an_explicit_alias_needs_no_keytool(self):
        result, written = self.alias_step({"STORE_PASSWORD": "p@ss word",
                                           "KEY_PASSWORD": 'q"uote$(id)`tick`',
                                           "KEY_ALIAS": "offlineotp"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"RELEASE_KEY_ALIAS": "offlineotp",
                          "RELEASE_STORE_PASSWORD": "p@ss word",
                          "RELEASE_KEY_PASSWORD": 'q"uote$(id)`tick`'}, written)
        for value in ("p@ss word", 'q"uote$(id)`tick`'):
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_the_only_private_key_in_the_keystore_becomes_the_alias(self):
        listing = ("Keystore type: JKS\n"
                   "offlineotp, Nov 4, 2026, PrivateKeyEntry, \n"
                   "Certificate fingerprint (SHA-256): 00:11\n")
        result, written = self.alias_step({"STORE_PASSWORD": "secret", "KEY_PASSWORD": "secret",
                                           "KEY_ALIAS": ""}, keytool_output=listing)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("offlineotp", written.get("RELEASE_KEY_ALIAS"))
        self.assertIn("Using key alias 'offlineotp'", result.stdout)

    def test_a_keystore_with_several_keys_is_refused_instead_of_guessed(self):
        listing = ("first, Nov 4, 2026, PrivateKeyEntry, \n"
                   "second, Nov 4, 2026, PrivateKeyEntry, \n")
        result, written = self.alias_step({"STORE_PASSWORD": "secret", "KEY_PASSWORD": "secret",
                                           "KEY_ALIAS": ""}, keytool_output=listing)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("holds 2 private keys", result.stdout)
        self.assertIn("ANDROID_RELEASE_KEY_ALIAS", result.stdout)
        self.assertEqual({}, written)

    def test_a_keystore_without_a_signing_key_is_reported(self):
        result, written = self.alias_step({"STORE_PASSWORD": "wrong", "KEY_PASSWORD": "wrong",
                                           "KEY_ALIAS": ""}, keytool_output="")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("holds no private key", result.stdout)
        self.assertEqual({}, written)

    def test_a_keystore_keytool_cannot_open_names_the_likely_cause(self):
        """A wrong password must say so, instead of silently reporting an empty keystore."""
        result, written = self.alias_step({"STORE_PASSWORD": "wrong", "KEY_PASSWORD": "wrong",
                                           "KEY_ALIAS": ""},
                                          keytool_output="keytool error: bad password",
                                          keytool_status=1)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("keytool could not open the release keystore", result.stdout)
        self.assertIn("ANDROID_KEYSTORE_PASSWORD is wrong", result.stdout)
        self.assertNotIn("wrong", result.stdout.replace("ANDROID_KEYSTORE_PASSWORD is wrong", ""))
        self.assertEqual({}, written)

    # -- build step -------------------------------------------------------------------------

    def test_secrets_are_never_interpolated_into_the_scripts_that_print(self):
        """A secret must reach a step through `env:`, never as text inside the shell script.

        Substituting ``${{ secrets.X }}`` into a ``run:`` block pastes the value straight into a
        shell script, so a password containing a quote, a backslash or ``$`` breaks the command
        (and lets shell syntax in a value run). The ``env:`` context has neither problem.
        """
        for step in ("Check release signing secret",
                     "Install release signing keystore",
                     "Find the key alias and the passwords",
                     "Build signed release APKs",
                     "Verify APK signatures",
                     "Prepare signed APKs and checksums"):
            self.assertNotIn("${{ secrets.", self.step_script(step),
                             f"{step} must read its secret from env, not inline")

    def test_build_step_hands_credentials_to_gradle_through_the_environment(self):
        workflow = self.workflow_text()
        script = self.step_script("Build signed release APKs")
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        for name in ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"):
            # The exported name maps onto the exact property Gradle reads, and never onto argv.
            self.assertIn(f'export ORG_GRADLE_PROJECT_{name}="$', script)
            self.assertIn(name, gradle)
            self.assertNotIn(f"-P{name}=", script,
                             f"{name} must not be passed on the command line (visible in ps)")
        self.assertIn('project.properties[\'RELEASE_STORE_PASSWORD\']', gradle)
        self.assertIn('project.properties[\'RELEASE_KEY_PASSWORD\']', gradle)
        self.assertIn("project.property('RELEASE_KEY_ALIAS')", gradle)
        # The keystore path is not a secret, so it stays an ordinary build argument.
        self.assertIn('-PRELEASE_STORE_FILE="$PWD/signing/OfflineOTP-release-keystore.jks"', script)
        # The values arrive through $GITHUB_ENV, so no workflow-level secret name appears here.
        for value in ("RELEASE_KEY_ALIAS", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD"):
            self.assertIn(f': "${{{value}:?missing}}"', script)

    def test_the_alias_step_publishes_what_the_build_step_consumes(self):
        result, written = self.alias_step({"STORE_PASSWORD": "pw", "KEY_PASSWORD": "kp",
                                           "KEY_ALIAS": "offlineotp"})
        self.assertEqual(0, result.returncode, result.stderr)
        script = self.step_script("Build signed release APKs")
        for name in ("RELEASE_KEY_ALIAS", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD"):
            self.assertIn(name, written)
            self.assertIn(name, script)

    # -- cleanup and documentation -----------------------------------------------------------

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
        workflow = self.workflow_text()
        secrets = {"ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD",
                   "ANDROID_RELEASE_KEY_ALIAS", "ANDROID_RELEASE_KEY_PASSWORD"}
        for name in secrets:
            self.assertIn(name, workflow)
            self.assertIn(name, document)
        self.assertIn("docs/release-signing.md", (ROOT / "README.md").read_text(encoding="utf-8"))
        self.assertIn(ENVIRONMENT, document)
        self.assertIn(ENVIRONMENT, workflow)


if __name__ == "__main__":
    unittest.main()
