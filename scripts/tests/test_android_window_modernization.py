"""Source guards for non-deprecated window setup; device IME checks remain required."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/kotlin/com/fairuniversal/offlineotp'


class AndroidWindowModernizationTest(unittest.TestCase):
    def test_themed_host_leaves_system_bar_color_to_theme(self):
        host = (APP / 'Activities/ThemedActivity.kt').read_text()
        self.assertNotIn('setNavigationBarColor(', host)
        self.assertIn('ActivityContentInsets.install(this)', host)
        theme = (ROOT / 'app/src/main/res/values/styles.xml').read_text()
        self.assertIn('name="android:navigationBarColor"', theme)

    def test_dialogs_own_insets_after_their_content_exists(self):
        manual = (APP / 'Dialogs/ManualEntryDialog.kt').read_text()
        self.assertLess(manual.index('dialog.show()'), manual.index('ActivityContentInsets.installDialog('))
        password = (APP / 'Dialogs/PasswordEntryDialog.kt').read_text()
        self.assertIn('override fun onCreate(savedInstanceState: android.os.Bundle?)', password)
        self.assertLess(password.index('super.onCreate(savedInstanceState)'),
                        password.index('ActivityContentInsets.installDialog('))
        for text in (manual, password):
            self.assertNotIn('SOFT_INPUT_ADJUST_RESIZE', text)
        helper = (APP / 'Utilities/ActivityContentInsets.kt').read_text()
        self.assertIn('window.decorView.findViewById<View>(android.R.id.content)', helper)
        self.assertIn('WindowCompat.setDecorFitsSystemWindows(window, false)', helper)
        self.assertIn('WindowInsetsCompat.Type.ime()', helper)

    def test_entry_edit_uses_compiler_proven_non_null_values(self):
        source = (APP / 'Dialogs/ManualEntryDialog.kt').read_text()
        self.assertNotIn('oldEntry!!', source)
        self.assertNotIn('getSecretEncoded() ?: ""', source)
