"""Static guards for Android's Dark Forest surfaces and legible foreground colors.

The framework's AlertDialog does not use AppCompat's drawable tint manager, so a
colorBackgroundFloating override alone does not tint AppCompat's white dialog drawable. These
checks complement (but do not replace) the device rendering tests.
"""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"


def styles(path):
    root = ET.parse(path).getroot()
    return {
        style.attrib["name"]: (
            style.get("parent"),
            {item.attrib["name"]: (item.text or "").strip() for item in style.findall("item")},
        )
        for style in root.findall("style")
    }


def inherited_item(all_styles, name, key):
    seen = set()
    while name in all_styles:
        if name in seen:
            raise AssertionError(f"cyclic style inheritance at {name}")
        seen.add(name)
        parent, items = all_styles[name]
        if key in items:
            return items[key]
        name = parent
    raise AssertionError(f"{key} is not defined by an app style in {seen}")


def palette():
    return {item.attrib["name"]: (item.text or "").strip()
            for item in ET.parse(RES / "values/colors.xml").getroot().findall("color")}


def color(name):
    colors = palette()
    seen = set()
    while name.startswith("@color/"):
        name = name.removeprefix("@color/")
    while True:
        if name in seen:
            raise AssertionError(f"cyclic color alias at {name}")
        seen.add(name)
        value = colors[name]
        if not value.startswith("@color/"):
            break
        name = value.removeprefix("@color/")
    assert re.fullmatch(r"#[0-9a-fA-F]{6}", value), (name, value)
    return tuple(int(value[index:index + 2], 16) / 255 for index in (1, 3, 5))


def contrast(foreground, background):
    def luminance(rgb):
        linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
        return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))
    low, high = sorted((luminance(color(foreground)), luminance(color(background))))
    return (high + 0.05) / (low + 0.05)


class AndroidDarkForestThemeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.default = styles(RES / "values/styles.xml")
        cls.api27 = {**cls.default, **styles(RES / "values-v27/styles.xml")}

    def test_framework_appcompat_and_legacy_hooks_use_real_dark_dialog_background(self):
        for theme_styles in (self.default, self.api27):
            for name in ("AppTheme.NoActionBar", "AppTheme.Dark.NoActionBar", "AppTheme.Black.NoActionBar"):
                for attr in ("android:alertDialogTheme", "alertDialogTheme", "android:dialogTheme", "dialogTheme"):
                    with self.subTest(theme=name, attr=attr, api27=theme_styles is self.api27):
                        self.assertEqual("@style/DialogTheme", inherited_item(theme_styles, name, attr))
                self.assertEqual("@style/ThemeOverlay.OfflineOTP.DarkForest.MaterialAlertDialog",
                                 inherited_item(theme_styles, name, "materialAlertDialogTheme"))
        self.assertEqual("Theme.MaterialComponents.Bridge", self.default["Theme.OfflineOTP.DarkForest"][0])
        self.assertEqual("Theme.MaterialComponents.Dialog.MinWidth.Bridge",
                         self.default["Theme.OfflineOTP.DarkForest.Dialog"][0])
        for attr in ("android:alertDialogTheme", "alertDialogTheme", "android:dialogTheme", "dialogTheme"):
            self.assertEqual("@style/DialogTheme", inherited_item(self.default, "Theme.Intro.Solid", attr))
        self.assertEqual("@style/ThemeOverlay.OfflineOTP.DarkForest.MaterialAlertDialog",
                         inherited_item(self.default, "Theme.Intro.Solid", "materialAlertDialogTheme"))
        for name in ("DialogTheme", "DialogTheme.Dark"):
            self.assertEqual("@drawable/forest_dialog_background",
                             inherited_item(self.default, name, "android:windowBackground"))
            self.assertEqual("@color/forest_surface_elevated",
                             inherited_item(self.default, name, "android:colorBackground"))

        drawable = ET.parse(RES / "drawable/forest_dialog_background.xml").getroot()
        self.assertEqual("inset", drawable.tag)
        for side in ("Left", "Right", "Top", "Bottom"):
            self.assertEqual("16dp", drawable.get(ANDROID + "inset" + side))
        self.assertEqual("@color/forest_surface_elevated", drawable.find("shape/solid").get(ANDROID + "color"))
        self.assertEqual("@dimen/forest_dialog_corner_radius",
                         drawable.find("shape/corners").get(ANDROID + "radius"))

    def test_dialog_popup_and_intro_controls_use_the_dark_palette(self):
        for name in ("Theme.OfflineOTP.DarkForest", "Theme.OfflineOTP.DarkForest.Dialog", "Theme.Intro.Solid"):
            for attr, widget in (
                ("android:spinnerStyle", "Spinner"), ("spinnerStyle", "Spinner"),
                ("android:popupMenuStyle", "PopupMenu"), ("popupMenuStyle", "PopupMenu"),
                ("android:listPopupWindowStyle", "ListPopupWindow"), ("listPopupWindowStyle", "ListPopupWindow"),
            ):
                with self.subTest(theme=name, attr=attr):
                    self.assertEqual(f"@style/Widget.OfflineOTP.DarkForest.{widget}",
                                     inherited_item(self.default, name, attr))
            self.assertEqual("@color/forest_text_hint", inherited_item(self.default, name, "android:textColorHint"))
            self.assertEqual("@color/forest_text_primary_selector",
                             inherited_item(self.default, name, "android:textColorPrimary"))
            self.assertEqual("@color/forest_text_primary_selector",
                             inherited_item(self.default, name, "android:textColorPrimaryDisableOnly"))
            self.assertEqual("@color/forest_text_secondary_selector",
                             inherited_item(self.default, name, "android:textColorSecondary"))
        for widget in ("Spinner", "PopupMenu", "PopupMenu.Overflow", "ListPopupWindow"):
            name = f"Widget.OfflineOTP.DarkForest.{widget}"
            self.assertEqual("@drawable/forest_popup_background", inherited_item(self.default, name, "android:popupBackground"))
        popup = ET.parse(RES / "drawable/forest_popup_background.xml").getroot()
        self.assertEqual("@color/forest_surface_elevated", popup.find("solid").get(ANDROID + "color"))
        overlay = "Theme.OfflineOTP.DarkForest.PopupOverlay"
        self.assertEqual("@style/Widget.OfflineOTP.DarkForest.PopupMenu.Overflow",
                         inherited_item(self.default, overlay, "actionOverflowMenuStyle"))
        for theme in (overlay, "ThemeOverlay.OfflineOTP.DarkForest.MaterialAlertDialog"):
            self.assertEqual("@color/forest_text_primary_selector",
                             inherited_item(self.default, theme, "android:textColorPrimaryDisableOnly"))
        for attr in ("android:textColorAlertDialogListItem", "textColorAlertDialogListItem"):
            for theme in ("Theme.OfflineOTP.DarkForest", "Theme.OfflineOTP.DarkForest.Dialog",
                          "ThemeOverlay.OfflineOTP.DarkForest.MaterialAlertDialog"):
                self.assertEqual("@color/forest_text_primary_selector", inherited_item(self.default, theme, attr))

    def test_enabled_disabled_and_hint_text_contrast_on_forest_surfaces(self):
        for selector, enabled in (("forest_text_primary_selector", "forest_text_primary"),
                                  ("forest_text_secondary_selector", "forest_text_secondary")):
            items = ET.parse(RES / "color" / f"{selector}.xml").getroot().findall("item")
            self.assertEqual("false", items[0].get(ANDROID + "state_enabled"))
            self.assertEqual("@color/forest_text_hint", items[0].get(ANDROID + "color"))
            self.assertEqual("@color/" + enabled, items[1].get(ANDROID + "color"))
        for background in ("forest_background", "forest_surface", "forest_surface_elevated", "forest_surface_variant"):
            for foreground in ("forest_text_primary", "forest_text_secondary", "forest_text_hint",
                               "colorAccent", "warning_red", "expiring_red_dark"):
                with self.subTest(foreground=foreground, background=background):
                    self.assertGreaterEqual(contrast(foreground, background), 4.5)
        for foreground in ("forest_text_primary", "forest_text_secondary", "colorAccent"):
            self.assertGreaterEqual(contrast(foreground, "forest_appbar"), 4.5)
        for foreground, background in (("forest_on_primary", "colorPrimary"),
                                       ("forest_on_primary", "warning_red"),
                                       ("forest_on_secondary", "colorAccent"),
                                       ("forest_on_secondary", "colorPrimary")):
            self.assertGreaterEqual(contrast(foreground, background), 4.5, (foreground, background))
        self.assertEqual("@color/forest_text_primary",
                         inherited_item(self.default, "Theme.OfflineOTP.DarkForest.AppBarOverlay", "android:textColorPrimary"))
        # The app-bar overlay uses light text, so both the wrapper and toolbar need its dark surface.
        for name in ("activity_main.xml", "activity_container.xml"):
            appbar = ET.parse(RES / "layout" / name).getroot().find(
                ".//com.google.android.material.appbar.AppBarLayout")
            self.assertEqual("@color/forest_appbar", appbar.find("RelativeLayout").get(ANDROID + "background"))
            self.assertEqual("@color/forest_appbar", appbar.find(".//androidx.appcompat.widget.Toolbar").get(ANDROID + "background"))

    def test_setup_slides_and_add_menu_are_not_light_unreadable_surfaces(self):
        intro = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/IntroScreenActivity.kt").read_text()
        self.assertEqual(5, intro.count(".background(R.color.forest_surface)"))
        self.assertNotIn(".background(R.color.colorPrimary)", intro)
        self.assertIn("getResources().getColor(R.color.forest_surface_variant)", intro)
        layout = ET.parse(RES / "layout/activity_main.xml").getroot()
        dial = layout.find(".//com.leinardi.android.speeddial.SpeedDialView")
        for attr in ("sdMainFabClosedIconColor", "sdMainFabOpenedIconColor"):
            self.assertEqual("@color/forest_on_secondary", dial.get(APP + attr))
        overlay = layout.find(".//com.leinardi.android.speeddial.SpeedDialOverlayLayout")
        self.assertEqual("@color/forest_scrim", overlay.get(ANDROID + "background"))
        self.assertEqual("@color/forest_text_primary", palette()["sd_label_text_color"])
        self.assertEqual("@color/forest_surface_elevated", palette()["sd_label_background_color"])
        night = {item.attrib["name"]: (item.text or "").strip()
                 for item in ET.parse(RES / "values-night/colors.xml").getroot().findall("color")}
        self.assertEqual(palette()["sd_label_text_color"], night["sd_label_text_color"])
        self.assertEqual(palette()["sd_label_background_color"], night["sd_label_background_color"])
        self.assertGreaterEqual(contrast("sd_label_text_color", "sd_label_background_color"), 4.5)


if __name__ == "__main__":
    unittest.main()
