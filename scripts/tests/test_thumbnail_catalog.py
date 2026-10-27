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
ENUM_FILE = UTIL / "EntryThumbnail.kt"
CATALOG_FILE = UTIL / "ThumbnailCatalog.kt"
TOKEN_FORMAT_FILE = UTIL / "TokenFormat.kt"


def enum_constant_names():
    text = ENUM_FILE.read_text(encoding="utf-8")
    block = re.search(r"enum class EntryThumbnails.*?\{(.*?)\n        \}", text, re.S).group(1)
    return re.findall(r"^\s*([A-Z][A-Za-z0-9_]*)\(R\.", block, re.M)


def catalog_names():
    text = CATALOG_FILE.read_text(encoding="utf-8")
    block = re.search(r"val NAMES: List<String> = listOf\((.*?)\)", text, re.S).group(1)
    return re.findall(r'"([A-Za-z0-9_]+)"', block)


class ThumbnailCatalogTest(unittest.TestCase):
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
                f"{path.name} must stay Android-free so it can move into the shared core",
            )

    def test_catalog_exposes_the_expected_resolution_entry_points(self):
        text = CATALOG_FILE.read_text(encoding="utf-8")
        for name in ("normalizeName", "resolveName", "DEFAULT_NAME"):
            self.assertIn(name, text)


if __name__ == "__main__":
    unittest.main()
