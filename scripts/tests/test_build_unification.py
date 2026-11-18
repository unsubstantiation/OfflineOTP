"""Structural gates for consolidation; these do not claim native UI/runtime validation."""
from pathlib import Path
import json
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/kotlin/com/fairuniversal/offlineotp'
UI = ROOT / 'ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui'

class BuildUnificationTest(unittest.TestCase):
    def test_generated_theme_bindings_are_fresh(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/generate-theme.py'), '--check'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        catalog = json.loads((ROOT / 'shared/theme/forest.json').read_text())
        # Existing Android library integrations keep their resource aliases.
        for name in ('sd_label_text_color', 'about_libraries_card', 'forest_scrim', 'forest_otp_amber'):
            self.assertIn(name, catalog['androidResources'])

    def test_password_form_is_shared_and_obsolete_forms_stay_removed(self):
        android = (ROOT / 'ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultComposeView.kt').read_text()
        desktop = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text()
        self.assertIn('PasswordEntryDialog(', android)
        self.assertIn('PasswordEntryDialog(', desktop)
        for name in ('EmailEntryDialog.kt', 'PasswordEntryEditDialog.kt'):
            self.assertFalse((APP / 'Dialogs' / name).exists())
        form = (UI / 'vault/PasswordEntryDialog.kt').read_text()
        self.assertIn('BrowserPasswordCsv.MAX_FIELD_CHARS', form)
        self.assertIn('verticalScroll(rememberScrollState())', form)
        self.assertIn('Show password', form)
        self.assertNotIn('rememberSaveable', form)

    def test_native_password_policy_and_shared_contract_are_wired(self):
        sheet = (ROOT / 'ios/App/Sources/CredentialDetailViews.swift').read_text()
        for guard in ('store.generation == openedGeneration', 'store.entries[index].fields == openedFields',
                      'store.isRevealed(store.entries[index])', 'PasswordCsvExport.supportsCommonEdit'):
            self.assertIn(guard, sheet)
        for source in ('desktop/src/test/kotlin/com/fairuniversal/offlineotp/desktop/control/PasswordEditContractTest.kt',
                       'ios/Tests/OfflineOTPKitTests/PasswordCsvExportTests.swift'):
            self.assertIn('password-edit-contract.json', (ROOT / source).read_text())

    def test_import_forms_share_scrollable_body(self):
        components = (UI / 'vault/VaultComponents.kt').read_text()
        body = components[components.index('internal fun ScrollableDialogBody'):]
        self.assertIn('Column(Modifier.verticalScroll(rememberScrollState()))', body)
        for name in ('OpenPgpImportDialog', 'EmailImportDialog', 'PasswordImportDialog',
                     'PreferencesImportDialog', 'UriImportDialog'):
            form = (UI / 'vault' / (name + '.kt')).read_text()
            self.assertIn('text = { ScrollableDialogBody {', form)
            self.assertIn('confirmButton =', form)
            self.assertIn('dismissButton =', form)
        pgp = (UI / 'vault/OpenPgpImportDialog.kt').read_text()
        self.assertIn('else "Merge", enabled = !busy', pgp)
        self.assertIn('else "Replace all current entries", enabled = !busy', pgp)

    def test_auto_lock_custom_values_remain_visible_on_ios_and_desktop(self):
        swift = (ROOT / 'ios/App/Sources/SettingsView.swift').read_text()
        self.assertIn('AutoLockOptions.including(store.inactivityLockSeconds)', swift)
        self.assertIn('AutoLockOptions.label(seconds)', swift)
        self.assertIn('sort order and inactivity delay', swift)
        form = (UI / 'vault/AutoLockDialog.kt').read_text()
        self.assertIn('(options + currentSeconds).distinct()', form)
        self.assertIn('AutoLockOptions.label(seconds)', form)
        self.assertIn('text = { ScrollableDialogBody {', form)
        for path in ('desktop/src/test/kotlin/com/fairuniversal/offlineotp/desktop/control/AutoLockOptionsContractTest.kt',
                     'ios/Tests/OfflineOTPKitTests/AutoLockOptionsTests.swift'):
            self.assertIn('auto-lock-options.json', (ROOT / path).read_text())
