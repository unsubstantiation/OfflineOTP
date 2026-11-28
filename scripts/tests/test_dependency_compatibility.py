"""Regression guards for coordinated pins; these do not replace real platform builds."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DependencyCompatibilityTest(unittest.TestCase):
    def test_kotlin_gradle_agp_stay_within_documented_supported_range(self):
        root = (ROOT / "build.gradle").read_text()
        self.assertIn("kotlin-gradle-plugin:2.4.21", root)
        self.assertIn("com.android.tools.build:gradle:9.3.1", root)
        self.assertIn("gradle-9.7.0-all.zip", (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text())

    def test_actual_compose_libraries_are_pinned_not_just_the_plugin(self):
        for module in ("ui", "ui-foundation"):
            text = (ROOT / module / "build.gradle").read_text()
            for coordinate in ("runtime:runtime:1.12.2", "foundation:foundation:1.12.2",
                               "ui:ui:1.12.2", "material3:material3:1.12.0-alpha03"):
                self.assertIn("org.jetbrains.compose." + coordinate, text)
            self.assertNotIn("implementation compose.", text)
        desktop = (ROOT / "desktop-compose/build.gradle").read_text()
        self.assertIn("implementation(compose.desktop.currentOs) { version { strictly '1.12.2' } }", desktop)

    def test_wrong_bindgen_version_is_rejected_before_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            generator = Path(directory) / "uniffi-bindgen"
            generator.write_text("#!/bin/sh\nif [ \"$1\" = --version ]; then echo 'uniffi-bindgen 0.28.3'; exit 0; fi\nexit 99\n")
            generator.chmod(0o755)
            result = subprocess.run(["bash", str(ROOT / "scripts/openpgp/generate-bindings.sh"), "--check"],
                                    env={**os.environ, "UNIFFI_BINDGEN": str(generator)},
                                    text=True, capture_output=True)
            self.assertEqual(2, result.returncode)
            self.assertIn("must match the pinned runtime", result.stderr)

    def test_uniffi_runtime_generator_and_generated_contract_stay_aligned(self):
        manifest = (ROOT / "rust/offlineotp-openpgp/Cargo.toml").read_text()
        self.assertIn('uniffi = "=0.31.0"', manifest)
        self.assertIn('uniffi = { version = "=0.31.0", features = ["build"] }', manifest)
        generator = (ROOT / "scripts/openpgp/generate-bindings.sh").read_text()
        self.assertIn('!= "uniffi-bindgen 0.31.0"', generator)
        self.assertIn('--metadata-no-deps', generator)
        # UniFFI v0.31.0's contract version is 30, not its package minor version.
        for name in ('rust/bindings/kotlin/uniffi/offlineotp_openpgp/offlineotp_openpgp.kt',
                     'ios/Sources/offlineotp_openpgp/offlineotp_openpgp.swift'):
            self.assertIn('bindings_contract_version = 30', (ROOT / name).read_text())

    def test_apple_default_toolchain_supports_the_pinned_packages(self):
        text = (ROOT / ".github/workflows/ios.yml").read_text()
        self.assertIn("inputs.runner || 'macos-26'", text)
        self.assertIn("< (6, 2)", text)
        project = (ROOT / "ios/project.yml").read_text()
        self.assertIn("c7413d967b97aae67c46452bf7645bdb7b200e8e", project)  # SwiftLog 1.17.2
