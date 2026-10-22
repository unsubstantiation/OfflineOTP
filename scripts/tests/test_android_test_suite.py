"""Guards for the instrumentation suite (app/src/androidTest + its compile check).

The instrumentation sources only run on a device (CI emulator matrix); this file pins the parts
that must hold in every checkout without one:

* ``ApplicationTest.kt`` exists at exactly the path docs/language-security-assessment.md links
  to, with the byte-exact entry-document vector and RFC vectors the doc describes,
* the vault round-trip test is assumption-guarded and wipes the vault it creates,
* the suite compiles in the no-SDK environment (scripts/kotlin-sandbox/compile-android-tests.sh
  reads its source list from run.sh, so it cannot drift from the sandbox check), and
* the androidx.test compile stubs never shadow a production class.
"""
import os
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID_TEST_ROOT = ROOT / "app/src/androidTest/kotlin"
APPLICATION_TEST = ANDROID_TEST_ROOT / "org/shadowice/flocke/andotp/ApplicationTest.kt"
COMPILE_SCRIPT = ROOT / "scripts/kotlin-sandbox/compile-android-tests.sh"
TEST_STUBS = ROOT / "scripts/kotlin-sandbox/stubs-android-test"
BUILD_GRADLE = ROOT / "app/build.gradle"

# The exact stored document the byte-exact testEntry vector compares (see docs/
# kotlin-extracted-helpers.md: "compares that string exactly, so an ordering or naming change
# fails there").
EXPECTED_DOCUMENT = ('{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example","label":"alice",'
                     '"digits":6,"type":"TOTP","algorithm":"SHA1","thumbnail":"Default",'
                     '"last_used":0,"used_frequency":0,"period":30,"tags":["work"]}')


class AndroidTestSuiteStructureTest(unittest.TestCase):
    def test_application_test_exists_at_the_documented_path(self):
        self.assertTrue(APPLICATION_TEST.is_file(),
                        "docs/language-security-assessment.md links to this exact path")
        text = APPLICATION_TEST.read_text(encoding="utf-8")
        self.assertRegex(text, r"fun testEntry\(")
        # The byte-exact vector: the expectation string must be present, allowing the Kotlin
        # triple-quote line wrapping.
        joined = "".join(re.findall(r'"""(.*?)"""', text, re.DOTALL))
        self.assertIn('"secret":"JBSWY3DPEHPK3PXP"', joined,
                      "testEntry no longer pins the stored document string")
        self.assertIn('"period":30,"tags":["work"]', joined,
                      "testEntry no longer pins the field order at the tail of the document")
        self.assertIn("94287082", text, "the RFC 6238 vector is gone from ApplicationTest")

    def test_android_test_files_are_wired_instrumentation_classes(self):
        files = sorted(ANDROID_TEST_ROOT.rglob("*.kt"))
        self.assertTrue(files, "no androidTest sources found")
        for path in files:
            text = path.read_text(encoding="utf-8")
            package = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
            self.assertIsNotNone(package, f"{path} declares no package")
            expected = path.parent.relative_to(ANDROID_TEST_ROOT).as_posix().replace("/", ".")
            self.assertEqual(expected, package.group(1),
                             f"{path} package does not match its directory")
            if path.name.endswith("Support.kt"):
                # Shared fixtures, not a test class: these files must not be discovered as tests.
                self.assertNotIn("@Test", text, f"{path.name} is support code but declares a test")
                self.assertIn("internal object", text,
                              f"{path.name} is not a support object")
                continue
            self.assertIn("@RunWith(AndroidJUnit4::class)", text,
                          f"{path.name} is not an AndroidJUnit4 class")
            self.assertRegex(text, r"@Test\s*\n\s*fun ", f"{path.name} declares no @Test method")
            self.assertIn("import androidx.test", text,
                          f"{path.name} does not use the androidx.test API")

    def test_database_round_trip_is_assumption_guarded_and_wipes(self):
        text = APPLICATION_TEST.read_text(encoding="utf-8")
        start = text.index("fun databaseRoundTrip")
        end = text.index("\n    }", start)
        body = text[start:end]
        guard = body.index("assumeTrue")
        can_initialize = body.index("canInitialize")
        save = body.index("saveDatabase")
        wipe = body.index("wipeDatabase")
        finally_keyword = body.index("finally")
        self.assertLess(guard, save, "the vault round trip must be guarded before it saves")
        self.assertIn("canInitialize", body[guard:save],
                      "the assumption must check DatabaseHelper.canInitialize")
        self.assertLess(save, finally_keyword)
        self.assertLess(finally_keyword, wipe,
                        "wipeDatabase must run in the finally block, after any save attempt")

    def test_gcm_coverage_runs_on_host_and_android(self):
        """The NIST/backup vectors run on host; device tests still cover Android providers."""
        host_text = (ROOT / "app/src/test/kotlin/org/shadowice/flocke/andotp/Utilities/EncryptionHelperTest.kt") \
            .read_text(encoding="utf-8")
        self.assertRegex(host_text, r"fun aesGcmUsesTheNistVector",
                         "the portable JCA NIST vector is missing from the host suite")
        self.assertRegex(host_text, r"fun encryptedBackupFrameRoundTripsCurrentAndLegacyFormats",
                         "current/legacy backup round trips are missing from the host suite")

        device_text = APPLICATION_TEST.read_text(encoding="utf-8")
        self.assertIn("AEADBadTagException", device_text,
                      "tampered-frame vectors are missing from the device suite")
        self.assertRegex(device_text, r"fun aesGcmRejectsTamperedAndTruncatedFrames",
                         "the Android tamper/truncation vector method is missing")
        self.assertIn("EncryptionHelper.encrypt", device_text)

    def test_build_gradle_wires_the_instrumentation_dependencies(self):
        text = BUILD_GRADLE.read_text(encoding="utf-8")
        for needle in (
            "androidTestImplementation 'androidx.test.ext:junit:1.2.1'",
            "androidTestImplementation 'androidx.test:runner:1.6.2'",
            "androidTestImplementation 'androidx.test:core:1.6.1'",
            'testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"',
        ):
            self.assertIn(needle, text, f"app/build.gradle lost: {needle}")

    def test_compile_script_exists_and_stays_in_lockstep(self):
        self.assertTrue(os.access(COMPILE_SCRIPT, os.X_OK),
                        "compile-android-tests.sh must be executable")
        text = COMPILE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("eval \"$(sed -n '/^APP_SOURCES=(/,/^)$/p' \"$SANDBOX/run.sh\")\"", text)
        self.assertIn("eval \"$(sed -n '/^STUBS=(/,/^)$/p' \"$SANDBOX/run.sh\")\"", text)
        self.assertIn("stubs-android-test", text)
        self.assertIn("app/src/androidTest/kotlin", text)

    def test_android_test_stubs_do_not_shadow_production_classes(self):
        def production_names():
            names = set()
            roots = [ROOT / "app/src/main/kotlin"] + \
                [ROOT / f"app/src/{flavor}/kotlin" for flavor in ("fdroid", "play")] + \
                [ANDROID_TEST_ROOT]
            for root in roots:
                for path in root.rglob("*.kt"):
                    text = path.read_text(encoding="utf-8")
                    match = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
                    package = match.group(1) if match else ""
                    names.add(f"{package}.{path.stem}" if package else path.stem)
            return names

        production = production_names()
        for path in sorted(TEST_STUBS.rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            package = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
            package = package.group(1) if package else ""
            for match in re.finditer(
                r"^(?:public\s+|internal\s+|private\s+)?(?:open\s+|abstract\s+|sealed\s+|final\s+)?"
                r"(class|object|interface|enum class|fun interface)\s+([A-Za-z0-9_]+)",
                text, re.MULTILINE,
            ):
                name = f"{package}.{match.group(2)}" if package else match.group(2)
                self.assertNotIn(name, production,
                                 f"{path.relative_to(ROOT)} declares {name}, "
                                 "which is also a production class")


if __name__ == "__main__":
    unittest.main()
