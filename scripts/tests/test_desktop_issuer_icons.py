"""The desktop client must show the bundled issuer logos and the amber OTP token color.

Both clients share one artwork set: the Android vector drawables. These checks keep the generated
name -> drawable table, the desktop resource packaging, and the amber token tokens from drifting.
"""
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRAWABLE = ROOT / "app/src/main/res/drawable"
GENERATED = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/ThumbnailAssets.kt"
DESKTOP_BUILD = ROOT / "desktop/build.gradle"
ANDROID = "{http://schemas.android.com/apk/res/android}"
RES = ROOT / "app/src/main/res"


def palette():
    root = ET.parse(RES / "values/colors.xml").getroot()
    return {node.attrib["name"]: (node.text or "").strip() for node in root.findall("color")}


def color(value):
    name = value.removeprefix("@color/")
    assert name in palette(), name
    return palette()[name]


def contrast(foreground, background):
    def luminance(hex_value):
        channels = [int(hex_value[index:index + 2], 16) / 255 for index in (1, 3, 5)]
        linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in channels]
        return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))

    low, high = sorted((luminance(color(foreground)), luminance(color(background))))
    return (high + 0.05) / (low + 0.05)


class DesktopIssuerIconsTest(unittest.TestCase):
    def test_generated_table_is_current(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts/branding/generate_thumbnail_assets.py"), "--check"],
            capture_output=True, text=True)
        self.assertEqual(0, result.returncode,
                         result.stderr or "ThumbnailAssets.kt is stale; rerun the generator")

    def test_table_matches_the_android_thumbnail_enum(self):
        enum_source = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EntryThumbnail.kt")\
            .read_text(encoding="utf-8")
        body = enum_source.split("enum class EntryThumbnails", 1)[1]
        declared = dict(re.findall(r"^\s{8}(\w+)\(R\.(?:drawable|mipmap)\.(\w+)", body, re.MULTILINE))
        generated = dict(re.findall(r'"(\w+)" to "(\w+)"', GENERATED.read_text(encoding="utf-8")))
        declared.pop("Default")  # the letter tile has no bundled artwork
        self.assertEqual(declared, generated)

    def test_every_generated_mark_is_a_bundled_vector(self):
        generated = re.findall(r'"\w+" to "(\w+)"', GENERATED.read_text(encoding="utf-8"))
        self.assertGreater(len(generated), 400)
        bundled = {path.stem for path in DRAWABLE.glob("thumb_*.xml")}
        self.assertEqual(bundled, set(generated))
        for name in set(generated):
            root = ET.parse(DRAWABLE / f"{name}.xml").getroot()
            self.assertEqual("vector", root.tag, name)
            self.assertTrue(root.get(ANDROID + "pathData") or len(root), name)

    def test_desktop_build_packages_the_shared_artwork_and_reads_it_from_the_classpath(self):
        build = DESKTOP_BUILD.read_text(encoding="utf-8")
        self.assertIn("app/src/main/res/drawable", build)
        self.assertIn("include 'thumb_*.xml'", build)
        self.assertIn("into 'thumbnails'", build)
        icons = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/IssuerIcons.kt")\
            .read_text(encoding="utf-8")
        self.assertIn("ThumbnailAssets", icons)
        self.assertIn('"/thumbnails/"', icons)
        cards = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/EntryCards.kt")\
            .read_text(encoding="utf-8")
        self.assertIn("IssuerIcons.artwork(entry.getThumbnailName())", cards)
        # A hidden entry must keep showing the letter tile, never a brand hint.
        self.assertIn("avatar.initial = initial(", cards)


class OtpAmberColorTest(unittest.TestCase):
    def test_amber_token_exists_and_is_readable_on_every_forest_surface(self):
        self.assertEqual("#FFC857", color("forest_otp_amber"))
        for surface in ("forest_background", "forest_background_deep", "forest_surface",
                        "forest_surface_elevated", "forest_surface_variant", "forest_appbar"):
            with self.subTest(surface=surface):
                self.assertGreaterEqual(contrast("forest_otp_amber", surface), 4.5)
        # The warning color stays distinct from the resting token color.
        self.assertNotEqual(color("forest_otp_amber"), color("expiring_red_dark"))

    def test_theme_attribute_is_defined_wherever_the_expiring_color_is(self):
        styles = ET.parse(RES / "values/styles.xml").getroot()
        definitions = {}
        for style in styles.findall("style"):
            items = {(item.attrib["name"]): (item.text or "").strip() for item in style.findall("item")}
            definitions[style.attrib["name"]] = items
        carriers = [name for name, items in definitions.items() if "colorExpiring" in items]
        self.assertTrue(carriers)
        for name in carriers:
            self.assertEqual("@color/forest_otp_amber", definitions[name].get("colorOtp"), name)
        attr_names = {attr.attrib["name"] for attr in styles.findall("attr")}
        self.assertIn("colorOtp", attr_names)

    def test_token_text_uses_the_amber_attribute_on_android(self):
        for layout in ("component_card_compact.xml", "component_card_default.xml", "component_card_full.xml"):
            root = ET.parse(RES / "layout" / layout).getroot()
            for view_id in ("valueText", "valueTextPrev"):
                node = root.find(f".//*[@{ANDROID}id='@+id/{view_id}']")
                self.assertIsNotNone(node, (layout, view_id))
                self.assertEqual("?attr/colorOtp", node.get(ANDROID + "textColor"), (layout, view_id))
            progress = root.find(f".//*[@{ANDROID}id='@+id/cardProgressBar']")
            self.assertEqual("@color/forest_otp_amber",
                             progress.get("{http://schemas.android.com/apk/res-auto}mpb_progressTint"), layout)
        holder = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntryViewHolder.kt")\
            .read_text(encoding="utf-8")
        self.assertIn("R.attr.colorOtp", holder)
        self.assertIn("R.attr.colorExpiring", holder)

    def test_desktop_cards_use_the_amber_token_for_revealed_codes(self):
        cards = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/EntryCards.kt")\
            .read_text(encoding="utf-8")
        theme = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/ForestTheme.kt")\
            .read_text(encoding="utf-8")
        self.assertIn("val OTP_AMBER = ColorUIResource(0xFF, 0xC8, 0x57)", theme)
        self.assertIn("else -> ForestTheme.OTP_AMBER", cards)
        self.assertIn("expiring -> ForestTheme.WARNING", cards)
        # Hover and selection lift the card with a gradient instead of a flat fill.
        self.assertIn("fun cardPaint(height: Int, hovered: Boolean, selected: Boolean): Paint", theme)
        self.assertIn("ForestTheme.cardPaint(height, hovered, selected)", cards)


if __name__ == "__main__":
    unittest.main()
