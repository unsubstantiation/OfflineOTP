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
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
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


def swift_without_literals(text):
    """`text` with comments and string literals blanked, so braces only mean scope.

    A brace counter over raw Swift is wrong the first time a literal contains `{` — which this kit
    does, because it writes JSON by hand. Positions are preserved (each blanked character becomes a
    space) so offsets still address the original source.
    """
    out = list(text)
    index, length = 0, len(text)
    while index < length:
        char = text[index]
        if text.startswith("//", index):
            while index < length and text[index] != "\n":
                out[index] = " "
                index += 1
        elif text.startswith("/*", index):
            while index < length and not text.startswith("*/", index):
                if text[index] != "\n":
                    out[index] = " "
                index += 1
            index += 2
        elif char == '"' or (char == "#" and text.startswith('#"', index)):
            triple = text.startswith('"""', index)
            raw = char == "#"
            quote = 3 if triple else 1
            index += quote + (1 if raw else 0)
            while index < length:
                if text[index] == "\\":
                    out[index] = " "
                    if index + 1 < length and text[index + 1] != "\n":
                        out[index + 1] = " "
                    index += 2
                    continue
                if text.startswith('"""' if triple else '"', index):
                    index += quote
                    break
                if text[index] != "\n":
                    out[index] = " "
                index += 1
        else:
            index += 1
    return "".join(out)


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

    def test_no_swift_expression_defeats_the_type_checker(self):
        """The kit has to survive `swift build` before anything else about it matters.

        `Argon2id.loadLe64` was written as one eight-term `|` chain of `UInt64(x) << literal`. That
        shape makes the type checker enumerate a literal type for every operand and it gives up with
        "unable to type-check this expression in reasonable time" — a red iOS build on master that
        no Kotlin gate could see. The rule is structural, so it is pinned structurally: no
        expression may chain more than a few widened shifts together.
        """
        argon = (SOURCES / "Argon2id.swift").read_text(encoding="utf-8")
        load = argon.split("func loadLe64", 1)[1].split("\n    }", 1)[0]
        self.assertNotIn("<< 56", load, "the loadLe64 shift chain is back")
        self.assertIn("for index in 0..<8", load)
        for path in list(SOURCES.glob("*.swift")) + list((IOS / "App/Sources").glob("*.swift")):
            text = path.read_text(encoding="utf-8")
            for line in text.splitlines():
                # A single line with five or more `<<` is the shape that defeats inference.
                self.assertLess(line.count("<<"), 5, f"{path.name}: {line.strip()}")
        # `assign(repeating:count:)` is deprecated in favour of `update(repeating:count:)`.
        self.assertNotIn("assign(repeating:", argon)

    def test_a_mutable_byte_access_never_reads_the_array_it_borrows(self):
        """`arr.withUnsafeMutableBytes { ... }` lends `arr` out for the call.

        Reading `arr.count` inside that closure is a second access to the same value while the
        mutable borrow is live, and Swift refuses it: "overlapping accesses to 'out', but
        modification requires exclusive access". `EmailCredentials.aesCbcDecrypt` passed
        `out.count` as CCCrypt's output capacity, which made `swift build` fail for the whole
        module. Nothing here compiles Swift, so the shape is pinned: inside a mutable byte/buffer
        closure the borrowed array's own properties come from the closure argument instead.
        """
        reads = re.compile(r"\b(count|capacity|indices|startIndex|endIndex|isEmpty|underestimatedCount)\b")
        files = list(SOURCES.glob("*.swift")) + list((IOS / "App/Sources").glob("*.swift"))
        offenders = []
        for path in files:
            text = path.read_text(encoding="utf-8")
            scope = swift_without_literals(text)
            for match in re.finditer(r"\b(\w+)\.withUnsafeMutable(?:Bytes|BufferPointer)\s*\{", scope):
                name, start, depth = match.group(1), match.end() - 1, 0
                end = start
                for index in range(start, len(scope)):
                    if scope[index] == "{":
                        depth += 1
                    elif scope[index] == "}":
                        depth -= 1
                        if depth == 0:
                            end = index
                            break
                body = scope[start:end]
                for member in re.finditer(rf"\b{re.escape(name)}\.(\w+)", body):
                    if reads.fullmatch(member.group(1)):
                        line = text[:start].count("\n") + 1
                        offenders.append(f"{path.name}:{line}: reads {name}.{member.group(1)} "
                                         f"inside {name}.withUnsafeMutable…")
        self.assertEqual([], offenders, "; ".join(offenders))

        # The call that broke, pinned from the other end: the capacity comes from the buffer.
        email = (SOURCES / "EmailCredentials.swift").read_text(encoding="utf-8")
        aes = email.split("func aesCbcDecrypt", 1)[1].split("\n    }", 1)[0]
        self.assertIn("outBuf.count", aes)
        self.assertIn("outBuf.baseAddress", aes)

    def test_the_csv_writer_is_declared_where_callers_name_it(self):
        """A Swift member call is resolved against the type in the callee position.

        `PasswordCsvExport` called `EmailMaterial.exportCsv(columns:rows:)` while the writer was
        declared on another type, and the iOS build failed with "type 'EmailMaterial' has no member
        'exportCsv'". Nothing in this sandbox compiles Swift, so the sources are parsed: every
        `Type.member(` reference must resolve to a member the type declares — including members a
        type gains through an `extension` in another file.
        """
        import re

        files = list(SOURCES.glob("*.swift")) + list((IOS / "App/Sources").glob("*.swift"))
        texts = [path.read_text(encoding="utf-8") for path in files]
        whole = "\n".join(texts)

        # Members are collected per file: positions from one file mean nothing in another.
        declared = {}
        for text in texts:
            # Anchored to the line and cut before the `{`: a pattern that spans to the opening brace
            # cannot cross the braces inside a declaration such as
            # `extension JSONValue {` ... `func decode(_ text: String)`, and silently skips it.
            for match in re.finditer(
                    r"^[ \t]*(?:public |internal |private |fileprivate )?"
                    r"(?:enum|struct|final class|class|extension) (\w+)", text, re.M):
                name, start, depth = match.group(1), match.end(), 0
                scope = swift_without_literals(text)
                for index in range(start, len(text)):
                    if scope[index] == "{":
                        depth += 1
                    elif scope[index] == "}":
                        depth -= 1
                        if depth == 0:
                            body = text[start:index]
                            members = set(re.findall(r"func\s+(\w+)", body))
                            members |= set(re.findall(r"(?:let|var)\s+(\w+)", body))
                            members |= set(re.findall(r"case\s+(\w+)", body))
                            # Nested types are constructed as `Outer.Inner(...)`.
                            members |= set(re.findall(
                                r"^[ \t]*(?:public |internal |private |fileprivate )?"
                                r"(?:enum|struct|final class|class) (\w+)", body, re.M))
                            declared.setdefault(name, set()).update(members)
                            break

        unresolved = {
            f"{owner}.{member}()"
            for owner, member in re.findall(r"\b([A-Z]\w+)\.(\w+)\(", whole)
            if owner in declared
            and member not in declared[owner]
            # `Type.init(...)` is a compiler form, not a declared member.
            and member != "init"
        }
        self.assertEqual(set(), unresolved, f"members a call names but no type declares: {unresolved}")

        # The specific pair that broke, from both ends.
        password = (SOURCES / "PasswordCsvExport.swift").read_text(encoding="utf-8")
        self.assertIn("ThunderbirdCsv.exportCsv(columns: columns, rows: rows)", password)
        self.assertNotIn("EmailMaterial.exportCsv(", whole)
        self.assertIn("exportCsv", declared["ThunderbirdCsv"])
        self.assertNotIn("exportCsv", declared["EmailMaterial"])

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
