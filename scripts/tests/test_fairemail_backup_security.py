"""Guard the narrow FairEmail v0 cryptographic compatibility exception.

The upstream FairEmail writer still emits headerless v0 AES-CBC/PKCS5Padding. OfflineOTP must keep
reading it, but the CodeQL suppression must not spread to the authenticated v1 path or to new formats.
These are source guards; the JVM vectors in EmailMaterialTest exercise the actual bytes.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/EmailMaterial.kt"
TEST = ROOT / "core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/EmailMaterialTest.kt"
DOC = ROOT / "docs/vault-security-hardening.md"
UPSTREAM_COMMIT = "13403d91902ded9ee4c586ceb602e2332c469373"


class FairEmailBackupSecurityTest(unittest.TestCase):
    def test_legacy_cbc_exception_is_local_and_read_only(self):
        material = CORE.read_text(encoding="utf-8")
        self.assertEqual(1, material.count("AES/CBC/PKCS5Padding"),
                         "the legacy CBC transformation must live in one isolated factory")
        self.assertIn("private fun legacyFairEmailCipher(): Cipher", material)
        self.assertIn("if (version == 0) legacyFairEmailCipher() else Cipher.getInstance(\"AES/GCM/NoPadding\")",
                      material)
        helper = re.search(
            r"private fun legacyFairEmailCipher\(\): Cipher \{(.*?)\n    \}",
            material, re.S)
        self.assertIsNotNone(helper, "legacy cipher factory missing")
        body = helper.group(1)
        self.assertIn("// codeql[java/weak-cryptographic-algorithm]", body)
        self.assertIn('return Cipher.getInstance("AES/CBC/PKCS5Padding")', body)
        self.assertNotIn("Cipher.ENCRYPT_MODE", body)
        self.assertNotIn("AES/GCM", body)
        self.assertEqual(1, material.count("codeql[java/weak-cryptographic-algorithm]"),
                         "do not broaden the suppression beyond the legacy factory")

    def test_authenticated_v1_path_and_failure_handling_remain(self):
        material = CORE.read_text(encoding="utf-8")
        self.assertIn('Cipher.getInstance("AES/GCM/NoPadding")', material)
        self.assertIn("GCMParameterSpec(128, iv)", material)
        self.assertIn('require(version == 1) { "Unsupported FairEmail backup version" }', material)
        self.assertIn('throw IllegalArgumentException("Could not decrypt FairEmail backup", invalid)', material)
        self.assertIn("spec.clearPassword()", material)
        self.assertIn("derived?.fill(0)", material)

    def test_regression_vectors_and_documented_residual_risk_exist(self):
        tests = TEST.read_text(encoding="utf-8")
        for name in (
            "encryptedFairEmailV0AndV1ExportsCanBeImported",
            "fairEmailV0UpstreamWireFormatStillImports",
            "fairEmailV1RejectsWrongPasswordTamperingAndUnsupportedFraming",
        ):
            self.assertIn(f"fun {name}(", tests)
        doc = DOC.read_text(encoding="utf-8")
        self.assertIn("## FairEmail backup compatibility exception", doc)
        self.assertIn(UPSTREAM_COMMIT, doc)
        self.assertIn("FairEmail v0", doc)
        self.assertIn("**None**", doc)
        self.assertIn("AES-256-GCM", doc)
        self.assertIn("malleable", doc)
        self.assertIn("Retire the exception only after upstream stops writing v0", doc)


if __name__ == "__main__":
    unittest.main()
