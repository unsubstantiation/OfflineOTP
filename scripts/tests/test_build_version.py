"""Guard timestamp version wiring without requiring the Android Gradle toolchain."""
from pathlib import Path
import unittest
import datetime as dt
import importlib.util
import plistlib
import tempfile
import yaml
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class BuildVersionTest(unittest.TestCase):
    def test_android_uses_build_date_for_every_variant(self):
        app = (ROOT / "app/build.gradle").read_text()
        self.assertIn("apply from: 'gradle/build-version.gradle'", (ROOT / "build.gradle").read_text())
        self.assertIn("versionName rootProject.ext.buildDateVersion", app)
        self.assertIn("versionCode rootProject.ext.buildDateVersionCode", app)
        self.assertNotIn("versionNameSuffix", app)
        self.assertNotIn('versionName "0.9.0.1"', app)
        about = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Fragments/BaseAboutFragment.kt").read_text()
        self.assertIn("packageInfo.versionName", about)
        self.assertIn("version.setText(versionName)", about)
        self.assertIn("findViewById<TextView>(R.id.about_text_version)", about)
        layout = ET.parse(ROOT / "app/src/main/res/layout/fragment_about.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        versions = [node for node in layout.iter()
                    if node.get(android + "id") == "@+id/about_text_version"]
        self.assertEqual(1, len(versions))
        self.assertNotIn(versions[0].get(android + "visibility"), ("gone", "invisible"))

    def test_timestamp_is_build_time_akst_with_reproducible_override(self):
        script = (ROOT / "gradle/build-version.gradle").read_text()
        self.assertIn("environmentVariable('SOURCE_DATE_EPOCH')", script)
        self.assertIn("java.time.Instant.now()", script)
        self.assertIn('ofPattern("\'V\'uuuu-MM-dd-HH-mm")', script)
        self.assertIn("withZone(java.time.ZoneOffset.ofHours(-9))", script)
        self.assertIn("2020-01-01T00:00:00Z", script)
        self.assertIn("minuteCode <= 39 || minuteCode > 2100000000L", script)
        self.assertIn("Duration.between(codeEpoch, buildInstant).toMinutes()", script)

    def test_desktop_embeds_version_and_supplies_safe_package_metadata(self):
        build = (ROOT / "desktop-compose/build.gradle").read_text()
        self.assertIn("inputs.property('versionLabel', rootProject.ext.buildDateVersion)", build)
        self.assertIn("tasks.named('processResources') { dependsOn 'generateBuildVersion' }", build)
        self.assertIn("sourceSets.main.resources.srcDir(versionResources)", build)
        self.assertIn("'--app-version', isWindows ? rootProject.ext.windowsBuildVersion : rootProject.ext.appleBuildVersion", build)
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn('getResourceAsStream("/offlineotp-build-version.txt")', host)
        self.assertIn('AboutNavigation(onAbout = { showAbout = true })', host)
        self.assertNotIn('Menu("Help")', host)
        self.assertIn('version = buildVersion', host)
        self.assertIn('Version: $version', (ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt').read_text())
        for platform in ("windows", "macos", "debian"):
            workflow = (ROOT / f".github/workflows/{platform}.yml").read_text()
            self.assertIn(":desktop-compose:packageDesktop", workflow)

    def test_ios_stamps_built_plist_on_every_local_or_ci_build(self):
        project = yaml.safe_load((ROOT / "ios/project.yml").read_text())
        phase = project["targets"]["OfflineOTP"]["postBuildScripts"][0]
        self.assertIn("stamp-ios-version.py", phase["script"])
        self.assertFalse(phase["basedOnDependencyAnalysis"])
        self.assertIn("$(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)", phase["inputFiles"])
        about = (ROOT / "ios/App/Sources/SettingsView.swift").read_text()
        self.assertIn('LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "OfflineOTPBuildVersion")', about)

    def test_actual_ios_timestamp_and_plist_round_trip(self):
        spec = importlib.util.spec_from_file_location("stamp_version", ROOT / "scripts/versioning/stamp-ios-version.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        examples = [
            ("2026-11-16T20:38:00+00:00", "V2026-11-16-11-38"),
            ("2026-07-01T08:30:00+00:00", "V2026-06-30-23-30"),
            ("2027-01-01T08:59:00+00:00", "V2026-12-31-23-59"),
        ]
        for iso, expected in examples:
            epoch = str(int(dt.datetime.fromisoformat(iso).timestamp()))
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "Info.plist"
                path.write_bytes(plistlib.dumps({"CFBundleIdentifier": "test.app", "CFBundleVersion": "1"}))
                module.stamp(path, epoch)
                first = path.read_bytes()
                info = plistlib.loads(first)
                self.assertEqual(expected, info["OfflineOTPBuildVersion"])
                self.assertEqual("test.app", info["CFBundleIdentifier"])
                self.assertRegex(info["CFBundleVersion"], r"^\d{1,4}\.\d{1,2}\.\d{1,2}$")
                self.assertRegex(info["CFBundleShortVersionString"], r"^\d+\.\d+\.\d+$")
                module.stamp(path, epoch)
                self.assertEqual(first, path.read_bytes())
        for invalid in ("not-a-timestamp", "0", "999999999999999999999999"):
            with self.assertRaises((ValueError, OverflowError, OSError)):
                module.versions(invalid)

    def test_reproducibility_checks_pin_time_for_both_builds(self):
        for name in ("scripts/verify_reproducible_build.sh", "scripts/packaging/verify-desktop-build.sh"):
            text = (ROOT / name).read_text()
            self.assertIn('export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date +%s)}"', text)
            self.assertLess(text.index("export SOURCE_DATE_EPOCH="), text.index("for run in 1 2"))


if __name__ == "__main__":
    unittest.main()
