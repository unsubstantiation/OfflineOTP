"""Guards for the 2026-10-26 bugfix pass.

These pin call sites the desktop harness cannot instantiate (dialog preferences, the
material-intro pager, and the entry-card menu). The behavioural checks live in Checks.kt.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


def read(rel):
    return (APP / rel).read_text(encoding="utf-8")


class BackupPasswordDialogTest(unittest.TestCase):
    def test_dialog_stays_open_when_the_keystore_write_fails(self):
        text = read("Preferences/PasswordEncryptedPreference.kt")
        save = text.split("R.id.btnSave -> {", 1)[1].split("}", 1)[0]
        self.assertIn("if (encryptAndPersist(", save)
        self.assertNotIn("encryptAndPersist(passwordInput.text.toString())\n", save)
        self.assertIn("notifyEncryptionFailed()", text)
        # A failed write must not dismiss; only the success branch does.
        self.assertLess(save.index("encryptAndPersist"), save.index("dismiss()"))
        self.assertIn("if (encryptAndPersist", save)


class IntroRotationTest(unittest.TestCase):
    def test_finish_slide_persists_its_own_completed_state(self):
        text = read("Activities/IntroScreenActivity.kt")
        finished = text.split("open class IntroFinishedFragment", 1)[1].split("open class AndroidSyncFragment", 1)[0]
        self.assertIn("STATE_SETUP_COMPLETED", finished)
        self.assertIn("outState.putBoolean(STATE_SETUP_COMPLETED, canGoForward || completedSetupRestored)", finished)
        self.assertIn("savedInstanceState?.getBoolean(STATE_SETUP_COMPLETED, false)", finished)

    def test_slides_are_fresh_instances_and_then_adopted_from_the_pager(self):
        text = read("Activities/IntroScreenActivity.kt")
        created = text.split("override fun onCreate(savedInstanceState: Bundle?) {", 1)[1]
        created = created.split("override fun onSaveInstanceState", 1)[0]
        self.assertNotIn("filterIsInstance", created,
                         "already-added fragments must not be passed to SlideAdapter")
        self.assertIn("val encryption = EncryptionFragment()", created)
        self.assertIn("adoptDisplayedFragment(f)", created)
        self.assertIn("adoptDisplayedFragments()", created)
        self.assertIn("authentication.clearCredentialInputs()", created)


class ScreenshotAndCardRefreshTest(unittest.TestCase):
    def test_screenshot_policy_covers_the_shared_activity_base_and_the_wizard(self):
        base = read("Activities/BaseActivity.kt")
        intro = read("Activities/IntroScreenActivity.kt")
        settings = read("Activities/SettingsActivity.kt")
        self.assertIn("Tools.applyScreenshotPolicy(getWindow(), settings?.getScreenshotsEnabled() == true)", base)
        self.assertIn("Tools.applyScreenshotPolicy(getWindow(), Settings(this).getScreenshotsEnabled())", intro)
        self.assertIn("settings_key_enable_screenshot", settings)
        self.assertIn("Tools.applyScreenshotPolicy(getWindow(), settings!!.getScreenshotsEnabled())", settings)

    def test_individual_timeouts_rebind_cards_and_progress_animators_are_replaced(self):
        main = read("Activities/MainActivity.kt")
        listener = main.split("override fun onSharedPreferenceChanged", 1)[1].split("adapter?.clearFilter()", 1)[0]
        self.assertIn("settings_key_show_individual_timeouts", listener)
        holder = read("View/EntryViewHolder.kt")
        self.assertIn("progressAnimator?.cancel()", holder)
        self.assertIn("timeoutAnimator?.cancel()", main)

    def test_pin_menu_uses_the_entry_not_the_stale_row(self):
        text = read("View/EntriesCardAdapter.kt")
        menu = text.split("menu_popup_establishPin", 1)[1].split("menu_popup_remove", 1)[0]
        self.assertIn("presentation.indexOf(popupEntry)", menu)
        self.assertNotIn("establishPIN(pos)", menu)
        strokes = text.split("private fun sendKeystrokes", 1)[1].split("private fun showQRCode", 1)[0]
        self.assertIn("toast_no_keystroke_receiver", strokes)
        self.assertIn("updateLastUsedAndFrequency(pos, getRealIndex(pos))", strokes)

    def test_cancelled_encryption_auth_is_not_reported_as_failure(self):
        text = read("Activities/SettingsActivity.kt")
        auth = text.split("override fun onActivityResult", 1)[1].split("INTENT_SETTINGS_BACKUP_LOCATION", 1)[0]
        self.assertIn("resultCode != RESULT_CANCELED && !isFinishing()", auth)


if __name__ == "__main__":
    unittest.main()
