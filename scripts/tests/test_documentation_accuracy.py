"""Keep selected user-facing descriptions aligned with their implementation."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
CORE_KOTLIN = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"


def text(path):
    return (ROOT / path).read_text(encoding="utf-8")


class DocumentationAccuracyTest(unittest.TestCase):
    def test_parser_space_behavior_is_documented_and_characterized(self):
        document = text("docs/kotlin-otp-parser-pilot.md")
        parser = text("shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/ProvisioningCodec.kt")
        tests = text("core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/OtpUriParserTest.kt")
        self.assertIn("Raw ASCII spaces are converted to `%20`", document)
        self.assertIn('RawInterchangeUri(input.replace(" ", "%20"))', parser)
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
        self.assertIn("external `ACTION_VIEW` import requires confirmation", document)
        self.assertIn("Standard scanned or file-imported QR accounts are added directly", document)
        self.assertIn("except that an existing issuer triggers a confirmation", document)
        self.assertIn("Google Authenticator exports always require batch confirmation", document)
        external = activity.split("Intent.ACTION_VIEW ->", 1)[1].split("private fun importPreviewText", 1)[0]
        self.assertIn("showImportConfirmation(entry, epoch, R.string.security_import_title", external)
        self.assertIn("importPreviewText(entry)", external)
        self.assertIn("currentAdapter.hasIssuer(issuer)", activity)
        self.assertIn("showDuplicateIssuerConfirmation(entry, issuer)", activity)
        self.assertIn("saveImportedEntry(entry, epoch, R.string.toast_invalid_qr_code)", activity)

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

    def test_current_guides_share_verification_and_cutover_status(self):
        for name in ("README.md", "docs/README.md", "docs/shared-ui-migration-status.md", "ios/README.md"):
            document = text(name)
            self.assertIn("non-iOS", document, name)
            self.assertIn("Swift", document, name)
            self.assertRegex(document.lower(), r"ios[^\n]*(?:fail|cutover)|(?:fail|cutover)[^\n]*ios", name)
            for obsolete in ("hard 10-minute budget", "no Face ID yet", "versionCode 39",
                             "simulator build is the only artifact", "working daily-use app"):
                self.assertNotIn(obsolete, document, name)

    def test_documentation_index_covers_every_top_level_guide(self):
        index = text("docs/README.md")
        for path in sorted((ROOT / "docs").glob("*.md")):
            if path.name != "README.md":
                self.assertIn(f"]({path.name})", index, path.name)
        for required in ("Historical reports", "Current status", "Contracts and focused guides"):
            self.assertIn(required, index)

    def test_packaging_guides_do_not_invent_installer_switches_or_ipa_size(self):
        packaging = text("scripts/packaging/README.md")
        self.assertIn("app-image", packaging)
        self.assertIn("no generic `--type` override", packaging)
        ios = text("ios/README.md")
        self.assertIn("no fixed IPA size is promised", ios)
        self.assertNotIn("about **2.5 MB**", ios)
        self.assertIn("does **not** run standalone `swift test`", ios)



if __name__ == "__main__":
    unittest.main()
