"""Regression guards for the andOTP carry-over pass (OfflineOTP issue #39).

Four confirmed defects from the upstream triage are pinned here:

1. **#591 — the first-run wizard forced portrait.** ``IntroScreenActivity`` carried
   ``android:screenOrientation="portrait"``. Removing the lock alone was not enough: the
   wizard commits the vault on its last slide, so a rotation had to keep the completed
   outcome (and the one-use derived-key handoff) instead of re-running setup or stranding the
   user on a slide that can no longer advance.

2. **#712 — re-lock on inactivity ignored background/screen-off time.** The inactivity
   deadline was a ``Handler`` delay cancelled in ``onPause``/restarted on ``onResume``, so the
   time spent with the screen off never counted. ``ScreenTimerController`` now keeps an
   ``elapsedRealtime`` deadline across the suspension and ``MainActivity`` checks it before
   re-arming. The accessor's fallback must also match the preference's 30-second default
   instead of a hardcoded zero that silently disables the timer.

3. **#880 — the "Hidden" placeholder wrapped onto two lines.** The three card layouts now keep
   the localized label on one auto-sized line.

4. **#805 — the keyboard hid the entry dialog's Save/Cancel buttons.** The entry and password
   dialogs ask the window to resize with the IME instead of being panned.
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
KOTLIN_APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
LAYOUTS = ROOT / "app/src/main/res/layout"
VALUES = ROOT / "app/src/main/res/values"


def read(path):
    return Path(path).read_text(encoding="utf-8")


class IntroScreenOrientationTest(unittest.TestCase):
    """#591: no forced orientation, and a completed setup survives recreation."""

    def test_no_activity_forces_an_orientation(self):
        root = ET.parse(MANIFEST).getroot()
        locks = []
        for activity in root.iter("activity"):
            value = activity.get(ANDROID + "screenOrientation")
            if value is not None:
                locks.append((activity.get(ANDROID + "name"), value))
        self.assertEqual([], locks, "activities must follow the user's auto-rotate setting")

    def test_intro_activity_stays_unexported_and_themed(self):
        root = ET.parse(MANIFEST).getroot()
        intro = [a for a in root.iter("activity")
                 if a.get(ANDROID + "name") == ".Activities.IntroScreenActivity"]
        self.assertEqual(1, len(intro))
        self.assertEqual("false", intro[0].get(ANDROID + "exported"))

    def test_setup_outcome_is_saved_and_restored(self):
        text = read(KOTLIN_APP / "Activities/IntroScreenActivity.kt")
        self.assertIn('outState.putBoolean(STATE_SETUP_FINISHED, setupFinished)', text)
        self.assertIn("savedInstanceState?.getBoolean(STATE_SETUP_FINISHED, false)", text)
        self.assertIn("finished.restoreCompletedSetup()", text)
        # Only the boolean is saved: no key material may enter the saved-state bundle.
        saved_body = text.split("override fun onSaveInstanceState(outState: Bundle) {", 1)[1]
        saved_body = saved_body.split("\n    }", 1)[0]
        self.assertIn("outState.putBoolean", saved_body)
        self.assertNotIn("encryptionKey", saved_body)
        self.assertNotIn("putByteArray", saved_body)

    def test_completed_setup_does_not_run_again_after_recreation(self):
        text = read(KOTLIN_APP / "Activities/IntroScreenActivity.kt")
        self.assertIn("if (!setupFinished && position == getCount() - 1", text,
                      "a restored, completed wizard must not save the settings a second time")
        finished = text.split("open class IntroFinishedFragment", 1)[1]
        self.assertIn("fun restoreCompletedSetup()", finished)
        self.assertIn("completedSetupRestored", finished,
                      "the restored state must also apply once the fragment view exists")

    def test_derived_key_is_published_as_soon_as_the_vault_is_committed(self):
        text = read(KOTLIN_APP / "Activities/IntroScreenActivity.kt")
        self.assertIn("VaultSession.offerDerivedKey(it.copyOf(), result.generation)", text,
                      "a rotation before onSendActivityResult must not drop the derived key")


class InactivityRelockTest(unittest.TestCase):
    """#712: background time counts, and the default delay matches the preference."""

    def test_controller_keeps_the_deadline_across_a_suspension(self):
        text = read(KOTLIN_APP / "Utilities/ScreenTimerController.kt")
        self.assertIn("fun suspendInactivity()", text)
        self.assertIn("fun hasInactivityExpired(): Boolean", text)
        self.assertIn("fun suspendForeground()", text)
        self.assertRegex(text, r"private var deadline: Long\? = null")
        suspend = text.split("fun suspendInactivity()", 1)[1].split("fun hasInactivityExpired", 1)[0]
        self.assertNotIn("deadline = null", suspend,
                         "suspending must keep the deadline: background time is not free time")

    def test_main_activity_checks_expiry_before_rearming(self):
        text = read(KOTLIN_APP / "Activities/MainActivity.kt")
        self.assertIn("screenTimers?.suspendForeground()", text)
        resume = text.split("public override fun onResume()", 1)[1].split("public override fun onPause", 1)[0]
        self.assertIn("if (inactivityRelockExpired())", resume)
        self.assertIn("authenticate(R.string.auth_msg_authenticate)", resume)
        # The re-arm has to happen before the unlock, or the unlock would immediately look
        # idle again; both must follow the expiry check that decides to relock.
        expired = resume.index("if (inactivityRelockExpired())")
        rearm = resume.index("restartInactivityTimer()", expired)
        unlock = resume.index("authenticate(R.string.auth_msg_authenticate)", expired)
        self.assertLess(expired, rearm)
        self.assertLess(rearm, unlock)

    def test_delay_default_matches_the_preference_resource(self):
        settings = read(KOTLIN_APP / "Utilities/Settings.kt")
        accessor = settings.split("fun getAuthInactivityDelay()", 1)[1].split("fun ", 1)[0]
        self.assertIn("R.integer.settings_default_auth_inactivity_delay", accessor,
                      "the accessor must fall back to the same default the preference uses")
        values = read(VALUES / "settings.xml")
        default = re.search(r'<integer name="settings_default_auth_inactivity_delay">(\d+)</integer>',
                            values)
        self.assertIsNotNone(default)
        self.assertGreater(int(default.group(1)), 0, "a zero default would disable the timer")

    def test_preference_dependency_and_bounds_stay_declared(self):
        prefs = read(ROOT / "app/src/main/res/xml/preferences.xml")
        self.assertIn('android:dependency="@string/settings_key_auth_inactivity"', prefs)
        values = read(VALUES / "settings.xml")
        self.assertIn('<integer name="settings_auth_min_inactivity_delay">', values)


class HiddenLabelLayoutTest(unittest.TestCase):
    """#880: the hidden-token placeholder stays on a single, auto-sized line."""

    CARD_LAYOUTS = ("component_card_compact.xml", "component_card_default.xml",
                    "component_card_full.xml")

    def _hidden_label(self, name):
        root = ET.parse(LAYOUTS / name).getroot()
        for view in root.iter("TextView"):
            if view.get(ANDROID + "text") == "@string/label_hidden":
                return view
        return None

    def test_every_card_keeps_the_hidden_label_on_one_auto_sized_line(self):
        for name in self.CARD_LAYOUTS:
            view = self._hidden_label(name)
            self.assertIsNotNone(view, f"{name} lost its hidden-token label")
            self.assertEqual("1", view.get(ANDROID + "maxLines"), name)
            self.assertEqual("uniform", view.get(ANDROID + "autoSizeTextType"), name)
            minimum = view.get(ANDROID + "autoSizeMinTextSize")
            maximum = view.get(ANDROID + "autoSizeMaxTextSize")
            self.assertIsNotNone(minimum, name)
            self.assertIsNotNone(maximum, name)
            self.assertNotEqual(minimum, maximum, name)

    def test_the_label_stays_translatable(self):
        values = read(VALUES / "strings_main.xml")
        self.assertIn('<string name="label_hidden">', values,
                      "the localized label must not be replaced by a hardcoded symbol")
        locales = [p for p in (ROOT / "app/src/main/res").glob("values-*/strings_main.xml")
                   if 'name="label_hidden"' in read(p)]
        self.assertGreater(len(locales), 10, "the translated hidden label disappeared")


class DialogImeTest(unittest.TestCase):
    """#805: the entry and password dialogs resize with the keyboard."""

    def test_entry_dialog_resizes_with_the_ime(self):
        text = read(KOTLIN_APP / "Dialogs/ManualEntryDialog.kt")
        self.assertIn("SOFT_INPUT_ADJUST_RESIZE", text)
        dialog = ET.parse(LAYOUTS / "dialog_manual_entry.xml").getroot()
        self.assertEqual("ScrollView", dialog.tag,
                         "the entry form must stay scrollable so Save/Cancel remain reachable")

    def test_password_dialog_resizes_with_the_ime(self):
        text = read(KOTLIN_APP / "Dialogs/PasswordEntryDialog.kt")
        self.assertIn("SOFT_INPUT_ADJUST_RESIZE", text)


if __name__ == "__main__":
    unittest.main()
