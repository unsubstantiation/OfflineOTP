"""Keep the Swift client's copy of the format honest.

The iOS package (`ios/`) has to reproduce bytes that `:core` produces. It cannot share that code, so
the numbers and the field order are restated in Swift — and every one of those restatements is checked
here against the Kotlin source it came from. A change to a bound, a limit, the stored field order or
the token alphabet on the JVM side fails this suite until the Swift side follows.
"""
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "ios"
SOURCES = IOS / "Sources/OfflineOTPKit"
TESTS = IOS / "Tests/OfflineOTPKitTests"
CORE = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"
GOLDEN = ROOT / "desktop/src/test/resources/golden"


def kotlin_const(relative, name):
    text = (CORE / relative).read_text(encoding="utf-8")
    match = re.search(rf"const val {name}\s*=\s*([0-9_]+)", text)
    if match is None:
        raise AssertionError(f"{name} not found in {relative}")
    return int(match.group(1).replace("_", ""))


def swift_sources():
    return {path.name: path.read_text(encoding="utf-8") for path in SOURCES.glob("**/*.swift")}


def swift_tests():
    return {path.name: path.read_text(encoding="utf-8") for path in TESTS.glob("*.swift")}


class IosPackageTest(unittest.TestCase):
    def test_the_package_declares_both_platforms_and_a_test_target(self):
        package = (IOS / "Package.swift").read_text(encoding="utf-8")
        self.assertIn("swift-tools-version", package)
        self.assertIn('.iOS(.v17)', package)
        self.assertIn('.macOS(.v14)', package)
        self.assertIn('.testTarget(name: "OfflineOTPKitTests"', package)
        # No app target: the UI arrives with M3, and a workflow that could only skip would look
        # like coverage that does not exist.
        self.assertNotIn(".executableTarget", package)

    def test_the_crypto_constants_match_the_shared_core(self):
        crypto = (SOURCES / "VaultCrypto.swift").read_text(encoding="utf-8")
        argon = (SOURCES / "Argon2id.swift").read_text(encoding="utf-8")
        expectations = {
            "derivedKeyBytes": 256 // 8,
            "vaultKeyBytes": kotlin_const("Utilities/Constants.kt", "ENCRYPTION_KEY_LENGTH"),
            "halfBytes": 16,
            "saltBytes": kotlin_const("Utilities/Constants.kt", "PBKDF2_SALT_LENGTH"),
            "nonceBytes": kotlin_const("Utilities/Constants.kt", "ENCRYPTION_IV_LENGTH"),
            "tagBytes": 128 // 8,
            "argon2MemoryKiB": kotlin_const("Utilities/Constants.kt", "ARGON2_MEMORY_KIB"),
            "argon2Iterations": kotlin_const("Utilities/Constants.kt", "ARGON2_ITERATIONS"),
            "argon2Parallelism": kotlin_const("Utilities/Constants.kt", "ARGON2_PARALLELISM"),
            "argon2SaltBytes": kotlin_const("Utilities/Constants.kt", "ARGON2_SALT_LENGTH"),
            "argon2AuthDerivedBytes": kotlin_const("Utilities/Constants.kt",
                                                   "ARGON2_AUTH_HASH_LENGTH"),
            "minAuthIterations": kotlin_const("Utilities/Constants.kt",
                                              "PBKDF2_MIN_AUTH_ITERATIONS"),
            "minBackupIterations": kotlin_const("Utilities/Constants.kt",
                                                "PBKDF2_MIN_BACKUP_ITERATIONS"),
            "maxBackupIterations": kotlin_const("Utilities/Constants.kt",
                                                "PBKDF2_MAX_BACKUP_ITERATIONS"),
        }
        for name, value in expectations.items():
            self.assertIn(f"static let {name} = {value}", crypto, f"{name} drifted from :core")
        self.assertIn("maxIterations = 20_000_000", crypto)
        self.assertIn("typeArgon2id: UInt32 = 2", argon)
        self.assertIn("version: UInt32 = 0x13", argon)
        self.assertIn("PBKDF2WithHmacSHA1".replace("PBKDF2WithHmacSHA1", "kCCPRFHmacAlgSHA1"), crypto)
        self.assertIn("CCKeyDerivationPBKDF", crypto)
        self.assertIn("AES.GCM", crypto)

    def test_the_backup_header_constants_match_the_codec(self):
        backup = (SOURCES / "EncryptedBackup.swift").read_text(encoding="utf-8")
        header = (CORE / "Utilities/EncryptedBackupHeader.kt").read_text(encoding="utf-8")
        for name, value in (("iterationBytes", 4), ("saltBytes", 12), ("argon2SaltBytes", 16)):
            self.assertIn(f"public static let {name} = {value}", backup)
        self.assertIn("public static let formatVersionArgon2id: UInt8 = 1", backup)
        self.assertIn("public static let minBytes = "
                      "iterationBytes + saltBytes + nonceBytes + tagBytes", backup)
        self.assertIn("public static let minArgon2Bytes = argon2HeaderBytes + nonceBytes + tagBytes",
                      backup)
        for line in ("const val ITERATION_BYTES = 4", "const val SALT_BYTES = 12",
                     "const val ARGON2_SALT_BYTES = 16",
                     "const val ARGON2_HEADER_BYTES = 4 + 1 + 4 + 4 + 1 + ARGON2_SALT_BYTES",
                     "const val NONCE_BYTES = 12", "const val TAG_BYTES = 16"):
            self.assertIn(line, header)
        self.assertEqual(1_000_000, kotlin_const("Utilities/EncryptedBackupHeader.kt",
                                                 "MAX_ITERATIONS"))
        self.assertIn("maxReadIterations = 1_000_000", backup)
        # Backup keys use the full 32-byte KDF output (Argon2id for new writes, PBKDF2 for legacy).
        self.assertIn("VaultCrypto.argon2id(", backup)
        self.assertIn("pbkdf2(password: password, salt: salt, iterations: iterations)", backup)

    def test_the_input_bounds_match_input_policy(self):
        policy = (SOURCES / "InputPolicy.swift").read_text(encoding="utf-8")
        expectations = {
            "maxEntries": kotlin_const("Utilities/InputPolicy.kt", "MAX_ENTRIES"),
            "maxJSONDepth": kotlin_const("Utilities/InputPolicy.kt", "MAX_JSON_DEPTH"),
            "maxTextChars": kotlin_const("Utilities/InputPolicy.kt", "MAX_TEXT_CHARS"),
            "maxTags": kotlin_const("Utilities/InputPolicy.kt", "MAX_TAGS"),
            "maxTagChars": kotlin_const("Utilities/InputPolicy.kt", "MAX_TAG_CHARS"),
            "maxSecretBytes": kotlin_const("Utilities/InputPolicy.kt", "MAX_SECRET_BYTES"),
            "maxNoteCharacters": kotlin_const("Utilities/InputPolicy.kt",
                                              "MAX_NOTE_CHARACTERS"),
            "maxPeriodSeconds": kotlin_const("Utilities/InputPolicy.kt", "MAX_PERIOD_SECONDS"),
        }
        for name, value in expectations.items():
            self.assertIn(f"static let {name} = {value}", policy, f"{name} drifted from :core")
        self.assertIn("public static let maxDocumentBytes = 16 * 1024 * 1024", policy)
        # The UIs enforce a minimum password length; the vault engine deliberately does not.
        self.assertEqual(6, kotlin_const("Utilities/Constants.kt", "AUTH_MIN_PASSWORD_LENGTH"))
        self.assertIn("public static let minVaultPasswordChars = 6", policy)

    def test_the_per_kind_seed_limits_match_the_material_classes(self):
        entry = (SOURCES / "Entry.swift").read_text(encoding="utf-8")
        # SshKeyMaterial: MAX_ENCODED_BYTES = 2 * MAX_KEY_BYTES + 8, MAX_KEY_BYTES = 32 KiB.
        self.assertIn("const val MAX_KEY_BYTES = 32 * 1024",
                      (CORE / "Utilities/SshKeyMaterial.kt").read_text(encoding="utf-8"))
        self.assertIn("public static let sshKey = 2 * (32 * 1024) + 8", entry)
        self.assertIn("const val MAX_FILE_BYTES = 4 * 1024 * 1024",
                      (CORE / "Utilities/BrowserPasswordCsv.kt").read_text(encoding="utf-8"))
        self.assertIn("public static let password = 4 * 1024 * 1024", entry)
        self.assertIn("const val MAX_BYTES = 128 * 1024",
                      (CORE / "Utilities/EmailMaterial.kt").read_text(encoding="utf-8"))
        self.assertIn("public static let email = 128 * 1024", entry)
        self.assertIn("public static let otp = InputPolicy.maxSecretBytes", entry)

    def test_the_stored_field_order_matches_the_jvm_serializer(self):
        """The order `EntryJson.serialize` appends keys in is part of the format."""
        serializer = (CORE / "Database/EntryJson.kt").read_text(encoding="utf-8")
        names = dict(re.findall(r'const val (JSON_[A-Z_]+) = "([a-z_]+)"', serializer))
        body = serializer.split("fun serialize(", 1)[1].split("\n    /**", 1)[0]
        written = []
        for token in re.findall(r"jsonObj\.put\((JSON_[A-Z_]+)[,)]", body):
            # `period` is written by the TOTP branch and again by the custom-Steam branch; only its
            # first position matters for the stored order.
            if names[token] not in written:
                written.append(names[token])
        self.assertGreaterEqual(len(written), 14, written)

        swift = (SOURCES / "Entry.swift").read_text(encoding="utf-8")
        block = swift.split("static let canonicalFieldOrder = [", 1)[1].split("]", 1)[0]
        order = re.findall(r'"([a-z_]+)"', block.replace("\n", " "))
        # The Swift list may mention more keys (the ones only some kinds write sit in one place), so
        # compare as an ordered subsequence: their relative order must be identical.
        filtered = [name for name in order if name in written]
        self.assertEqual(written, filtered,
                         "the Swift field order no longer follows EntryJson.serialize")
        self.assertEqual(set(names.values()) - set(order), set(),
                         "a JSON field is missing from the Swift canonical order")

    def test_the_token_rules_match_the_calculator(self):
        token = (SOURCES / "TokenCalculator.swift").read_text(encoding="utf-8")
        calculator = (CORE / "Utilities/TokenCalculator.kt").read_text(encoding="utf-8")
        alphabet = "23456789BCDFGHJKMNPQRTVWXY"
        for char in alphabet:
            self.assertIn(f"'{char}'", calculator)
        self.assertIn(alphabet, token)
        self.assertIn("const val STEAM_DEFAULT_DIGITS = 5", calculator)
        self.assertIn("public static let steamDefaultDigits = 5", token)
        self.assertIn("const val MOTP_STEP_SECONDS = 10", calculator)
        self.assertIn("public static let motpStepSeconds: Int64 = 10", token)
        self.assertIn("const val TOTP_DEFAULT_PERIOD = 30", calculator)
        self.assertIn("public static let totpDefaultPeriod = 30", token)
        self.assertIn('const val MOTP_NO_PIN_CODE = "PINREQ"',
                      (CORE / "Database/Entry.kt").read_text(encoding="utf-8"))
        self.assertIn('public static let motpNoPinCode = "PINREQ"',
                      (SOURCES / "Entry.swift").read_text(encoding="utf-8"))

    def test_the_swift_tests_use_the_shared_corpus(self):
        corpus = (TESTS / "GoldenCorpus.swift").read_text(encoding="utf-8")
        self.assertIn("desktop/src/test/resources/golden", corpus)
        self.assertIn('static let password = "golden-interop-password"', corpus)
        # The fixture parameters are restated in Swift, so they are pinned here instead.
        self.assertIn("static let vaultSalt: [UInt8] = Array(0x00...0x0F)", corpus)
        self.assertIn("static let vaultIterations = 4096", corpus)
        self.assertIn("static let vaultVerifierBase64URL = "
                      '"TJU9JVeBdEaFFFYX0KW4XA=="', corpus)
        self.assertIn("static let backupSalt: [UInt8] = Array(0x20...0x2B)", corpus)
        self.assertIn("static let backupNonce: [UInt8] = Array(0x30...0x3B)", corpus)
        properties = (GOLDEN / "vault.properties").read_text(encoding="utf-8")
        self.assertIn("auth_iterations=4096", properties)
        self.assertIn("auth_salt=AAECAwQFBgcICQoLDA0ODw", properties)
        self.assertIn("auth_credentials=TJU9JVeBdEaFFFYX0KW4XA", properties)

        every_test = "\n".join(swift_tests().values())
        for name in ("secrets.dat", "expected-vault.json", "otp_accounts.json.aes",
                     "desktop-note-export.json.aes", "desktop-ssh-export.json.aes",
                     "desktop-privacy-export.json.aes", "token-vectors.json"):
            self.assertIn(name, every_test, f"no Swift test opens {name}")

    def test_the_checked_in_token_vectors_match_their_generator(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/ios/generate-token-vectors.py")],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("token vectors match", result.stdout)
        vectors = (TESTS / "Fixtures/token-vectors.json").read_text(encoding="utf-8")
        self.assertIn("JBSWY3DPEHPK3PXP", vectors)
        self.assertIn("KRSXG5A=", vectors)

    def test_the_ios_workflow_builds_tests_and_signs_nothing(self):
        workflow = (ROOT / ".github/workflows/ios.yml").read_text(encoding="utf-8")
        self.assertIn("runs-on: macos-15", workflow)
        self.assertIn("swift test", workflow)
        self.assertIn("swift build --build-tests", workflow)
        self.assertIn("-destination 'generic/platform=iOS Simulator'", workflow)
        self.assertIn("scripts/ios/generate-token-vectors.py", workflow)
        # No signing, no secrets, no publishing: the certificate story belongs to the app target and
        # to the person shipping it, exactly as the other platforms keep releases manual.
        for forbidden in ("secrets.", "codesign", "xcodebuild archive", "exportArchive",
                          "notarytool", "altool", "gh release", "contents: write"):
            self.assertNotIn(forbidden, workflow, f"ios.yml must not use {forbidden}")


if __name__ == "__main__":
    unittest.main()
