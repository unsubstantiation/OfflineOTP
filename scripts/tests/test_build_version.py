"""Guard timestamp version wiring without requiring the Android Gradle toolchain."""
from pathlib import Path
import unittest

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

    def test_timestamp_is_build_time_utc_with_reproducible_override(self):
        script = (ROOT / "gradle/build-version.gradle").read_text()
        self.assertIn("environmentVariable('SOURCE_DATE_EPOCH')", script)
        self.assertIn("java.time.Instant.now()", script)
        self.assertIn('ofPattern("\'V\'uuuu-MM-dd-HH-mm")', script)
        self.assertIn("withZone(java.time.ZoneOffset.UTC)", script)
        self.assertIn("2020-01-01T00:00:00Z", script)
        self.assertIn("minuteCode <= 39 || minuteCode > 2100000000L", script)
        self.assertIn("Duration.between(codeEpoch, buildInstant).toMinutes()", script)


if __name__ == "__main__":
    unittest.main()
