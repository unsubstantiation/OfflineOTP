"""Keep the Android-free thumbnail name catalog identical to the resource-backed enum.

The entry model stores a thumbnail *name* string and resolves issuer matches through
``ThumbnailCatalog`` (pure JVM, headed for the shared core), while ``EntryThumbnail`` binds those
same names to Android drawables. The persisted names and their declaration order must not drift,
or a vault written on one platform could resolve to a different logo on another. This guard parses
both declarations from source so the relationship cannot silently change.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UTIL = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities"
CORE_UTIL = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities"
ENUM_FILE = UTIL / "EntryThumbnail.kt"  # stays in app: binds names to Android drawables
CATALOG_FILE = CORE_UTIL / "ThumbnailCatalog.kt"
TOKEN_FORMAT_FILE = CORE_UTIL / "TokenFormat.kt"


def enum_constant_names():
    text = ENUM_FILE.read_text(encoding="utf-8")
    block = re.search(r"enum class EntryThumbnails.*?\{(.*?)\n        \}", text, re.S).group(1)
    # Deliberately matches a lowercase first letter too: three constants were once declared as
    # `netcup`, `netlify` and `phpMyAdmin`, which every [A-Z]-anchored guard silently skipped.
    return re.findall(r"^\s*([A-Za-z][A-Za-z0-9_]*)\(R\.", block, re.M)


def referenced_drawables():
    text = ENUM_FILE.read_text(encoding="utf-8")
    block = re.search(r"enum class EntryThumbnails.*?\{(.*?)\n        \}", text, re.S).group(1)
    return re.findall(r"R\.drawable\.([a-z0-9_]+)", block)


def catalog_names():
    text = CATALOG_FILE.read_text(encoding="utf-8")
    block = re.search(r"val NAMES: List<String> = listOf\((.*?)\)", text, re.S).group(1)
    return re.findall(r'"([A-Za-z0-9_]+)"', block)


class ThumbnailCatalogTest(unittest.TestCase):
    def test_enum_constants_are_pascal_case(self):
        lowercased = [name for name in enum_constant_names() if not name[0].isupper()]
        self.assertEqual([], lowercased,
                         "enum constant names are persisted in vaults via ThumbnailCatalog; a "
                         "lowercase name cannot match its catalog entry")

    def test_every_thumbnail_drawable_is_reachable(self):
        referenced = set(referenced_drawables())
        bundled = {path.stem for path in (ROOT / "app/src/main/res/drawable").glob("thumb_*.xml")}
        self.assertEqual(set(), bundled - referenced,
                         "thumb_* drawables that no enum constant can select")

    def test_catalog_matches_the_resource_enum_in_order(self):
        enum_names = enum_constant_names()
        catalog = catalog_names()
        self.assertGreater(len(enum_names), 100, "expected the full brand enum, not a stub")
        self.assertEqual(enum_names, catalog)
        self.assertEqual("Default", catalog[0], "the default logo must remain first")
        self.assertEqual(len(catalog), len(set(catalog)), "thumbnail names must be unique")

    def test_pure_helpers_have_no_android_imports(self):
        for path in (CATALOG_FILE, TOKEN_FORMAT_FILE):
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(
                text, r"(?m)^import (android|androidx)\.",
                f"{path.name} must stay Android-free in the shared :core module",
            )

    def test_catalog_exposes_the_expected_resolution_entry_points(self):
        text = CATALOG_FILE.read_text(encoding="utf-8")
        for name in ("normalizeName", "resolveName", "DEFAULT_NAME"):
            self.assertIn(name, text)


if __name__ == "__main__":
    unittest.main()
