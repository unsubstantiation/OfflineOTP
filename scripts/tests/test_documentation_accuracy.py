"""Keep selected user-facing descriptions aligned with their implementation."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


def text(path):
    return (ROOT / path).read_text(encoding="utf-8")


class DocumentationAccuracyTest(unittest.TestCase):
    def test_parser_space_behavior_is_documented_and_characterized(self):
        document = text("docs/kotlin-otp-parser-pilot.md")
        parser = (KOTLIN / "Utilities/OtpUriParser.kt").read_text(encoding="utf-8")
        tests = text("app/src/test/kotlin/org/shadowice/flocke/andotp/Utilities/OtpUriParserTest.kt")
        self.assertIn("Raw ASCII spaces are converted to `%20`", document)
        self.assertIn('URI(input.replace(" ", "%20"))', parser)
        self.assertIn("fun spaceCharactersAreEncodedBeforeParsing()", tests)
        self.assertIn("otpauth://totp/alice example?secret=", tests)

    def test_readme_theme_matches_legacy_style_hooks(self):
        self.assertIn("The UI uses the Dark Forest theme", text("README.md"))
        default = ET.parse(ROOT / "app/src/main/res/values/styles.xml").getroot()
        qualified = ET.parse(ROOT / "app/src/main/res/values-v27/styles.xml").getroot()
        parents = {style.get("name"): style.get("parent") for style in default.findall("style")}
        overrides = {style.get("name"): style.get("parent") for style in qualified.findall("style")}
        for styles in (parents, {**parents, **overrides}):
            for name in ("AppTheme", "AppTheme.Dark", "AppTheme.Black", "AppTheme.NoActionBar",
                         "AppTheme.Dark.NoActionBar", "AppTheme.Black.NoActionBar"):
                with self.subTest(style=name, qualified=styles is not parents):
                    current = name
                    seen = set()
                    while current != "Theme.OfflineOTP.DarkForest":
                        self.assertNotIn(current, seen, f"cyclic parent chain for {name}")
                        seen.add(current)
                        self.assertIn(current, styles, f"missing parent for {name}")
                        current = styles[current]

    def test_security_doc_distinguishes_external_and_qr_imports(self):
        document = text("docs/vault-security-hardening.md")
        activity = (KOTLIN / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("external `ACTION_VIEW` OTP import requires confirmation", document)
        self.assertIn("Scanned or file-imported QR codes are added directly", document)
        self.assertIn("except that an existing issuer triggers a confirmation", document)
        self.assertIn("Intent.ACTION_VIEW ->", activity)
        self.assertIn(".setTitle(R.string.security_import_title)", activity)
        self.assertIn("currentAdapter.hasIssuer(issuer)", activity)
        self.assertIn("showDuplicateIssuerConfirmation(entry, issuer)", activity)
        self.assertIn("saveImportedQrEntry(entry)", activity)

    def test_security_doc_matches_the_source_backup_policy(self):
        document = text("docs/vault-security-hardening.md")
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        application = manifest.find("application")
        android = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual("false", application.get(android + "allowBackup"))
        self.assertIsNone(application.get(android + "backupAgent"))
        self.assertTrue((KOTLIN / "Utilities/BackupAgent.kt").is_file())
        self.assertIn("no backup agent registration", document)
        self.assertIn("retained unregistered legacy agent", document)
        self.assertIn("merged, packaged manifest", document)

    def test_feature_graphic_does_not_mislabel_the_license(self):
        root = ET.parse(ROOT / "assets/feature_graphic.svg").getroot()
        labels = " ".join(element.text or "" for element in root if element.tag.endswith("}text"))
        self.assertIn("Offline two-factor", labels)
        self.assertNotIn("open source", labels.lower())
        self.assertIn("not** an OSI-approved open-source license", text("README.md"))
        self.assertEqual(
            (ROOT / "assets/feature_graphic.png").read_bytes(),
            (ROOT / "fastlane/metadata/android/en-US/images/featureGraphic.png").read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
