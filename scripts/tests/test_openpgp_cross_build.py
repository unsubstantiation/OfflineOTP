"""Source-level engine contract, not a substitute for release/device crypto tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/kotlin/com/fairuniversal/offlineotp'
DESKTOP = ROOT / 'desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop'
UI = ROOT / 'ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault'
IOS = ROOT / 'ios/App/Sources'

class OpenPgpCrossBuildTest(unittest.TestCase):
    def test_all_clients_explain_external_apps_are_not_required(self):
        notice = 'OpenPGP runs in this app using key files. No external OpenPGP app is required.'
        for path in (ROOT / 'app/src/main/res/values/strings_backup.xml',
                     UI / 'OpenPgpExportDialog.kt', IOS / 'ComposeVaultHost.swift'):
            self.assertIn(notice, path.read_text())
        for dialog in ('OpenPgpImportDialog.kt', 'OpenPgpExportDialog.kt'):
            self.assertIn('Text(text = OPENPGP_IN_APP_NOTICE)', (UI / dialog).read_text())

    def test_desktop_distributions_use_in_process_engines(self):
        # Windows, macOS and Linux use these same controllers, not separate platform flows.
        for controller in ('DesktopOpenPgpImport.kt', 'DesktopOpenPgpExport.kt', 'BackupCoordinator.kt'):
            source = (DESKTOP / 'control' / controller).read_text()
            self.assertIn('RpgpOpenPgpPort()', source)
        adapter = (DESKTOP / 'openpgp/RpgpOpenPgpPort.kt').read_text()
        self.assertIn('PgpainlessOpenPgpPort()', adapter)  # Also an embedded library, not GnuPG.
        self.assertNotIn('ProcessBuilder', adapter)
        host = (ROOT / 'desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt').read_text()
        self.assertIn('OpenPgpExportDialog(', host)
        self.assertIn('OpenPgpImportDialog(', host)

    def test_ios_defaults_to_bundled_engine_and_gates_unavailable_builds(self):
        store = (IOS / 'VaultStore.swift').read_text()
        self.assertIn('openPgp: OpenPgpPort = RustOpenPgpPort()', store)
        port = (ROOT / 'ios/Sources/OfflineOTPKit/OpenPgp.swift').read_text()
        self.assertIn('#if OFFLINEOTP_OPENPGP_NATIVE', port)
        self.assertIn('offlineotp_openpgp.encrypt(', port)
        self.assertIn('offlineotp_openpgp.decrypt(', port)
        self.assertIn('throw Self.unavailable()', port)
        self.assertIn('availability.canEncrypt', (IOS / 'ComposeVaultHost.swift').read_text())
        self.assertIn('availability.canRestore', (IOS / 'ComposeVaultHost.swift').read_text())

    def test_android_preserves_optional_provider_without_requiring_it(self):
        settings = (APP / 'Utilities/Settings.kt').read_text()
        self.assertIn('if (getOpenPGPProvider().isBlank()) OpenPgpMode.KEY_FILE_VALUE', settings)
        backup = (APP / 'Activities/BackupActivity.kt').read_text()
        self.assertIn('OpenPgpExportComposeView(', backup)
        self.assertIn('OpenPgpKeyFileBackupTask(this, entries, recipients, openPgpPort, uri)', backup)
        self.assertIn('OpenPgpKeyFileRestoreTask(this, uri, secretFile, passphrase, openPgpPort)', backup)
        self.assertIn('bindOpenPgpService(provider)', backup)
        self.assertIn('!keyFileBackendAvailable ->', backup)
