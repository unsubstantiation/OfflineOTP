"""Keep the iOS client plan honest about the formats it promises to implement.

`docs/ios-client-plan.md` is a contract: a Swift client is only safe if it writes the same bytes the
Android and desktop clients write. These guards fail when the document's numbers drift from `:core`,
when the fixed-vector corpus it names disappears, and when the plan is left claiming a build that
does not exist (or missing one that does).
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
PLAN = (ROOT / "docs/ios-client-plan.md").read_text(encoding="utf-8")
GOLDEN = ROOT / "desktop/src/test/resources/golden"


def constant(relative, name):
    """Read one `const val NAME = value` out of a Kotlin source file."""
    import re
    text = (CORE / relative).read_text(encoding="utf-8")
    match = re.search(rf"const val {name}\s*=\s*([0-9_]+)", text)
    if match is None:
        raise AssertionError(f"{name} not found in {relative}")
    return int(match.group(1).replace("_", ""))


class IosPlanTest(unittest.TestCase):
    def test_the_plan_is_linked_from_the_documentation_index(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("docs/ios-client-plan.md", readme)

    def test_vault_parameters_match_the_shared_core(self):
        constants = (CORE / "Utilities/Constants.kt").read_text(encoding="utf-8")
        self.assertIn('const val KDF_ARGON2ID = "argon2id"', constants)
        self.assertIn('const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA1"', constants)
        for promised, name in ((32, "ENCRYPTION_KEY_LENGTH"), (64, "ARGON2_AUTH_HASH_LENGTH"),
                               (65536, "ARGON2_MEMORY_KIB"), (3, "ARGON2_ITERATIONS"),
                               (4, "ARGON2_PARALLELISM"), (16, "ARGON2_SALT_LENGTH"),
                               (256, "PBKDF2_LENGTH"), (16, "PBKDF2_SALT_LENGTH"),
                               (12, "ENCRYPTION_IV_LENGTH")):
            self.assertEqual(promised, constant("Utilities/Constants.kt", name))
        # The document quotes both the Argon2id writer profile and the legacy PBKDF2 reader bounds.
        self.assertEqual(30000, constant("Utilities/Constants.kt", "PBKDF2_MIN_AUTH_ITERATIONS"))
        for value in ("Argon2id", "65536", "512-bit", "AES-256-GCM", "30000", "20000000",
                      "256-bit", "16-byte", "128-bit", "96-bit", "PBKDF2-HMAC-**SHA1**"):
            self.assertIn(value, PLAN, f"the plan must state {value}")

    def test_vault_framing_matches_the_writer(self):
        # `nonce ‖ ciphertext ‖ tag`, key = lower 32-byte half, verifier = upper 32-byte half.
        self.assertIn("first 32 bytes", PLAN)
        self.assertIn("last 32 bytes", PLAN)
        self.assertIn("nonce (12 bytes) ‖ ciphertext ‖ tag (16 bytes)", PLAN)
        # And the settings keys the plan tells a reader to look for really exist.
        settings = (CORE / "Utilities/VaultStoragePorts.kt").read_text(encoding="utf-8")
        for line in ('const val AUTH_METHOD = "auth_method"',
                     'const val ENCRYPTION = "encryption"',
                     'const val AUTH_KDF = "auth_kdf"',
                     'const val AUTH_CREDENTIALS = "auth_credentials"',
                     'const val AUTH_SALT = "auth_salt"',
                     'const val AUTH_ITERATIONS = "auth_iterations"',
                     'const val AUTH_MEMORY_KIB = "auth_memory_kib"',
                     'const val AUTH_PARALLELISM = "auth_parallelism"'):
            self.assertIn(line, settings)
        for key in ("auth_method", "encryption", "auth_kdf", "auth_salt", "auth_memory_kib",
                    "auth_iterations", "auth_parallelism", "auth_credentials"):
            self.assertIn(key, PLAN, f"the plan must name the settings key {key}")

    def test_backup_framing_matches_the_codec(self):
        header = (CORE / "Utilities/EncryptedBackupHeader.kt").read_text(encoding="utf-8")
        for name, value in (("ITERATION_BYTES", 4), ("SALT_BYTES", 12),
                            ("ARGON2_SALT_BYTES", 16), ("NONCE_BYTES", 12), ("TAG_BYTES", 16)):
            self.assertIn(f"const val {name} = {value}", header)
        self.assertIn("const val ARGON2_HEADER_BYTES = 4 + 1 + 4 + 4 + 1 + ARGON2_SALT_BYTES",
                      header)
        self.assertEqual(1_000_000, constant("Utilities/EncryptedBackupHeader.kt", "MAX_ITERATIONS"))
        self.assertEqual(140000, constant("Utilities/Constants.kt", "PBKDF2_MIN_BACKUP_ITERATIONS"))
        self.assertEqual(160000, constant("Utilities/Constants.kt", "PBKDF2_MAX_BACKUP_ITERATIONS"))
        for value in ("OOTP", "30-byte KDF header", "16-byte salt",
                      "4-byte big-endian iterations", "12-byte salt", "12-byte nonce",
                      "140000", "160000", "1000000", "all 32 bytes"):
            self.assertIn(value, PLAN, f"the plan must state {value}")

    def test_bounds_match_input_policy(self):
        policy = (CORE / "Utilities/InputPolicy.kt").read_text(encoding="utf-8")
        for name, value in (("MAX_ENTRIES", 2000), ("MAX_NOTE_CHARACTERS", 200),
                            ("MAX_SECRET_BYTES", 2048), ("MAX_TEXT_CHARS", 1024),
                            ("MAX_TAGS", 32), ("MAX_TAG_CHARS", 128),
                            ("MAX_PERIOD_SECONDS", 86400)):
            self.assertIn(f"const val {name} = {value}", policy)
        self.assertEqual(2000, constant("Utilities/InputPolicy.kt", "MAX_ENTRIES"))
        self.assertEqual(200, constant("Utilities/InputPolicy.kt", "MAX_NOTE_CHARACTERS"))
        # The table repeats the numbers a Swift validator has to enforce.
        for value in ("2000", "200 Unicode code points", "2048 bytes", "1024 characters",
                      "32 tags", "86400", "1 – 9"):
            self.assertIn(value, PLAN, f"the plan must state {value}")

    def test_token_rules_match_the_calculator(self):
        calculator = (CORE / "Utilities/TokenCalculator.kt").read_text(encoding="utf-8")
        self.assertIn("const val STEAM_DEFAULT_DIGITS = 5", calculator)
        self.assertIn("const val MOTP_STEP_SECONDS = 10", calculator)
        # The Steam alphabet is fixed; a Swift implementation copies it character for character.
        alphabet = "23456789BCDFGHJKMNPQRTVWXY"
        self.assertEqual(26, len(alphabet))
        for char in alphabet:
            self.assertIn(f"'{char}'", calculator)
        self.assertIn(alphabet, PLAN)
        # An mOTP entry without a PIN shows a placeholder instead of a code.
        entry = (CORE / "Database/Entry.kt").read_text(encoding="utf-8")
        self.assertIn('const val MOTP_NO_PIN_CODE = "PINREQ"', entry)
        self.assertIn("PINREQ", PLAN)
        # mOTP is MD5 of the 10-second step, the seed and the PIN, first six hex digits.
        self.assertIn('MessageDigest.getInstance("MD5")', calculator)
        self.assertIn("first 6 characters", PLAN)

    def test_the_conformance_corpus_it_names_exists(self):
        for name in ("argon2id-secrets.dat", "argon2id-vault.properties",
                     "argon2id-otp_accounts.json.aes",
                     "secrets.dat", "vault.properties", "expected-vault.json",
                     "otp_accounts.json.aes", "desktop-note-export.json.aes",
                     "desktop-ssh-export.json.aes", "desktop-privacy-export.json.aes"):
            self.assertTrue((GOLDEN / name).is_file(), f"missing fixture {name}")
            self.assertIn(name, PLAN, f"the plan must name the fixture {name}")
        # The fixtures are only interchangeable because they share one throwaway password.
        self.assertIn("golden-interop-password", PLAN)
        self.assertIn("golden-interop-password", (GOLDEN / "README.md").read_text(encoding="utf-8"))

    def test_no_ios_build_is_claimed_before_one_exists(self):
        """A workflow with no project to build is coverage that does not exist."""
        project = ROOT / "ios"
        workflow = ROOT / ".github/workflows/ios.yml"
        if project.is_dir():
            self.assertTrue(workflow.is_file(),
                            "an ios/ project exists, so .github/workflows/ios.yml must build it")
        else:
            self.assertFalse(workflow.is_file(),
                             "do not add an ios workflow before there is a project to build")
            self.assertIn("plan only", PLAN.lower())
            self.assertIn("M0", PLAN)

    def test_the_plan_states_the_data_loss_rule(self):
        """An unsupported kind must be preserved, not dropped, on both read and write."""
        self.assertIn("preserved verbatim", PLAN)
        for kind in ("PASSWORD", "PASSWORD_ARCHIVE", "EMAIL"):
            self.assertIn(f"`{kind}`", PLAN)


if __name__ == "__main__":
    unittest.main()
