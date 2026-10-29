"""Prevent the shared desktop palette from drifting away from Android's Dark Forest tokens."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COLORS = ROOT / "app/src/main/res/values/colors.xml"
THEME = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/ForestTheme.kt"


class DesktopDarkForestThemeTest(unittest.TestCase):
    def test_desktop_semantic_colors_match_android(self):
        android = {node.attrib["name"]: (node.text or "").strip().upper()
                   for node in ET.parse(COLORS).getroot().findall("color")}
        source = THEME.read_text(encoding="utf-8")
        tokens = {}
        # Keep RGB components separate so failures identify the token rather than a textual format.
        for name, red, green, blue in re.findall(
                r"val\s+(\w+)\s*=\s*ColorUIResource\(0x([0-9A-Fa-f]{2}),\s*"
                r"0x([0-9A-Fa-f]{2}),\s*0x([0-9A-Fa-f]{2})\)", source):
            tokens[name] = f"#{red}{green}{blue}".upper()
        pairs = {
            "BACKGROUND": "forest_background",
            "BACKGROUND_DEEP": "forest_background_deep",
            "SURFACE": "forest_surface",
            "SURFACE_ELEVATED": "forest_surface_elevated",
            "SURFACE_VARIANT": "forest_surface_variant",
            "APP_BAR": "forest_appbar",
            "PRIMARY": "colorPrimary",
            "ACCENT": "colorAccent",
            "TEXT_PRIMARY": "forest_text_primary",
            "TEXT_SECONDARY": "forest_text_secondary",
            "TEXT_HINT": "forest_text_hint",
            "ON_PRIMARY": "forest_on_primary",
            "DIVIDER": "forest_divider",
            "WARNING": "warning_red",
            "THUMBNAIL": "forest_thumbnail_background",
        }
        for desktop, mobile in pairs.items():
            with self.subTest(desktop=desktop, android=mobile):
                self.assertEqual(android[mobile], tokens[desktop])

    def test_theme_is_installed_before_desktop_components_and_startup_error(self):
        window = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/MainWindow.kt")\
            .read_text(encoding="utf-8")
        main = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/Main.kt")\
            .read_text(encoding="utf-8")
        self.assertLess(window.index("ForestTheme.install()"), window.index("val table = JTable("))
        self.assertIn("ForestTheme.install()", main)
        self.assertIn("MetalLookAndFeel", THEME.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
