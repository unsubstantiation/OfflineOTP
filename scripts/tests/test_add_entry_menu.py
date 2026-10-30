"""Static guards for the Android add menu; real layout and touch need instrumentation."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
MAIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt"
EDITOR = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Dialogs/ManualEntryDialog.kt"
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"


class AddEntryMenuTest(unittest.TestCase):
    def test_all_actions_have_distinct_icons_and_accessible_titles(self):
        menu = ET.parse(RES / "menu/menu_fab.xml").getroot()
        actions = {item.attrib[ANDROID + "id"]: item for item in menu.findall("item")}
        expected = {
            "@+id/fabEnterDetails": ("button_enter_details", "ic_edit_white"),
            "@+id/fabAddSecureNote": ("button_add_secure_note", "ic_note_white"),
            "@+id/fabAddSshKey": ("button_add_ssh_key", "ic_key_white"),
            "@+id/fabImportPasswords": ("password_import", "ic_password_import_white"),
            "@+id/fabScanQRFromImage": ("button_qr_from_image", "ic_image_white"),
            "@+id/fabScanQR": ("button_scan_qr", "ic_camera_white"),
        }
        self.assertEqual(set(expected), set(actions))
        strings = {s.attrib["name"]: "".join(s.itertext())
                   for s in ET.parse(RES / "values/strings_main.xml").getroot().findall("string")}
        for action_id, (title, icon) in expected.items():
            item = actions[action_id]
            self.assertEqual("@string/" + title, item.attrib[ANDROID + "title"])
            self.assertTrue(strings[title].strip(), title)
            self.assertEqual("@drawable/" + icon, item.attrib[ANDROID + "icon"])
            vector = ET.parse(RES / "drawable" / (icon + ".xml")).getroot()
            self.assertEqual("vector", vector.tag)
        self.assertEqual("Add secure note", strings["button_add_secure_note"])
        self.assertEqual("Add SSH key", strings["button_add_ssh_key"])

    def test_action_routes_keep_the_session_guard_and_qr_paths(self):
        main = MAIN.read_text(encoding="utf-8")
        actions = main[main.index("dial.setOnActionSelectedListener"):
                       main.index("dial.setOnChangeListener")]
        self.assertLess(actions.index("if (VaultSession.getKey() == null)"),
                        actions.index("val actionId"))
        for action_id, target in {
            "fabAddSecureNote": r"ManualEntryDialog\.showNew\([^\n]*Entry\.OTPType\.NOTE\)",
            "fabAddSshKey": r"ManualEntryDialog\.showNew\([^\n]*Entry\.OTPType\.SSH_KEY\)",
            "fabImportPasswords": r"choosePasswordImport\(\)",
            "fabEnterDetails": r"ManualEntryDialog\.show\(this, settings!!, adapter!!\)",
            "fabScanQR": r"scanQRCode\(\)",
            "fabScanQRFromImage": r"showOpenFileSelector\(Constants\.INTENT_MAIN_QR_OPEN_IMAGE\)",
        }.items():
            self.assertRegex(actions, rf"actionId == R\.id\.{action_id}\)\s*{target}")

    def test_type_preselection_reuses_editor_without_changing_edit_path(self):
        editor = EDITOR.read_text(encoding="utf-8")
        self.assertIn("showEditor(callingActivity, settings, adapter, null, null, type)", editor)
        self.assertIn("showEditor(callingActivity, settings, adapter, oldEntry, updateCallback, null)", editor)
        self.assertRegex(editor, r"if \(isNewEntry && initialType != null\)\s*"
                                 r"typeInput\.setSelection\(typeAdapter\.getPosition\(initialType\)\)")
        self.assertIn("activeDialog?.dismiss()", editor)
        for field in ("secretInput", "sshPublicInput", "otpUriInput"):
            self.assertIn(field + '.setText("")', editor)
        adapter = (ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntriesCardAdapter.kt")
        self.assertIn("ManualEntryDialog.dismissActive()", adapter.read_text(encoding="utf-8"))

    def test_menu_is_bottom_anchored_scrollable_and_above_overlay(self):
        root = ET.parse(RES / "layout/activity_main.xml").getroot()
        scroll = root.find("ScrollView")
        self.assertIsNotNone(scroll)
        self.assertEqual("@+id/speedDialScroll", scroll.attrib[ANDROID + "id"])
        self.assertEqual("wrap_content", scroll.attrib[ANDROID + "layout_height"])
        self.assertEqual("bottom|end", scroll.attrib[ANDROID + "layout_gravity"])
        self.assertEqual("@dimen/sd_open_elevation", scroll.attrib[ANDROID + "elevation"])
        self.assertEqual("vertical", scroll.attrib[ANDROID + "scrollbars"])
        self.assertEqual("false", scroll.attrib[ANDROID + "fadeScrollbars"])
        self.assertEqual("com.leinardi.android.speeddial.SpeedDialView$SnackbarBehavior",
                         scroll.attrib[APP + "layout_behavior"])
        dial = scroll.find("com.leinardi.android.speeddial.SpeedDialView")
        self.assertIsNotNone(dial)
        self.assertEqual("@+id/speedDial", dial.attrib[ANDROID + "id"])
        self.assertEqual("@id/speedDialOverlay", dial.attrib[APP + "sdOverlayLayout"])

        main = MAIN.read_text(encoding="utf-8")
        prepare = main[main.index("private fun prepareExpandedSpeedDial"):
                       main.index("private fun restoreSortMode")]
        self.assertIn("observer.removeOnGlobalLayoutListener(this)", prepare)
        self.assertIn("currentObserver.removeOnGlobalLayoutListener(this)", prepare)
        self.assertIn("if (dial.isOpen())", prepare)
        self.assertLess(prepare.index("scroll.scrollTo(0, dial.height)"),
                        prepare.index("focusFirstSpeedDialAction(dial)"))

    def test_direct_paths_and_small_viewport_have_device_coverage(self):
        path = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp/AddEntryMenuDeviceTest.kt"
        test = path.read_text(encoding="utf-8")
        for expected in ("directActionsValidateSaveAndRelockWithoutChangingManualEntry",
                         "shortAddMenuScrollsAllActionsAndReceivesTouchesAboveOverlay",
                         "DatabaseHelper.readVault", "VaultSession.lock()", "getGlobalVisibleRect",
                         "activity.dispatchTouchEvent", "32 * 1024 + 1", '"🔐".repeat(198)'):
            self.assertIn(expected, test)


if __name__ == "__main__":
    unittest.main()
