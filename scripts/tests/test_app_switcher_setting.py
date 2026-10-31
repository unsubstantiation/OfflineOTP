"""The app switcher blackout is an opt-in setting, off by default.

Android exposes one flag for two different things: it blocks screenshots while the app is visible
*and* it blanks the app switcher preview while the app is in the background. The switch that hides
the app from the switcher must therefore be independent of the existing screenshot setting and must
stay off unless the user turns it on. These guards complement `ScreenshotPolicyTest` (core) and the
sandbox check that drives the window flags.
"""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
RES = APP / "res"
KOTLIN = APP / "kotlin/org/shadowice/flocke/andotp"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def read(path):
    return path.read_text(encoding="utf-8")


class AppSwitcherSettingTest(unittest.TestCase):
    def setUp(self):
        self.settings_xml = read(RES / "values/settings.xml")
        self.strings = read(RES / "values/strings_settings.xml")
        self.preferences = read(RES / "xml/preferences.xml")

    def test_the_preference_is_a_default_off_security_option(self):
        self.assertIn(
            '<string name="settings_key_hide_in_app_switcher" translatable="false">'
            "pref_hide_in_app_switcher</string>",
            self.settings_xml,
        )
        root = ET.parse(RES / "xml/preferences.xml").getroot()
        preference = root.find(f".//*[@{ANDROID}key='@string/settings_key_hide_in_app_switcher']")
        self.assertIsNotNone(preference, "the setting needs a row in preferences.xml")
        self.assertEqual("CheckBoxPreference", preference.tag.split("}")[-1])
        self.assertEqual("false", preference.get(ANDROID + "defaultValue"))
        # It is a privacy option, not a "special feature" hidden behind another switch.
        special = ET.parse(RES / "xml/preferences_special.xml").getroot()
        self.assertIsNone(
            special.find(f".//*[@{ANDROID}key='@string/settings_key_hide_in_app_switcher']"),
            "the app switcher setting must not be hidden under special features",
        )
        self.assertIn("settings_title_hide_in_app_switcher", self.strings)
        self.assertIn("settings_desc_hide_in_app_switcher", self.strings)

    def test_the_getter_defaults_to_showing_the_preview(self):
        settings = read(KOTLIN / "Utilities/Settings.kt")
        self.assertIn(
            "open fun getHideInAppSwitcher(): Boolean {\n"
            "        return getBoolean(R.string.settings_key_hide_in_app_switcher, false)",
            settings,
        )
        # The existing screenshot switch keeps its own default and is not reused for this.
        self.assertIn(
            "open fun getScreenshotsEnabled(): Boolean {\n"
            "        return getBoolean(R.string.settings_key_enable_screenshot, false)",
            settings,
        )

    def test_the_policy_keeps_the_two_behaviours_separate(self):
        policy = read(ROOT / "core/src/main/kotlin/"
                           "org/shadowice/flocke/andotp/Utilities/ScreenshotPolicy.kt")
        self.assertIn("fun secureWhileVisible(screenshotsEnabled: Boolean)", policy)
        self.assertIn(
            "fun secureWhileBackgrounded(screenshotsEnabled: Boolean, hideInAppSwitcher: Boolean)",
            policy,
        )
        self.assertIn("!screenshotsEnabled && hideInAppSwitcher", policy)

    def test_every_secret_bearing_window_applies_the_background_policy(self):
        tools = read(KOTLIN / "Utilities/Tools.kt")
        self.assertIn("fun applyAppSwitcherPolicy(", tools)
        self.assertIn("fun setSecure(window: Window, secure: Boolean)", tools)

        base = read(KOTLIN / "Activities/BaseActivity.kt")
        self.assertIn("public override fun onPause()", base)
        self.assertIn("Tools.applyAppSwitcherPolicy(", base)
        self.assertIn("public override fun onResume()", base)
        self.assertIn("applyScreenshotPolicyForForeground()", base)

        intro = read(KOTLIN / "Activities/IntroScreenActivity.kt")
        self.assertIn("Tools.applyAppSwitcherPolicy(", intro)
        self.assertIn("Tools.applyScreenshotPolicy(", intro)

        # The scanner shows the camera, so it opts out of the user preference in both phases.
        scanner = read(KOTLIN / "Activities/SecureCaptureActivity.kt")
        self.assertEqual(scanner.count("Tools.setSecure(getWindow(), true)"), 3)
        self.assertNotIn("FLAG_SECURE", scanner)

    def test_the_settings_screen_reacts_to_the_switch(self):
        activity = read(KOTLIN / "Activities/SettingsActivity.kt")
        self.assertIn("settings_key_hide_in_app_switcher", activity)
        self.assertIn("applyScreenshotPolicyForForeground()", activity)


if __name__ == "__main__":
    unittest.main()
