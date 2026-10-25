"""Source-level guards for the tag drawer: deletion refresh and the per-tag entry counts.

`EntriesCardAdapter.removeItem` used to finish with a bare `saveEntries(...)` call. Every other
mutation path that can change the set of tags in use (`addEntry`, the edit dialog, renames) goes
through `saveAndRefresh(...)`, which reconciles `TagsAdapter` (rows, active state and the #994
per-tag entry counts) *and* re-filters the displayed list. Deleting therefore left the drawer
showing the deleted entry's tag - with its stale count - after the last entry carrying it was
removed, and selecting that row filtered the list down to nothing.

`TagsAdapter.setTags` only replaces the rows; the `"tag (n)"` decoration reads a separate map
pushed by `setTagCounts`. That push only happened in `EntriesCardAdapter.updateTagsFilter`, which
runs on a mutation, so a fresh start, an unlock or an import showed plain rows (and a stale count
for a tag a restored entry set no longer contains) until the user added, edited or deleted an
entry. Both `MainActivity` paths that rebuild the drawer now push `EntriesCardAdapter.getTagCounts`.

The deletion callback runs deep inside `showActionDialog`/`dialogs.delete` and needs a live
session key, a persisted vault and a shown dialog, so it cannot be driven from the desktop
harness; the shape is pinned here instead. `EntryList.getTagCounts` (called by
`EntriesCardAdapter.updateTagsFilter` and `getTagCounts`) is covered behaviourally in
`EntryListTest`.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntriesCardAdapter.kt"
MAIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt"


def function_body(text, signature):
    """The body of a member function, ending at the next member-level closing brace."""
    start = text.index(signature)
    end = text.index("\n    }\n", start)
    return text[start:end]


class DeleteRefreshesTagDrawerTest(unittest.TestCase):
    def setUp(self):
        self.text = ADAPTER.read_text(encoding="utf-8")
        self.body = function_body(self.text, "fun removeItem(")

    def test_removal_goes_through_the_shared_refresh_path(self):
        self.assertIn(
            "saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled())",
            self.body,
            "deleting an entry must reconcile the tag drawer, not only save the vault",
        )

    def test_removal_does_not_fall_back_to_a_bare_save(self):
        self.assertNotRegex(
            self.body,
            re.compile(r"(?<!saveAnd)\bsaveEntries\("),
            "a bare saveEntries() after deletion leaves the deleted tag in the drawer",
        )

    def test_the_removed_entry_is_still_destroyed(self):
        # The seed wipe must stay in the finally block, whatever the save outcome is.
        self.assertRegex(self.body, re.compile(r"finally\s*\{\s*actual\.destroy\(\)"))


class DrawerCountsArePushedTest(unittest.TestCase):
    """`TagsAdapter.setTags` only replaces rows; the counts are a separate map (#994)."""

    def setUp(self):
        self.text = MAIN.read_text(encoding="utf-8")

    def test_the_initial_load_pushes_counts(self):
        body = function_body(self.text, "private fun populateAdapter()")
        self.assertIn("setTags(", body)
        self.assertIn("setTagCounts(", body)

    def test_rebuilding_the_drawer_pushes_counts(self):
        body = function_body(self.text, "fun refreshTags()")
        self.assertIn("drawerAdapter.setTags(", body)
        self.assertIn("drawerAdapter.setTagCounts(", body)

    def test_the_adapter_exposes_the_count_source(self):
        adapter = ADAPTER.read_text(encoding="utf-8")
        self.assertIn("fun getTagCounts(): Map<String, Int> = entries.getTagCounts()", adapter)
