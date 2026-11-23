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
        adapter = (UI / 'vault/PasswordEntryDialog.kt').read_text()
        form = (ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/SharedPasswordEntryDialog.kt').read_text()
        self.assertIn('BrowserPasswordCsv.MAX_FIELD_CHARS', adapter)
        self.assertIn('SharedPasswordEntryDialog(', adapter)
        self.assertIn('verticalScroll(rememberScrollState())', form)
        self.assertIn('Show password', form)
        self.assertNotIn('rememberSaveable', form)

    def test_native_password_policy_and_shared_contract_are_wired(self):
        sheet = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        for guard in ('openedGeneration.map { store.generation == $0 } == true',
                      'store.entries[index].fields == openedFields',
                      'store.isRevealed(store.entries[index])', 'PasswordCsvExport.supportsCommonEdit'):
            self.assertIn(guard, sheet)
        for source in ('desktop/src/test/kotlin/com/fairuniversal/offlineotp/desktop/control/PasswordEditContractTest.kt',
                       'ios/Tests/OfflineOTPKitTests/PasswordCsvExportTests.swift'):
            self.assertIn('password-edit-contract.json', (ROOT / source).read_text())

    def test_import_forms_share_scrollable_body(self):
        foundation = ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui'
        components = (foundation / 'vault/DialogComponents.kt').read_text()
        body = components[components.index('fun ScrollableDialogBody'):]
        self.assertIn('Column(Modifier.verticalScroll(rememberScrollState()))', body)
        for path in (
            foundation / 'vault/OpenPgpImportDialog.kt',
            foundation / 'vault/EmailImportDialog.kt',
            foundation / 'vault/SharedPasswordImportDialog.kt',
            foundation / 'vault/UriImportDialog.kt',
            foundation / 'vault/PreferencesImportDialog.kt',
        ):
            form = path.read_text()
            self.assertIn('text = { ScrollableDialogBody {', form)
            self.assertIn('confirmButton =', form)
            self.assertIn('dismissButton =', form)
        pgp = (foundation / 'vault/OpenPgpImportDialog.kt').read_text()
        self.assertIn('else "Merge", enabled = !busy', pgp)
        self.assertIn('else "Replace all current entries", enabled = !busy', pgp)

    def test_auto_lock_custom_values_remain_visible_on_ios_and_desktop(self):
        swift = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('row("autoLock", "Auto-lock after inactivity"', swift)
        self.assertNotIn('AutoLockOptions.', swift)
        self.assertIn('store.setInactivityLockSeconds(seconds)', swift)
        form = (ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AutoLockDialog.kt').read_text()
        self.assertIn('(options + currentSeconds).distinct()', form)
        self.assertIn('AutoLockOptions.label(seconds)', form)
        self.assertIn('text = { ScrollableDialogBody {', form)
        for path in ('desktop/src/test/kotlin/com/fairuniversal/offlineotp/desktop/control/AutoLockOptionsContractTest.kt',):
            self.assertIn('auto-lock-options.json', (ROOT / path).read_text())

    def test_android_linscp_read_does_not_reimplement_desktop_dialog(self):
        main = (APP / 'Activities/MainActivity.kt').read_text()
        read = main[main.index('    private fun promptLinScpSecret('):main.index('    private fun finishLinScpImport(')]
        self.assertIn('view.showLinScpRead(', read)
        self.assertIn('view.ownsLinScpRead(request)', read)
        self.assertIn('dispose = { bytes.fill(0) }', read)
        self.assertNotIn('AlertDialog', read)
        self.assertNotIn('EditText', read)
        self.assertNotIn('linScpImportPrompt', main)
        host = (ROOT / 'ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultComposeView.kt').read_text()
        self.assertIn('LinScpImportDialog(reading.key', host)
        self.assertIn('LinScpImportDialog(importing.key', host)
        form = (ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/LinScpImportDialog.kt').read_text()
        self.assertIn('allowKeyFileSelection: Boolean = true', form)
        self.assertIn('val submitted = password; password = ""; onRead(submitted)', form)
        self.assertNotIn('rememberSaveable', form)

    def test_openpgp_recipient_bounds_email_identity_creation_and_persisted_tag_filters_are_aligned(self):
        bounds = (ROOT / 'ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortableInputBounds.kt').read_text()
        self.assertIn('const val MAX_OPENPGP_RECIPIENTS: Int = 64', bounds)
        self.assertIn('const val MAX_EMAIL_IDENTITIES: Int = 16', bounds)

        pgp_dialog = (ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/OpenPgpExportDialog.kt').read_text()
        self.assertIn('maxRecipients: Int = PortableInputBounds.MAX_OPENPGP_RECIPIENTS', pgp_dialog)
        desktop_pgp = (ROOT / 'desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopOpenPgpExport.kt').read_text()
        self.assertIn('MAX_RECIPIENT_FILES = OpenPgpKeyFilePolicy.MAX_RECIPIENTS', desktop_pgp)
        swift_pgp = (ROOT / 'ios/Sources/OfflineOTPKit/OpenPgp.swift').read_text()
        self.assertIn('public static let maxRecipients = 64', swift_pgp)
        swift_host = (ROOT / 'ios/App/Sources/ComposeVaultHost.swift').read_text()
        self.assertIn('OpenPgpBackupCodec.maxRecipients', swift_host)
        self.assertIn('!openPgpRecipients.contains(data)', swift_host)

        email_dialog = (ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/SharedEmailSettingsDialog.kt').read_text()
        self.assertIn('Add SMTP identity', email_dialog)
        self.assertIn('PasswordVisualTransformation()', email_dialog)
        self.assertIn('input = input.withoutSecrets()', email_dialog)
        self.assertIn('clearAddedPasswords()', swift_host)

        prefs_kt = (ROOT / 'core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/PreferencesExport.kt').read_text()
        prefs_swift = (ROOT / 'ios/Sources/OfflineOTPKit/PreferencesExport.swift').read_text()
        for key in ('ui_selected_tags', 'ui_untagged_selected'):
            self.assertIn(f'"{key}"', prefs_kt)
            self.assertIn(f'"{key}"', prefs_swift)
        self.assertIn('"ui_minimize_on_copy"', prefs_kt)
