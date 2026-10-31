"""A revealed secure note must be readable in full on both clients.

Notes are the one entry kind whose content is meant to be read rather than copied out of a dialog,
so the cards show the whole note (wrapped, no truncation, no placeholder) once its details are
visible. These guards complement the desktop unit tests and the Android device tests.
"""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
CARDS = ("component_card_compact.xml", "component_card_default.xml", "component_card_full.xml")


class AndroidVisibleNoteTest(unittest.TestCase):
    def test_every_card_has_a_wrapping_note_view(self):
        for layout in CARDS:
            root = ET.parse(RES / "layout" / layout).getroot()
            note = root.find(f".//*[@{ANDROID}id='@+id/noteText']")
            with self.subTest(layout=layout):
                self.assertIsNotNone(note, "card layouts need a noteText view")
                self.assertEqual("wrap_content", note.get(ANDROID + "layout_height"))
                # Nothing may cap or shrink the note: no ellipsize, no maxLines, no autosizing.
                for attribute in ("maxLines", "ellipsize", "autoSizeTextType"):
                    self.assertIsNone(note.get(ANDROID + attribute), attribute)
                self.assertEqual("?android:attr/textColorPrimary", note.get(ANDROID + "textColor"))

    def test_the_card_shows_the_whole_note_instead_of_a_placeholder(self):
        holder = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntryViewHolder.kt")\
            .read_text(encoding="utf-8")
        self.assertIn("entry.getNote()", holder)
        self.assertIn("noteText.visibility = if (showWholeNote) View.VISIBLE else View.GONE", holder)
        self.assertIn("value.visibility = if (showWholeNote) View.GONE else View.VISIBLE", holder)
        # The token line keeps amber; the note uses body text so it never reads as a code.
        self.assertNotIn("secure_note_hidden", holder)
        strings = (RES / "values/strings_main.xml").read_text(encoding="utf-8")
        self.assertNotIn("secure_note_hidden", strings)
        # The desktop client dropped its placeholder too.
        model = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/model/EntryTableModel.kt")\
            .read_text(encoding="utf-8")
        self.assertIn("entry.isNote() -> entry.getNote()", model)
        self.assertNotIn("[Note hidden]", model)


class DesktopVisibleNoteTest(unittest.TestCase):
    def setUp(self):
        self.cards = (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/EntryCards.kt")\
            .read_text(encoding="utf-8")

    def test_notes_are_painted_wrapped_and_the_row_grows_to_fit(self):
        self.assertIn("internal object NoteText", self.cards)
        self.assertIn("fun wrap(text: String, width: Int, metrics: FontMetrics): List<String>", self.cards)
        self.assertIn("internal object CardRowHeights", self.cards)
        self.assertIn("table.setRowHeight(row, height)", self.cards)
        # The note is drawn line by line, never as Swing HTML or a truncated label.
        self.assertIn("class NoteView", self.cards)
        self.assertIn("for ((index, line) in NoteText.lines(text, width, metrics).withIndex())", self.cards)
        self.assertIn("(valueSlot.layout as CardLayout).show(valueSlot, if (noteShown) NOTE else VALUE)",
                      self.cards)

    def test_note_height_measurement_and_painting_share_one_wrapper(self):
        # One line-breaking routine behind both the row measurement and the painter.
        self.assertEqual(1, self.cards.count("fun wrap("),
                         "line breaking must have a single definition")
        self.assertIn("fun lines(text: String, width: Int, metrics: FontMetrics): List<String> "
                      "= wrap(text, width, metrics)", self.cards)
        self.assertIn("lines(text, width, metrics).size * metrics.height", self.cards)

    def test_other_kinds_keep_their_redacted_placeholders(self):
        for placeholder in ("[Password hidden]", "[Password file]", "[SSH keys hidden]", "[Email account]"):
            self.assertIn(placeholder,
                          (ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/model/EntryTableModel.kt")
                          .read_text(encoding="utf-8"))


class DocumentationTest(unittest.TestCase):
    def test_readme_describes_visible_notes(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        section = readme.split("### Secure notes", 1)[1].split("###", 1)[0]
        self.assertIn("in full", section)
        self.assertNotIn("Notes are hidden in the list", section)
        self.assertRegex(section, r"(?i)reveal")


if __name__ == "__main__":
    unittest.main()
