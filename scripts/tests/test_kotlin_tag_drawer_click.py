"""Source-level guards for the tag drawer click handler.

`TagsAdapter` renders the entry count next to a tag ("Alpha (3)", see #994) whenever
`EntriesCardAdapter.updateTagsFilter` has run, and the drawer activity cannot be instantiated in
the desktop harness, so pin the handler shape: the clicked tag must be read from the adapter by
position, never parsed back out of the decorated row text. `Checks.kt` covers the adapter side
(`getItem` follows the sorted display order, a decorated row still toggles its tag).
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt")


def click_listener_body(text):
    start = text.index("drawerList.onItemClickListener")
    # The listener ends at the next top-level closing brace followed by a blank line.
    end = text.index("\n        }\n", start)
    return text[start:end]


class DrawerTagClickGuardTest(unittest.TestCase):
    def setUp(self):
        self.text = MAIN.read_text(encoding="utf-8")
        self.listener = click_listener_body(self.text)

    def test_clicked_tag_is_resolved_by_position(self):
        self.assertIn("drawerAdapter.getItem(position)", self.listener)

    def test_decorated_row_text_is_not_parsed_back_into_a_tag(self):
        # The rejected shape stored `checkedTextView.text.toString()` ("Alpha (3)") as the tag.
        self.assertNotIn(".text.toString()", self.listener)
        self.assertNotRegex(self.listener, re.compile(r"setTag(State|Toggle)\(checkedTextView"))

    def test_the_listener_still_updates_the_persisted_toggle(self):
        self.assertIn("settings!!.setTagToggle(tag, checkedTextView.isChecked)", self.listener)
        self.assertIn("drawerAdapter.setTagState(tag, checkedTextView.isChecked)", self.listener)


if __name__ == "__main__":
    unittest.main()
