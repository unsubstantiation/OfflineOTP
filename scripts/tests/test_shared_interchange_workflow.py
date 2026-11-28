"""Chunk-7 delegation and secret-ownership ratchet; source checks are not native build evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOMAIN = 'shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/'
CORE = 'core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/'
IOS = 'ios/App/Sources/'
KIT = 'ios/Sources/OfflineOTPKit/'

def read(path):
    return (ROOT / path).read_text()

class SharedInterchangeWorkflowTest(unittest.TestCase):
    def test_common_codecs_have_no_native_parser_crypto_or_document_access(self):
        for name in ['InterchangeCodec', 'InterchangeEncoding', 'CsvSyntax', 'BrowserCsvCodec', 'ThunderbirdCsvCodec',
                     'BrowserCsvPolicy', 'ProvisioningCodec', 'PortableOtp', 'MigrationInterchangeCodec',
                     'CredentialMaterialCodec', 'CredentialDocumentCodec', 'LinScpSessionCodec',
                     'PreferencesInterchangeCodec', 'InterchangeWorkflow']:
            source = read(DOMAIN + name + '.kt')
            for forbidden in ['import java.', 'import javax.', 'import android.', 'import platform.', 'org.json.',
                              'File(', 'Context', 'SecretKey', '@Synchronized', 'JSONObject']:
                self.assertNotIn(forbidden, source, name)
        policy = read(DOMAIN + 'InterchangeWorkflow.kt')
        self.assertNotIn('ByteArray', policy)
        self.assertIn('currentGeneration == generation', policy)
        self.assertIn('PortableBatchStatus.DIFFERENT_EXPORT', policy)

    def test_jvm_codecs_delegate_instead_of_retaining_parallel_tokenizers(self):
        for native, shared in [('BrowserPasswordCsv', 'BrowserCsvCodec'), ('ThunderbirdCsv', 'ThunderbirdCsvCodec'),
                               ('OtpUriParser', 'ProvisioningCodec'), ('GoogleAuthenticatorMigration', 'MigrationInterchangeCodec'),
                               ('PasswordMaterial', 'CredentialMaterialCodec'), ('SshKeyMaterial', 'CredentialMaterialCodec')]:
            source = read(CORE + native + '.kt')
            self.assertIn(shared, source)
            self.assertNotIn('private fun readRecords', source)
            self.assertNotIn('private class ProtoReader', source)
            self.assertNotIn('import java.net.URI', source)
            self.assertNotIn('ByteBuffer.allocate', source)
        self.assertIn('ProvisioningCodec.encode(', read('core/src/main/kotlin/com/fairuniversal/offlineotp/Database/Entry.kt'))
        self.assertIn('BrowserCsvPolicy()', read(CORE + 'PasswordCsvExport.kt'))
        self.assertIn('CsvSyntax.readRecords', read(DOMAIN + 'BrowserCsvCodec.kt'))
        self.assertIn('CsvSyntax.readRecords', read(DOMAIN + 'ThunderbirdCsvCodec.kt'))

    def test_preferences_validate_all_known_types_before_native_mutation(self):
        native = read(CORE + 'PreferencesExport.kt')
        block = native.split('fun import(bytes:', 1)[1].split('private fun write(', 1)[0]
        self.assertIn('PreferencesInterchangeCodec().decode(', block)
        self.assertIn('plan.error == "PLATFORM"', block)
        self.assertLess(block.index('val accepted = plan.accepted.map'), block.index('port.write('))
        shared = read(DOMAIN + 'PreferencesInterchangeCodec.kt')
        self.assertIn('value.decimal.toIntOrNull()', shared)
        self.assertIn('if (kind == "SECRET") secrets = true', shared)
        self.assertIn('key in deviceBound', shared)
        self.assertIn('value.values.all', shared)
        host = read(IOS + 'ComposeVaultHost.swift')
        for method in ['inspect(pendingImport', 'apply(pendingImport', 'export(store.preferencesPort']:
            line = next(line for line in host.splitlines() if 'PreferencesExport.' + method in line)
            self.assertIn('interchange: store.interchangePolicy', line)

    def test_apple_production_uses_shared_adapters_without_failure_fallback(self):
        store = read(IOS + 'VaultStore.swift')
        self.assertIn('InterchangeAdapter = SharedInterchangeAdapter()', store)
        for marker in ['OtpUri.parse(trimmed, interchange: interchange)', 'OtpUri.uri(for: entries[index], interchange: interchange)',
                       'MigrationPayload.parse(text, interchange: interchange)', 'interchange.migrationBatch(generation: generation)',
                       'PasswordImport.entries(filename: filename, data: data, mode: mode, interchange: interchange)',
                       'ThunderbirdImport.entries(from: data, interchange: interchange)']:
            self.assertIn(marker, store)
        adapter = read(IOS + 'SharedInterchangeAdapter.swift')
        for marker in ['codec.otp(', 'codec.migration(', 'codec.csv(', 'codec.exportCsv(', 'PreferencesInterchangeCodec()',
                       'CredentialDocumentCodec()', 'LinScpSessionCodec()', 'MigrationBatchPolicy(', 'defer { clear(input) }']:
            self.assertIn(marker, adapter)
        self.assertNotIn('NativeInterchange', adapter)
        self.assertNotIn('OtpUri.parse(', adapter)
        self.assertIn('CredentialMaterialCodec().validateEncoded', read(IOS + 'SharedEntryDocumentAdapter.swift'))
        backup = read(KIT + 'EncryptedBackup.swift')
        self.assertIn('LinScpVault.openToDocument(password: password, data: data, interchange: interchange)', backup)
        self.assertIn('@Volatile private var publishedGeneration', read('desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopVaultState.kt'))

    def test_linscp_and_email_share_shape_material_and_ini_validation_above_native_crypto(self):
        lin = read(CORE + 'LinScpCredentials.kt')
        self.assertIn('CredentialDocumentCodec().linScp(text, false)', lin)
        self.assertIn('CredentialDocumentCodec().linScp(jsonText, true)', lin)
        self.assertIn('LinScpSessionCodec().parse(iniText)', lin)
        self.assertNotIn('for (rawLine in iniText.lineSequence())', lin)
        self.assertIn('CredentialMaterialCodec().emailValid(bytes)', read(CORE + 'EmailMaterial.kt'))
        swift = read(KIT + 'LinScpVault.swift')
        self.assertIn('interchange?.linScp(jsonText, bundle: false)', swift)
        self.assertIn('interchange?.linScp(jsonText, bundle: true)', swift)
        self.assertIn('interchange.linScpIni(iniText)', swift)
        self.assertIn('VaultCrypto.argon2id(', swift)
        self.assertIn('authenticating: Data(magic)', swift)

    def test_pending_import_and_export_workers_consume_generation_bound_delivery_once(self):
        for name in ['PendingEntryImport', 'PendingMigrationImport']:
            source = read(CORE + name + '.kt')
            self.assertIn('InterchangeWorkflow(generation)', source)
            self.assertIn('workflow.consume(currentGeneration, sessionReady)', source)
            self.assertIn('workflow.cancel()', source)
        self.assertIn('MigrationBatchPolicy(generation)', read(CORE + 'GoogleAuthenticatorMigration.kt'))
        store = read(IOS + 'VaultStore.swift')
        self.assertIn('pending == entries', store)
        self.assertIn('migrationConfirmation?.consume(currentGeneration:', store)
        self.assertIn('let entries = pending', store)
        for name in ['Password', 'Archive', 'Email', 'Thunderbird', 'Preferences', 'Encrypted', 'OpenPgp']:
            source = read('desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/Desktop' + name + 'Export.kt')
            self.assertIn('InterchangeWorkflow(generation)', source)
            self.assertIn('delivery.consume(generation, ready)', source)
            self.assertIn('task.take(state.generation, !state.isLocked)', source)
        pgp = read('desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopOpenPgpExport.kt')
        self.assertIn('RpgpOpenPgpPort()', pgp)
        self.assertIn('OpenPgpBackupCodec.seal(port, input.document, keys)', pgp)

    def test_native_regressions_are_retained_until_framework_and_device_validation(self):
        self.assertIn('testSharedProvisioningRoundTripsTheNativeEntryWithoutLosingTagsOrCounter', read('ios/App/Tests/SharedInterchangeAdapterTests.swift'))
        self.assertIn('testMigrationConfirmationCannotReplayAfterLockOrSuccessfulConsumption', read('ios/App/Tests/VaultStoreTests.swift'))
        self.assertTrue((ROOT / KIT / 'OtpUri.swift').exists())
        self.assertTrue((ROOT / KIT / 'PasswordCredentials.swift').exists())
        self.assertTrue((ROOT / KIT / 'MigrationImport.swift').exists())
