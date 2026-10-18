"""Regression guards for the per-entry popup menu.

The original defect: ``EntriesCardAdapter.showPopupMenu`` revealed the mOTP PIN row with a
positional ``popup.getMenu().getItem(ESTABLISH_PIN_MENU_INDEX)`` lookup. The constant was ``4``,
i.e. ``menu_popup_remove``, so the "Establish PIN" action (menu index 3,
``android:visible="false"``) never became visible and the action was unreachable from the menu.
The fix looks the item up by id instead. These tests fail if a positional lookup, a
``*_MENU_INDEX`` constant, or a dangling ``R.id.menu_*`` reference reappears, or if the
mOTP reveal stops using the id-based lookup.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
RES_MENU = ROOT / "app/src/main/res/menu"


def app_sources():
    return sorted((APP_KOTLIN).rglob("*.kt"))


def menu_ids_declared():
    ids = set()
    for menu in RES_MENU.glob("*.xml"):
        ids.update(re.findall(r'<item[^>]*android:id="@\+id/([A-Za-z0-9_]+)"', menu.read_text(encoding="utf-8")))
    return ids


class PopupMenuItemLookupTest(unittest.TestCase):
    def test_no_positional_menu_item_lookup(self):
        """No ``getMenu().getItem(<position>)`` call and no ``*_MENU_INDEX`` constant may exist."""
        offenders = []
        for source in app_sources():
            text = source.read_text(encoding="utf-8")
            if re.search(r"getMenu\(\)\s*\.\s*getItem\(", text):
                offenders.append(f"{source.name}: positional getMenu().getItem() lookup")
            for match in re.finditer(r"\b([A-Z][A-Z0-9_]*_MENU_INDEX)\b", text):
                offenders.append(f"{source.name}: menu index constant {match.group(1)}")
        self.assertEqual([], offenders,
                         "Menu items must be looked up by id, never by their position in the XML")

    def test_menu_id_references_resolve(self):
        """Every ``R.id.menu_*`` referenced from Kotlin must be declared in ``res/menu/``."""
        declared = menu_ids_declared()
        self.assertTrue(declared, "no menu ids found in res/menu")
        referenced = set()
        for source in app_sources():
            referenced.update(re.findall(r"R\.id\.(menu_[A-Za-z0-9_]+)", source.read_text(encoding="utf-8")))
        missing = referenced - declared
        self.assertEqual(set(), missing, "R.id.menu_* references without a menu declaration")

    def test_establish_pin_revealed_by_id(self):
        """The mOTP PIN row is revealed with a by-id lookup and stays hidden by default."""
        adapter = APP_KOTLIN / "View/EntriesCardAdapter.kt"
        text = adapter.read_text(encoding="utf-8")
        self.assertIn(
            'popup.getMenu().findItem(R.id.menu_popup_establishPin)?.setVisible(true)',
            text,
            "the mOTP PIN row must be revealed by id, not by menu position",
        )
        self.assertNotIn("menu_popup_establishPin),", text)

        popup_menu = (RES_MENU / "menu_popup.xml").read_text(encoding="utf-8")
        self.assertIn('android:id="@+id/menu_popup_establishPin"', popup_menu)
        self.assertRegex(
            popup_menu,
            re.compile(
                r'<item\s+android:id="@\+id/menu_popup_establishPin"[^>]*android:visible="false"',
                re.DOTALL,
            ),
            "the mOTP PIN row must stay hidden by default (only shown for mOTP entries)",
        )
        # The popup handler must route the item to establishPIN().
        self.assertRegex(text, r"R\.id\.menu_popup_establishPin\s*\)\s*\{\s*establishPIN\(pos\)")


if __name__ == "__main__":
    unittest.main()
