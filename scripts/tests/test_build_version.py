"""Guard timestamp version wiring without requiring the Android Gradle toolchain."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class BuildVersionTest(unittest.TestCase):
    def test_android_uses_build_date_for_every_variant(self):
        app = (ROOT / "app/build.gradle").read_text()
        self.assertIn("apply from: rootProject.file('gradle/build-version.gradle')", app)
        self.assertIn("versionName buildDateVersion", app)
        self.assertIn("versionCode buildDateVersionCode", app)
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


if __name__ == "__main__":
    unittest.main()
